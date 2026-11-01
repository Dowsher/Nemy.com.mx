package org.pipalab.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.pipalab.core.ActivityCapture
import org.pipalab.core.PcmWave
import java.io.File
import android.util.AtomicFile

/** Persistent idempotency and cancellation ledger for authenticated phone commands. */
object TrainingInbox {
    val pending = MutableStateFlow<ActivityCapture?>(null)
    @Synchronized fun blocked(context:Context,id:String) = id in context.getSharedPreferences("training",0).getStringSet("cancelled_ids",emptySet()).orEmpty()
    @Synchronized fun block(context:Context,id:String) {
        val prefs=context.getSharedPreferences("training",0)
        check(prefs.edit().putStringSet("cancelled_ids",prefs.getStringSet("cancelled_ids",emptySet()).orEmpty()+id).commit())
        cancel(context,id)
    }
    @Synchronized fun load(context: Context) {
        pending.value = runCatching { ActivityCapture.parse(JSONObject(context.getSharedPreferences("training", 0).getString("pending", "")!!)) }.getOrNull()
    }
    @Synchronized fun prepare(context: Context, request: ActivityCapture): String {
        val prefs = context.getSharedPreferences("training", 0)
        if(blocked(context,request.id)) return "cancelled"
        if (request.id in prefs.getStringSet("used_ids", emptySet()).orEmpty()) return "already_started"
        if (Live.state.value.active) return "busy"
        val previous = pending.value
        if (previous != null && previous.id != request.id) return "pending_other"
        check(prefs.edit().putString("pending", request.json().toString()).commit())
        pending.value = request
        return "prepared"
    }
    @Synchronized fun cancel(context: Context, id: String): Boolean {
        if (pending.value?.id != id) return false
        context.getSharedPreferences("training", 0).edit().remove("pending").commit()
        pending.value = null
        return true
    }
    @Synchronized fun started(context: Context, id: String) {
        val prefs = context.getSharedPreferences("training", 0)
        val used = prefs.getStringSet("used_ids", emptySet()).orEmpty().toMutableSet().apply { add(id) }
        check(prefs.edit().putStringSet("used_ids", used).remove("pending").commit())
        pending.value = null
    }
    /** Only new training recordings are recovered. Older measurement archives are untouched. */
    fun recoverInterrupted(context: Context) {
        if (Live.state.value.active) return
        File(context.getExternalFilesDir(null), "sesiones").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            runCatching {
                val file = File(dir, "meta.json")
                if (!file.exists()) return@runCatching
                val meta = JSONObject(file.readText())
                if (meta.optJSONObject("training") == null || meta.optBoolean("finalized")) return@runCatching
                val audioFile = File(dir, "audio.wav")
                val frames = PcmWave.recover(audioFile)
                val audio = runCatching { JSONObject(File(dir, "audio.json").readText()) }.getOrDefault(JSONObject())
                    .put("state", if(meta.getJSONObject("training").optBoolean("audio_requested")) "interrupted" else "off").put("frames", frames).put("duration_seconds", frames / 16000.0)
                    .put("recovered_after_process_end", true)
                if (audioFile.exists()) writeJson(File(dir, "audio.json"), audio)
                val end = meta.optLong("last_checkpoint_elapsed_ns", meta.getLong("start_elapsed_ns"))
                meta.put("finalized", true).put("status", "interrupted").put("stop_reason", "process_restarted")
                    .put("flush_completed", false).put("end_elapsed_ns", end)
                    .put("end_wall_ms", meta.getLong("start_wall_ms") + (end - meta.getLong("start_elapsed_ns")) / 1_000_000)
                    .put("end_time_is_last_checkpoint", true).put("audio", audio)
                writeJson(file, meta)
            }.onFailure { android.util.Log.w("VisioTraining", "Recording recovery pending", it) }
        }
    }
    fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(value.toString(2).toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
