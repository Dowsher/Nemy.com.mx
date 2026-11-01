package org.pipalab.phone

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.ClockProbe
import java.io.File

/** Sidecars are additive: old SQLite rows and original wearable archives are untouched. */
class SessionMedia(private val store:Store) {
    private val root=File(store.root.parentFile,"captures").apply { mkdirs() }
    fun dir(id:String):File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return File(root,id).apply { mkdirs();check(canonicalFile.parentFile==root.canonicalFile) }
    }
    fun video(id:String)=File(dir(id),"video.mp4")
    fun read(id:String):JSONObject=runCatching { AtomicFile(File(dir(id),"capture.json")).openRead().bufferedReader().use {JSONObject(it.readText())} }.getOrDefault(JSONObject())
    @Synchronized fun update(id:String,block:(JSONObject)->Unit):JSONObject {
        val json=read(id);block(json)
        val atomic=AtomicFile(File(dir(id),"capture.json"));val output=atomic.startWrite()
        try { output.write(json.toString(2).toByteArray());atomic.finishWrite(output) }
        catch(e:Exception) {atomic.failWrite(output);throw e}
        return json
    }
    fun create(id:String)=update(id) {
        check(!video(id).exists()) {"Esta sesión ya tiene video; crea una nueva."}
        if(it.length()>0) {check(it.optString("state")=="ready");return@update}
        it.put("schema","visio-multimodal-v1").put("id",id).put("state","ready")
            .put("camera","front").put("requested_quality","SD_480p").put("video_audio",false)
            .put("audio_source","watch_microphone").put("requested_streams",JSONArray(listOf("heart_rate","temperatura","giroscopio","acelerometro","audio")))
            .put("created_wall_ms",System.currentTimeMillis()).put("events",JSONArray())
            .put("alignment_method","watch=phone_elapsed+offset; video origin estimated from CameraX callbacks; not frame-accurate")
    }
    fun event(id:String,name:String,text:String="")=update(id) {
        val events=it.optJSONArray("events") ?: JSONArray()
        events.put(JSONObject().put("name",name).put("text",text).put("phone_elapsed_ns",SystemClock.elapsedRealtimeNanos()).put("wall_ms",System.currentTimeMillis()))
        it.put("events",events)
    }
    fun probe(id:String,p:ClockProbe)=update(id) {
        val a=it.optJSONArray("clock_probes") ?: JSONArray()
        a.put(JSONObject().put("phone_send_ns",p.phoneSend).put("watch_receive_ns",p.watchReceive).put("watch_send_ns",p.watchSend)
            .put("phone_receive_ns",p.phoneReceive).put("offset_ns",p.offset).put("round_trip_ns",p.roundTrip).put("network_uncertainty_ns",p.roundTrip/2))
        it.put("clock_probes",a)
        if(!it.has("watch_minus_phone_ns") || p.roundTrip<it.optLong("clock_round_trip_ns",Long.MAX_VALUE))
            it.put("watch_minus_phone_ns",p.offset).put("clock_round_trip_ns",p.roundTrip)
    }
    fun finalize(id:String,error:String,reason:String) {
        val file=video(id)
        val facts=JSONObject()
        val readable=runCatching {
            val r=MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                facts.put("width",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
                    .put("height",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
                    .put("rotation_degrees",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull())
                    .put("duration_ms",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                    .put("bit_rate",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull())
                    .put("capture_frame_rate",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull())
                check(facts.optLong("duration_ms")>0 && facts.optInt("width")>0)
            } finally {r.release()}
        }.isSuccess
        update(id) { it.put("state",if(error.isEmpty() && readable)"closed" else "interrupted").put("video_readable",readable)
            .put("error",error).put("stop_reason",reason).put("video",facts).put("bytes",file.length())
            .put("video_sha256",if(file.exists())store.sha(file) else "").put("finalized_wall_ms",System.currentTimeMillis()) }
    }
    fun recover():List<String> {
        val interrupted=mutableListOf<String>()
        root.listFiles()?.filter {it.isDirectory}?.forEach {dir->
            val j=read(dir.name)
            if(j.optString("state") in setOf("starting","recording","saving")) {
                finalize(dir.name,"La app terminó antes del cierre confirmado","process_restarted")
                interrupted.add(dir.name)
            }
        }
        return interrupted
    }
    fun files(id:String)=dir(id).listFiles()?.filter {it.isFile && it.name in setOf("capture.json","video.mp4")} ?: emptyList()
}
