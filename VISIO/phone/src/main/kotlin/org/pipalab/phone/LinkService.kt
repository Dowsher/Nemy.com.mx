package org.pipalab.phone
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.pipalab.core.*
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

data class LinkStatus(val text:String="Enlace apagado",val connected:Boolean=false,val recording:Boolean=false,val session:String="",val lastData:Long=0,val battery:Int=-1,val sync:String="",val calibrated:Boolean=false,val clockDelta:Long=0,val compact:Boolean=false,val protocol:Int=1,val stage:Int=-1,val watchElapsed:Long=0,val trainingId:String="",val pendingTrainingId:String="",val activity:String="",val seconds:Long=0,val durationSeconds:Int=0,val audioState:String="off",val audioSeconds:Double=0.0,val audioPeak:Double=0.0,val audioSilenced:Boolean=false,val audioMessage:String="",val stopping:Boolean=false,val remoteReady:Boolean=false,val statusReceivedNs:Long=0)
object PhoneLive { val state=MutableStateFlow(LinkStatus()); val clock=SessionClock() }
class LinkService:Service() {
    private lateinit var worker:HandlerThread
    private lateinit var handler:Handler
    private lateinit var db:Store
    private var endpoint:BleClient?=null
    private lateinit var detector:ReferenceDetector
    private var lastDemoImpulse=0L
    private var lastCatalog=0L
    private var chunkRequestedMs=0L
    private var pending:Transfer?=null
    private val queue=ArrayDeque<Transfer>()
    private val sessionFiles=mutableMapOf<String,List<Transfer>>()
    private var wasRecording=false
    private data class Transfer(val session:String,val name:String,val size:Long,val sha:String)
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate(); db=Store.get(this)
        getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("detection",false).putBoolean("esp_automatic",false).apply()
        Notices.channels(this)
        val stop=PendingIntent.getService(this,7,Intent(this,LinkService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"link").setSmallIcon(R.drawable.ic_lab).setContentTitle("Visio · enlace local")
            .setContentText("Recibiendo y guardando las señales del reloj").setOngoing(true).setContentIntent(Notices.open(this))
            .addAction(Notification.Action.Builder(null,"Desconectar",stop).build()).build()
        startForeground(90,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        worker=HandlerThread("PipaPhoneLink").apply{start()}; handler=Handler(worker.looper)
        detector=ReferenceDetector(this) {message->handler.post {send(message)}}
        handler.post {
            runCatching {
                val key=PairingKeys.load(this) ?: error("Falta configurar el vínculo privado")
                endpoint=BleClient(this,handler,key,{ text,ready ->
                    PhoneLive.state.value=PhoneLive.state.value.copy(text=text,connected=ready,recording=if(ready) PhoneLive.state.value.recording else false)
                    if(!ready) { PhoneLive.clock.clear(); pending=null; queue.clear(); lastDemoImpulse=0L; getSharedPreferences("prefs",MODE_PRIVATE).edit().apply() }
                },::receive).also{it.start()}
                handler.post(tick)
            }.onFailure{PhoneLive.state.value=LinkStatus(text=it.message ?: "Error al conectar"); stopSelf()}
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int {
        if(intent?.action=="STOP") stopSelf()
        if(intent?.action in setOf("TRAINING_START","TRAINING_PREPARE","TRAINING_CANCEL","TRAINING_STOP","TRAINING_POINT")) handler.post {
            val training=TrainingStore(db)
            val key=intent?.getStringExtra("id") ?: return@post
            val live=PhoneLive.state.value
            if(intent.action=="TRAINING_STOP") {
                val prefs=getSharedPreferences("capture_stop",0)
                prefs.edit().putStringSet("ids",prefs.getStringSet("ids",emptySet()).orEmpty()+key).commit()
            }
            if(!live.connected || live.protocol<3) { if(intent.action=="TRAINING_PREPARE")training.status(key,if(live.connected)"unsupported" else "unconfirmed");return@post }
            when(intent.action) {
                "TRAINING_START" -> training.records().find{it.id==key}?.let {
                    val probe=PhoneLive.clock.best(SystemClock.elapsedRealtimeNanos())
                    if(live.protocol<4 || !live.remoteReady || probe==null) training.status(key,"not_ready")
                    else send(JSONObject().put("type","training_start").put("capture",JSONObject(it.original))
                        .put("expires_watch_ns",SystemClock.elapsedRealtimeNanos()+probe.offset+10_000_000_000L))
                }
                "TRAINING_PREPARE" -> training.records().find{it.id==key}?.let {
                    send(JSONObject().put("type","training_prepare").put("capture",JSONObject(it.original)))
                    handler.postDelayed({training.records().find{r->r.id==key && r.status=="requested"}?.let{training.status(key,"unconfirmed")}},12000)
                }
                "TRAINING_CANCEL" -> send(JSONObject().put("type","training_cancel").put("id",key))
                "TRAINING_STOP" -> send(JSONObject().put("type","training_stop").put("id",key))
                "TRAINING_POINT" -> {
                    val label=intent.getStringExtra("label") ?: return@post
                    val pointId=java.util.UUID.randomUUID().toString();val now=System.currentTimeMillis()
                    training.pointRequest(pointId,key,label,now)
                    send(JSONObject().put("type","training_point").put("training_id",key).put("id",pointId).put("label",label).put("phone_wall_ms",now))
                }
            }
        }
        if(intent?.action in listOf("MEASURE","RETAIN"))handler.post {
            val key=intent?.getStringExtra("id") ?: return@post
            val live=PhoneLive.state.value
            val manual=intent.action=="MEASURE"
            val now=System.currentTimeMillis()-live.clockDelta
            if(manual)db.window(key,live.session,"medicion_manual",now,now+30000,now,"requested")
            if(!live.connected || !live.recording || live.protocol<2) {
                if(manual)db.window(key,live.session,"medicion_manual",now,now+30000,now,"failed")
                return@post
            }
            send(JSONObject().put("type","retain").put("id",key).put("manual",manual).put("kind",intent.getStringExtra("kind") ?: "evento")
                .put("from",intent.getLongExtra("from",now)).put("to",intent.getLongExtra("to",now)))
            if(manual)handler.postDelayed({db.measurements().find{it.id==key && it.status=="requested"}?.let{db.window(it.id,it.session,it.kind,it.start,it.end,it.available,"unconfirmed")}},10000)
        }
        return START_NOT_STICKY
    }
    private fun send(j:JSONObject) { endpoint?.send(j.toString()) }
    private fun receive(text:String) {
        try {
            val j=JSONObject(text)
            when(j.getString("type")) {
                "detection_response" -> {
                    if(DetectionStore(db).review(j.getString("event_id"),j.getString("label"),source="watch",responseId=j.getString("id"),original=j))
                        send(JSONObject().put("type","detection_response_ack").put("id",j.getString("id")))
                }
                "audio_meter" -> AudioMeter.accept(j)

                "clock_pong" -> PhoneLive.clock.add(ClockProbe(j.getLong("p1"),j.getLong("w2"),j.getLong("w3"),SystemClock.elapsedRealtimeNanos()))
                "stop_ack" -> {
                    val prefs=getSharedPreferences("capture_stop",0)
                    prefs.edit().putStringSet("ids",prefs.getStringSet("ids",emptySet()).orEmpty()-j.getString("id")).commit()
                    TrainingStore(db).status(j.getString("id"),"cancelled")
                }
                "status" -> {
                    val recording=j.getBoolean("recording"); val session=j.optString("session")
                    if(session!=PhoneLive.state.value.session)lastDemoImpulse=0L
                    if(!recording)getSharedPreferences("prefs",MODE_PRIVATE).edit().apply()
                    if(recording!=wasRecording) endpoint?.priority(recording || pending!=null)
                    PhoneLive.state.value=PhoneLive.state.value.copy(recording=recording,session=session,battery=j.optInt("battery",-1),clockDelta=System.currentTimeMillis()-j.getLong("wall"),compact=j.optBoolean("compact"),protocol=j.optInt("protocol",1),watchElapsed=j.optLong("elapsed"))
                    PhoneLive.state.value=PhoneLive.state.value.copy(trainingId=j.optString("training_id"),pendingTrainingId=j.optString("pending_training_id"),activity=j.optString("activity"),
                        seconds=j.optLong("seconds"),durationSeconds=j.optInt("duration_seconds"),audioState=j.optString("audio_state","off"),audioSeconds=j.optDouble("audio_seconds",0.0),
                        audioPeak=j.optDouble("audio_peak",0.0),audioSilenced=j.optBoolean("audio_silenced"),audioMessage=j.optString("audio_message"),stopping=j.optBoolean("stopping"),remoteReady=j.optBoolean("remote_ready"),statusReceivedNs=SystemClock.elapsedRealtimeNanos())
                    TrainingStore(db).live(j.optString("training_id"),session,recording,j.optBoolean("stopping"),j.optString("audio_state","off"),j.optDouble("audio_seconds",0.0))
                    if(wasRecording && !recording) lastCatalog=0
                    wasRecording=recording
                }
                "meta" -> db.putMeta(j.getString("session"),j.getJSONObject("meta"))
                "training_ack" -> TrainingStore(db).status(j.getString("id"),j.getString("state"))
                "point_ack" -> if(j.optBoolean("ok")) TrainingStore(db).pointAck(j.getString("id"),j.getLong("wall"))
                "audio_impulse" -> {
                    val live=PhoneLive.state.value;val elapsed=j.getLong("elapsed")
                    val fresh=System.currentTimeMillis()-live.clockDelta-j.getLong("wall") in -1000..3500
                    if(getSharedPreferences("prefs",MODE_PRIVATE).getBoolean("audio_demo",false) && live.connected && EspLive.state.value.ready && live.audioState=="recording" && !live.audioSilenced && live.recording && live.trainingId==j.getString("training_id") && fresh && elapsed-lastDemoImpulse>1_500_000_000L) {
                        lastDemoImpulse=elapsed;EspTrigger.pulse(this,j.getString("id"),"demo_sound")
                    }
                }
                "rows" -> {
                    val id=j.getString("session"); val file=j.getString("file"); val stream=j.getString("stream")
                    val columns=j.getString("columns").split(","); val rows=j.getJSONArray("rows")
                    val values=ArrayList<Pair<Long,JSONObject>>()
                    for(i in 0 until rows.length()) {
                        val a=rows.getJSONArray(i); require(a.length()==columns.size+1)
                        val row=JSONObject(); columns.forEachIndexed{k,c->row.put(c,a.get(k+1))}
                        values.add(a.getLong(0) to row)

                    }
                    detector.add(id,stream,values.map{it.second})
                    if(j.optBoolean("persist",true))db.insertRows(id,file,stream,values)
                    if(stream !in listOf("resumen_minuto","ventanas"))LiveSamples.add(id,stream,values.map{(idx,row)->Sample(row.getLong("t_wall_ms"),row.getLong("t_elapsed_ns"),row.keys().asSequence().associateWith{row.optString(it)},if(j.optBoolean("persist",true))"raw" else "live",file,idx)})
                    if(stream !in listOf("resumen_minuto","ventanas"))PhoneLive.state.value=PhoneLive.state.value.copy(lastData=System.currentTimeMillis(),session=id)
                }
                "retain_ack" -> {
                    val id=j.getString("id");val m=db.measurements().find{it.id==id}
                    if(m!=null)db.window(id,j.optString("session",m.session),m.kind,j.optLong("start",m.start),j.optLong("end",m.end),j.optLong("available_from",m.available),if(j.optBoolean("ok"))"open" else "failed")
                }
                "catalog" -> {
                    if(pending!=null) return
                    lastCatalog=System.currentTimeMillis()
                    if(j.optInt("offset",0)==0) { queue.clear(); sessionFiles.clear() }
                    val sessions=j.getJSONArray("sessions")
                    for(i in 0 until sessions.length()) {
                        val s=sessions.getJSONObject(i); val id=s.getString("session"); db.putMeta(id,s.getJSONObject("meta"));db.pendingVerification(id)
                        val files=s.getJSONArray("files"); val list=ArrayList<Transfer>()
                        for(f in 0 until files.length()) {
                            val a=files.getJSONObject(f); val t=Transfer(id,a.getString("name"),a.getLong("size"),a.getString("sha"))
                            require(ActivityTypes.transferable(t.name))
                            list.add(t)
                            if(!known(t)) queue.addLast(t)
                        }
                        sessionFiles[id]=list
                    }
                    if(j.optInt("next",-1)>=0) send(JSONObject().put("type","catalog").put("offset",j.getInt("next"))) else next()
                }
                "chunk" -> {
                    val t=pending ?: return
                    require(j.getString("session")==t.session && j.getString("name")==t.name)
                    val part=db.safeFile(t.session,t.name+".part")
                    require(j.getLong("offset")==part.length())
                    val bytes=Base64.getDecoder().decode(j.getString("data"))
                    require(part.length()+bytes.size<=t.size && (bytes.isNotEmpty() || part.length()==t.size))
                    part.appendBytes(bytes)
                    PhoneLive.state.value=PhoneLive.state.value.copy(sync="Copiando "+t.name+" · "+(part.length()*100/t.size.coerceAtLeast(1))+"%")
                    if(part.length()==t.size) {
                        if(db.sha(part)!=t.sha) { check(part.delete()); error("La copia se reintentará: verificación incompleta") }
                        val destination=db.safeFile(t.session,t.name)
                        java.nio.file.Files.move(part.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        db.importFile(t.session,destination,t.sha); pending=null; next()
                    } else requestChunk(t,part.length())
                }
            }
        } catch(e:Exception) {
            PhoneLive.state.value=PhoneLive.state.value.copy(sync=e.message ?: "Reintentando copia")
            pending=null; queue.clear(); lastCatalog=0
            android.util.Log.w("PipaPhone","Transfer interrupted",e)
        }
    }
    private fun known(t:Transfer):Boolean {
        return db.readableDatabase.rawQuery("SELECT sha256 FROM files WHERE session=? AND name=?",arrayOf(t.session,t.name)).use{it.moveToFirst() && it.getString(0)==t.sha}
    }
    private fun next() {
        sessionFiles.forEach{(id,list)->if(list.all(::known)) db.completed(id)}
        val t=queue.removeFirstOrNull()
        if(t==null) { endpoint?.priority(PhoneLive.state.value.recording); PhoneLive.state.value=PhoneLive.state.value.copy(sync="Sesiones cerradas sincronizadas");LearningEngine.get(this).refresh(); return }
        pending=t
        endpoint?.priority(true)
        val part=db.safeFile(t.session,t.name+".part")
        if(part.length()>t.size) check(part.delete())
        requestChunk(t,part.length())
    }
    private fun requestChunk(t:Transfer,offset:Long) {
        chunkRequestedMs=System.currentTimeMillis()
        send(JSONObject().put("type","chunk").put("session",t.session).put("name",t.name).put("offset",offset))
    }
    private val tick=object:Runnable { override fun run() {
        if(PhoneLive.state.value.connected) {
            val now=System.currentTimeMillis()
            if(PhoneLive.state.value.protocol>=4) send(JSONObject().put("type","clock_ping").put("p1",SystemClock.elapsedRealtimeNanos()))
            getSharedPreferences("capture_stop",0).getStringSet("ids",emptySet()).orEmpty().forEach {send(JSONObject().put("type","training_stop").put("id",it))}
            if(pending!=null && now-chunkRequestedMs>20000) {pending=null;queue.clear();lastCatalog=0;PhoneLive.state.value=PhoneLive.state.value.copy(sync="Reanudando copia desde el último bloque recibido")}
            if(pending==null && now-lastCatalog>20000) { lastCatalog=now; send(JSONObject().put("type","catalog")) }
            else send(JSONObject().put("type","ping"))
        }
        handler.postDelayed(this,3000)
    } }
    override fun onDestroy() {
        if(::detector.isInitialized)detector.close()
        if(::handler.isInitialized) handler.post { handler.removeCallbacks(tick); endpoint?.stop(); worker.quitSafely() }
        PhoneLive.state.value=PhoneLive.state.value.copy(connected=false,recording=false,text="Enlace apagado")
        super.onDestroy()
    }
}
object Notices {
    fun channels(context:Context) {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link","Conexión local",NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("candidates","Eventos por revisar",NotificationManager.IMPORTANCE_HIGH))
    }
    fun open(context:Context,id:String=""):PendingIntent = PendingIntent.getActivity(context,if(id.isBlank())0 else id.hashCode(),
        Intent(context,MainActivity::class.java).putExtra("event",id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun candidate(context:Context,id:String,test:Boolean) {
        channels(context)
        val discard=PendingIntent.getBroadcast(context,id.hashCode(),Intent(context,FeedbackReceiver::class.java).putExtra("id",id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(context,"candidates").setSmallIcon(R.drawable.ic_lab)
            .setContentTitle(if(test) "Prueba · ¿fue un consumo?" else "¿Fue un consumo?")
            .setContentText(if(test) "Aviso de prueba; excluido del entrenamiento." else "Patrón de movimiento experimental. Ayúdanos a clasificarlo.")
            .setAutoCancel(true).setContentIntent(open(context,id))
            .addAction(Notification.Action.Builder(null,"Revisar",open(context,id)).build())
            .addAction(Notification.Action.Builder(null,"Descartar",discard).build()).build()
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(),n)
    }
}
class FeedbackReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getStringExtra("id") ?: return
        val pending=goAsync()
        Thread {
            try {
                val db=Store.get(context); val event=db.events().find{it.id==id} ?: return@Thread
                db.feedback(id,"false_positive",event.kind,"Descartado desde notificación","notification")
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
            } finally { pending.finish() }
        }.start()
    }
}
