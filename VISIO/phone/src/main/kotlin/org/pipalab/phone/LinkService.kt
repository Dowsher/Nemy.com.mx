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

data class LinkStatus(val text:String="Enlace apagado",val connected:Boolean=false,val recording:Boolean=false,val session:String="",val lastData:Long=0,val battery:Int=-1,val sync:String="",val calibrated:Boolean=false,val clockDelta:Long=0,val compact:Boolean=false,val protocol:Int=1,val stage:Int=-1,val watchElapsed:Long=0)
object PhoneLive { val state=MutableStateFlow(LinkStatus()) }
class LinkService:Service() {
    private lateinit var worker:HandlerThread
    private lateinit var handler:Handler
    private lateinit var db:Store
    private var endpoint:BleClient?=null
    private val detector=GestureDetector()
    private lateinit var personalization:PersonalDetector
    private var lastPersonalization=0L
    private var detectorSession=""
    private val imu=ArrayList<Pair<String,JSONObject>>()
    private var lastCatalog=0L
    private var pending:Transfer?=null
    private val queue=ArrayDeque<Transfer>()
    private val sessionFiles=mutableMapOf<String,List<Transfer>>()
    private var wasRecording=false
    private data class Transfer(val session:String,val name:String,val size:Long,val sha:String)
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate(); db=Store.get(this);personalization=PersonalDetector(this,db)
        Notices.channels(this)
        val stop=PendingIntent.getService(this,7,Intent(this,LinkService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"link").setSmallIcon(R.drawable.ic_lab).setContentTitle("Visio · enlace local")
            .setContentText("Recibiendo y guardando las señales del reloj").setOngoing(true).setContentIntent(Notices.open(this))
            .addAction(Notification.Action.Builder(null,"Desconectar",stop).build()).build()
        startForeground(90,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        worker=HandlerThread("PipaPhoneLink").apply{start()}; handler=Handler(worker.looper)
        handler.post {
            runCatching {
                val key=PairingKeys.load(this) ?: error("Falta configurar el vínculo privado")
                endpoint=BleClient(this,handler,key,{ text,ready ->
                    PhoneLive.state.value=PhoneLive.state.value.copy(text=text,connected=ready,recording=if(ready) PhoneLive.state.value.recording else false)
                    if(!ready) { pending=null; queue.clear(); imu.clear(); detector.reset() }
                },::receive).also{it.start()}
                handler.post(tick)
            }.onFailure{PhoneLive.state.value=LinkStatus(text=it.message ?: "Error al conectar"); stopSelf()}
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int {
        if(intent?.action=="STOP") stopSelf()
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
                "status" -> {
                    val recording=j.getBoolean("recording"); val session=j.optString("session")
                    if(recording!=wasRecording) endpoint?.priority(recording || pending!=null)
                    PhoneLive.state.value=PhoneLive.state.value.copy(recording=recording,session=session,battery=j.optInt("battery",-1),clockDelta=System.currentTimeMillis()-j.getLong("wall"),compact=j.optBoolean("compact"),protocol=j.optInt("protocol",1),watchElapsed=j.optLong("elapsed"))
                    if(wasRecording && !recording) lastCatalog=0
                    wasRecording=recording
                }
                "meta" -> db.putMeta(j.getString("session"),j.getJSONObject("meta"))
                "rows" -> {
                    val id=j.getString("session"); val file=j.getString("file"); val stream=j.getString("stream")
                    val columns=j.getString("columns").split(","); val rows=j.getJSONArray("rows")
                    val values=ArrayList<Pair<Long,JSONObject>>()
                    for(i in 0 until rows.length()) {
                        val a=rows.getJSONArray(i); require(a.length()==columns.size+1)
                        val row=JSONObject(); columns.forEachIndexed{k,c->row.put(c,a.get(k+1))}
                        values.add(a.getLong(0) to row)
                        if((!j.optBoolean("persist",true) || !PhoneLive.state.value.compact) && stream=="acelerometro") {
                            if(detectorSession!=id) { detector.reset();detector.setProfile(MotionProfile());lastPersonalization=0; imu.clear(); detectorSession=id }
                            imu.add(stream to row)
                            if(imu.size>2000){imu.clear();detector.reset()}
                        }
                    }
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
        if(t==null) { endpoint?.priority(PhoneLive.state.value.recording); PhoneLive.state.value=PhoneLive.state.value.copy(sync="Sesiones cerradas sincronizadas"); return }
        pending=t
        endpoint?.priority(true)
        val part=db.safeFile(t.session,t.name+".part")
        if(part.length()>t.size) check(part.delete())
        requestChunk(t,part.length())
    }
    private fun requestChunk(t:Transfer,offset:Long) {
        send(JSONObject().put("type","chunk").put("session",t.session).put("name",t.name).put("offset",offset))
    }
    private val tick=object:Runnable { override fun run() {
        personalize()
        if(PhoneLive.state.value.connected) {
            val now=System.currentTimeMillis()
            if(pending==null && now-lastCatalog>20000) { lastCatalog=now; send(JSONObject().put("type","catalog")) }
            else send(JSONObject().put("type","ping"))
            analyze()
        }
        handler.postDelayed(this,3000)
    } }
    private fun personalize() {
        if(System.currentTimeMillis()-lastPersonalization>60000) {
            runCatching{detector.setProfile(personalization.refresh(PhoneLive.state.value.session))}
                .onFailure{android.util.Log.w("VisioDetector","Perfil local pendiente",it)}
            lastPersonalization=System.currentTimeMillis()
        }
    }
    private fun analyze() {
        if(!getSharedPreferences("prefs",MODE_PRIVATE).getBoolean("detection",false)) { imu.clear(); detector.reset(); PhoneLive.state.value=PhoneLive.state.value.copy(calibrated=false,stage=-1); return }
        if(!PhoneLive.state.value.recording || db.latestLive(detectorSession,"offbody")?.values?.get("worn")!="1") {
            imu.clear(); detector.reset(); PhoneLive.state.value=PhoneLive.state.value.copy(calibrated=false,stage=-1); return
        }
        val sorted=imu.sortedBy{it.second.getLong("t_elapsed_ns")}; imu.clear()
        for((_,row) in sorted) {
            val t=row.getLong("t_elapsed_ns")
            if(PhoneLive.state.value.watchElapsed>0 && PhoneLive.state.value.watchElapsed-t>10_000_000_000L){detector.reset();continue}
            run {
                val c=detector.accel(t,row.getDouble("x_m_s2"),row.getDouble("y_m_s2"),row.getDouble("z_m_s2")) ?: continue
                val end=row.getLong("t_wall_ms"); val start=end-(c.endNs-c.startNs)/1000000
                val details=JSONObject().put("gestures",c.gestures).put("max_angle_deg",c.maxAngle).put("peak_orientation_deg_s",c.peakSpeed)
                    .put("method",c.method).put("evidence_windows",c.evidenceWindows)
                    .put("hold_seconds",c.holdSeconds).put("direction_consistency",c.consistency).put("motion_fingerprint",org.json.JSONArray(c.fingerprint))
                    .put("positive_similarity",c.positiveSimilarity).put("negative_similarity",c.negativeSimilarity)
                    .put("positive_groups",detector.profile.positiveGroups).put("negative_groups",detector.profile.negativeGroups)
                    .put("start_elapsed_ns",c.startNs).put("end_elapsed_ns",c.endNs).put("rules",GestureDetector.VERSION)
                    .put("score_is_probability",false)
                for((stream,key) in listOf("heart_rate" to "bpm","temperatura" to "skin_c")) {
                    val s=db.latestLive(detectorSession,stream)
                    val age=if(s==null)Long.MAX_VALUE else end-s.wall
                    if(s!=null && age in 0..(if(stream=="heart_rate")30000L else 180000L) && s.values["status"]==(if(stream=="heart_rate")"1" else "0"))
                        s.values[key]?.toDoubleOrNull()?.takeIf{it.isFinite()}?.let{details.put(key,it).put(key+"_age_ms",age)}
                }
                val features=details.toString()
                val id=db.event(start,end,detectorSession,"POR_CLASIFICAR","automatic",features=features)
                if(PhoneLive.state.value.protocol>=2)send(JSONObject().put("type","retain").put("id",id).put("kind","alerta_sin_confirmar").put("from",start).put("to",end))
                Notices.candidate(this,id,false)
                EspTrigger.pulse(this,id,"automatic")
            }
        }
        // Status heartbeats can arrive before newer sensor batches. Do not mark fresh
        // movement unavailable merely because its timestamp is ahead of the heartbeat.
        val now=maxOf(PhoneLive.state.value.watchElapsed,sorted.lastOrNull()?.second?.getLong("t_elapsed_ns") ?: 0)
        PhoneLive.state.value=PhoneLive.state.value.copy(calibrated=detector.calibrated(),stage=detector.stage(now))
    }
    override fun onDestroy() {
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
