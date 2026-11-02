package org.pipalab.phone

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DetectorLive {val state=MutableStateFlow(JSONObject().put("state","WAITING"))}

/** One bounded session in memory; replay preserves the reference's causal filters. */
class ReferenceDetector(private val context:Context,private val prompt:(JSONObject)->Unit) {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val acc=TreeMap<Long,JSONArray>();private val gyro=TreeMap<Long,JSONArray>();private val screen=java.util.TreeSet<Long>()
    private var session="";private var training="";private var lastRun=0L;private var generation=0
    private val seen=hashSetOf<String>();private var wallOffset=0L
    @Synchronized fun add(id:String,stream:String,rows:List<JSONObject>) {
        val live=PhoneLive.state.value
        if(!live.recording || live.session!=id || live.trainingId.isBlank())return
        if(id!=session) {session=id;training=live.trainingId;acc.clear();gyro.clear();screen.clear();seen.clear();generation++;lastRun=0}
        rows.forEach {r->
            val ns=r.optLong("t_elapsed_ns");if(ns<=0)return@forEach
            wallOffset=r.optLong("t_wall_ms")-ns/1_000_000
            if(stream=="pantalla" && r.optString("interactive") in setOf("1","true"))screen.add(ns)
            val keys=when(stream){"acelerometro"->listOf("x_m_s2","y_m_s2","z_m_s2");"giroscopio"->listOf("x_rad_s","y_rad_s","z_rad_s");else->return@forEach}
            val values=keys.map{r.optString(it).toDoubleOrNull()};if(values.any{it==null || !it.isFinite()})return@forEach
            (if(stream=="acelerometro")acc else gyro)[ns]=JSONArray(listOf(ns)+values)
        }
        // Captures are capped at ten minutes. Do not silently slide the origin.
        if(acc.size>16000 || gyro.size>16000){DetectorLive.state.value=JSONObject().put("state","LIMIT");return}
        val now=SystemClock.elapsedRealtime();if(now-lastRun<5000 || acc.size<50 || gyro.size<50 || !busy.compareAndSet(false,true))return
        lastRun=now
        val prefs=context.getSharedPreferences("prefs",0)
        val payload=JSONObject().put("acc",JSONArray(acc.values)).put("gyro",JSONArray(gyro.values)).put("screen_ns",JSONArray(screen))
            .put("wrist",prefs.getString("detector_wrist","L")).put("crown_hand",prefs.getBoolean("detector_crown_hand",true))
        val current=session;val capture=training;val epoch=generation;val offset=wallOffset
        executor.execute {
            try {
                if(!Python.isStarted())Python.start(AndroidPlatform(context.applicationContext))
                val result=JSONObject(Python.getInstance().getModule("visio_live").callAttr("evaluate",payload.toString()).toString())
                synchronized(this) {
                    if(epoch!=generation)return@synchronized
                    result.put("session",current).put("received_ms",System.currentTimeMillis());DetectorLive.state.value=result
                    val origin=result.optLong("origin_ns");val list=result.optJSONArray("gestures") ?: JSONArray()
                    val store=DetectionStore(Store.get(context))
                    for(i in 0 until list.length()) {
                        val g=list.getJSONObject(i);val s=g.getJSONObject("segment")
                        val onset=origin+(s.getDouble("t_on")*1e9).toLong();val end=origin+(s.getDouble("t_end")*1e9).toLong()
                        val key=UUID.nameUUIDFromBytes("$current:$onset:${s.optInt("chunk")}".toByteArray()).toString()
                        if(!seen.add(key))continue
                        val wall=end/1_000_000+offset
                        val evidence=JSONObject(result.toString()).apply {remove("gestures");put("gesture",g);put("start_elapsed_ns",onset);put("end_elapsed_ns",end);put("start_wall_ms",onset/1_000_000+offset);put("end_wall_ms",wall);put("training_id",capture);put("policy","experimental_review_sampling; released model alerts remain disabled")}
                        store.add(key,current,capture,wall,evidence)
                        // This is a requested annotation prompt, NOT a released detector alert.
                        val active=PhoneLive.state.value
                        if(g.optDouble("score")>=.5 && g.getJSONObject("features").optDouble("valid_frac",0.0)>=.8 && s.optInt("chunk")==0 && active.protocol>=5 && active.connected && active.recording && active.session==current &&
                            System.currentTimeMillis()-active.clockDelta-wall in -1000..15000 && prefs.getBoolean("detector_prompts",true) && store.prompt(key,System.currentTimeMillis())) {
                            prompt(JSONObject().put("type","detection_prompt").put("id",key).put("session",current).put("wall",wall)
                                .put("expires_ms",System.currentTimeMillis()-active.clockDelta+120000).put("score",g.getDouble("score")))
                        }
                    }
                }
            } catch(e:Exception) {DetectorLive.state.value=JSONObject().put("state","ERROR").put("message",e.message.orEmpty())}
            finally {busy.set(false)}
        }
    }
    fun close(){executor.shutdown()}
}
