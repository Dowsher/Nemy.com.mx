package org.pipalab.wear
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue

object PhoneBridge {
    data class Row(val session:String,val file:String,val stream:String,val idx:Long,val values:List<Any?>,val persist:Boolean=true)
    val notices=ArrayBlockingQueue<String>(30)
    val rows=ArrayBlockingQueue<Row>(3000)
    @Volatile var listening=false
    private val latest=mutableMapOf<String,Row>()
    private var currentSession=""
    @Synchronized fun offer(session:String,file:String,stream:String,idx:Long,values:List<Any?>,persist:Boolean=true) {
        if(currentSession!=session) { latest.clear(); currentSession=session }
        val row=Row(session,file,stream,idx,values,persist)
        if((latest[stream]?.values?.get(0) as? Long ?: -1L)<=(values[0] as Long))latest[stream]=row
        if(listening) rows.offer(row)
    }
    @Synchronized fun connection(ready:Boolean) {
        listening=ready; rows.clear()
        // On-change sensors may never fire again after reconnection. Replay their
        // actual latest rows with original indices/timestamps; phone inserts dedupe.
        if(ready && Live.state.value.active) latest.values.forEach { rows.offer(it) }
    }
}
class LinkServerService:Service() {
    private lateinit var thread:HandlerThread
    private lateinit var handler:Handler
    private var endpoint:BleServer?=null
    private var connected=false
    private var tick=0
    private val hashCache=mutableMapOf<String,Pair<Long,String>>()
    private val root get()=File(getExternalFilesDir(null),"sesiones")
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("phone-link","Enlace local",NotificationManager.IMPORTANCE_LOW))
        val p=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"phone-link").setSmallIcon(R.drawable.ic_record).setContentTitle("Enlace al teléfono")
            .setContentText("Bluetooth directo · datos locales").setContentIntent(p).setOngoing(true).build()
        startForeground(42,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        thread=HandlerThread("LocalPhoneLink").apply{start()}; handler=Handler(thread.looper)
        handler.post {
            runCatching {
                TrainingInbox.recoverInterrupted(this)
                TrainingInbox.load(this)
                val key=PairingKeys.load(this) ?: error("Configura el vínculo desde la PC")
                endpoint=BleServer(this,handler,key,{ connected=it; PhoneBridge.connection(it) },::request).also{it.start()}
                handler.post(pulse)
            }.onFailure{android.util.Log.e("PipaLink","No se pudo iniciar enlace",it); stopSelf()}
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int { if(intent?.action=="STOP_LINK") stopSelf(); return START_NOT_STICKY }
    private val pulse=object:Runnable { override fun run() {
        if(connected) {
            while(true) {val notice=PhoneBridge.notices.poll() ?: break;endpoint?.send(notice)}
            val rows=ArrayList<PhoneBridge.Row>(); PhoneBridge.rows.drainTo(rows,1500)
            rows.groupBy{it.session to it.file}.forEach{(group,items)->
                endpoint?.send(JSONObject().put("type","rows").put("session",group.first).put("file",group.second)
                    .put("persist",items.first().persist)
                    .put("stream",items.first().stream).put("columns","t_elapsed_ns,t_wall_ms,"+Schema.columns.getValue(items.first().stream))
                    .put("rows",JSONArray().also { a->items.forEach{a.put(JSONArray(listOf(it.idx)+it.values))} }).toString(),!items.first().persist)
            }
            if(tick++%4==0) heartbeat()
        }
        handler.postDelayed(this,1000)
    } }
    private fun heartbeat() {
        val live=Live.state.value
        endpoint?.send(JSONObject().put("type","status").put("recording",live.active).put("session",live.session)
            .put("wall",System.currentTimeMillis()).put("battery",live.battery).put("health",live.health)
            .put("elapsed",SystemClock.elapsedRealtimeNanos())
            .put("sensor_profile","multimodal_v4").put("motion_hz",25).put("protocol",4).put("remote_ready",RemoteReady.state.value).put("compact",live.captureId.isBlank() && getSharedPreferences("setup",MODE_PRIVATE).getBoolean("compact",true))
            .put("training_id",live.captureId).put("activity",live.activity).put("seconds",live.seconds).put("duration_seconds",live.durationSeconds)
            .put("pending_training_id",TrainingInbox.pending.value?.id.orEmpty()).put("audio_state",live.audio.state)
            .put("audio_seconds",live.audio.seconds).put("audio_peak",live.audio.peak).put("audio_silenced",live.audio.silenced)
            .put("audio_message",live.audio.message).put("stopping",live.stopping).toString())
    }
    private fun sha(file:File):String {
        val key=file.absolutePath
        hashCache[key]?.let{if(it.first==file.length()) return it.second}
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}}
        return md.digest().joinToString(""){"%02x".format(it)}.also{hashCache[key]=file.length() to it}
    }
    private fun request(text:String) {
        val j=JSONObject(text)
        when(j.getString("type")) {
            "clock_ping" -> {
                val received=SystemClock.elapsedRealtimeNanos()
                endpoint?.send(JSONObject().put("type","clock_pong").put("p1",j.getLong("p1"))
                    .put("w2",received).put("w3",SystemClock.elapsedRealtimeNanos()).toString())
            }
            "training_start" -> {
                val capture=ActivityCapture.parse(j.getJSONObject("capture"))
                val expires=j.optLong("expires_watch_ns")
                val now=SystemClock.elapsedRealtimeNanos()
                val state=when {
                    !RemoteReady.state.value -> "not_ready"
                    expires !in now..(now+15_000_000_000L) -> "expired"
                    Live.state.value.active && Live.state.value.captureId==capture.id -> "already_started"
                    !capture.audio -> "unsupported"
                    else -> TrainingInbox.prepare(this,capture)
                }
                if(state=="prepared") {
                    val prefs=getSharedPreferences("setup",MODE_PRIVATE)
                    startService(Intent(this,RecorderService::class.java).setAction("START").putExtra("remote",true)
                        .putExtra("training_id",capture.id).putExtra("expires",expires)
                        .putExtra("capture",capture.json().toString()).putExtra("consent",true)
                        .putExtra("participant",prefs.getString("participant","P001")).putExtra("wrist",prefs.getString("wrist","izq"))
                        .putExtra("dominant",prefs.getString("dominant","der")).putExtra("power","auto"))
                }
                endpoint?.send(JSONObject().put("type","training_ack").put("id",capture.id).put("state",state).toString())
                heartbeat()
            }
            "training_prepare" -> {
                val capture = ActivityCapture.parse(j.getJSONObject("capture"))
                val state = TrainingInbox.prepare(this, capture)
                endpoint?.send(JSONObject().put("type", "training_ack").put("id", capture.id).put("state", state).toString())
                heartbeat()
            }
            "training_cancel" -> {
                val id=j.getString("id")
                endpoint?.send(JSONObject().put("type", "training_ack").put("id", id)
                    .put("state", if(TrainingInbox.cancel(this,id)) "cancelled" else "not_pending").toString())
                heartbeat()
            }
            "training_stop" -> {
                TrainingInbox.block(this,j.getString("id"))
                if(Live.state.value.active && Live.state.value.captureId == j.getString("id"))
                startService(Intent(this, RecorderService::class.java).setAction("STOP").putExtra("training_id", j.getString("id")))
                endpoint?.send(JSONObject().put("type","stop_ack").put("id",j.getString("id")).toString())
            }
            "training_point" -> if(Live.state.value.active && Live.state.value.captureId == j.getString("training_id")) {
                require(j.getString("id").matches(Regex("[A-Za-z0-9_-]{1,80}")))
                startService(Intent(this, RecorderService::class.java).setAction("POINT").putExtra("id", j.getString("id"))
                    .putExtra("training_id", j.getString("training_id")).putExtra("label", j.getString("label"))
                    .putExtra("origin", "phone").putExtra("phone_wall_ms", j.optLong("phone_wall_ms")))
            }
            "retain" -> {
                val id=j.getString("id");require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                if(!Live.state.value.active) endpoint?.send(JSONObject().put("type","retain_ack").put("id",id).put("ok",false).put("message","Inicia una sesión en el reloj").toString())
                else startService(Intent(this,RecorderService::class.java).setAction("RETAIN").putExtra("id",id)
                    .putExtra("kind",j.optString("kind","evento")).putExtra("from",j.optLong("from"))
                    .putExtra("to",j.optLong("to")).putExtra("manual",j.optBoolean("manual")))
            }
            "hello","catalog" -> {
                heartbeat()
                val sessions=JSONArray()
                val dirs=root.listFiles()?.filter{it.isDirectory}?.sortedByDescending{it.name} ?: emptyList()
                val offset=j.optInt("offset",0).coerceIn(0,dirs.size)
                var cursor=offset
                while(cursor<dirs.size && sessions.length()==0) {
                    val dir=dirs[cursor++]
                    val m=File(dir,"meta.json")
                    if(m.exists()) {
                        val meta=JSONObject(m.readText())
                        if(meta.optBoolean("finalized")) sessions.put(JSONObject().put("session",dir.name).put("meta",meta).put("files",
                            JSONArray().also{a->dir.listFiles()?.filter{it.isFile && ActivityTypes.transferable(it.name)}?.sortedBy { if(it.extension=="wav") "z"+it.name else it.name }?.forEach{
                                a.put(JSONObject().put("name",it.name).put("size",it.length()).put("sha",sha(it)))
                            }}))
                        else if(dir.name==Live.state.value.session) endpoint?.send(JSONObject().put("type","meta").put("session",dir.name).put("meta",meta).toString())
                    }
                }
                endpoint?.send(JSONObject().put("type","catalog").put("sessions",sessions).put("offset",offset).put("next",if(cursor<dirs.size)cursor else -1).toString())
            }
            "chunk" -> {
                val id=j.getString("session"); val name=j.getString("name"); val offset=j.getLong("offset")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,120}")) && name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && !name.contains(".."))
                require(ActivityTypes.transferable(name))
                val dir=File(root,id); val file=File(dir,name)
                require(dir.canonicalFile.parentFile==root.canonicalFile && file.canonicalFile.parentFile==dir.canonicalFile)
                require(JSONObject(File(dir,"meta.json").readText()).optBoolean("finalized"))
                require(offset in 0..file.length())
                val b=ByteArray(minOf(8192L,file.length()-offset).toInt())
                RandomAccessFile(file,"r").use{it.seek(offset);it.readFully(b)}
                endpoint?.send(JSONObject().put("type","chunk").put("session",id).put("name",name).put("offset",offset)
                    .put("data",Base64.getEncoder().encodeToString(b)).toString())
            }
            "ping" -> heartbeat()
        }
    }
    override fun onDestroy() {
        PhoneBridge.connection(false)
        handler.post { handler.removeCallbacks(pulse); endpoint?.stop(); thread.quitSafely() }
        super.onDestroy()
    }
}
