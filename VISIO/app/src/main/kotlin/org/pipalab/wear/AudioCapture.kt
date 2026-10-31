package org.pipalab.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import org.json.JSONObject
import org.pipalab.core.Csv
import org.pipalab.core.PcmWave
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioState(val state: String = "off", val seconds: Double = 0.0, val rms: Double = 0.0,
    val peak: Double = 0.0, val clipped: Long = 0, val message: String = "", val silenced: Boolean = false)

/** Session-bounded local PCM, with independent acquisition-clock anchors. No sound classifier. */
class AudioCapture(private val context: Context, private val dir: File, private val anchorNs: Long, private val anchorWall: Long,
    private val onImpulse: (Long, Double, Double) -> Unit = {_,_,_->}) {
    @Volatile var state = AudioState(); private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private val metadata = JSONObject().put("requested", true).put("file", "audio.wav")
        .put("timeline_file", "audio_timeline.csv").put("encoding", "PCM_S16LE").put("channels", 1)
        .put("sample_rate_hz", 16000).put("state", "starting").put("classifier", "none")
    private fun save() = TrainingInbox.writeJson(File(dir, "audio.json"), metadata)
    fun start() {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Falta permiso de micrófono" }
        val manager = context.getSystemService(AudioManager::class.java)
        val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        metadata.put("source", if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION").put("unprocessed_supported", unprocessed)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Formato de audio no disponible" }
        val input = AudioRecord.Builder().setAudioSource(source).setPrivacySensitive(true)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, 16000)).build()
        recorder = input
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Micrófono no disponible" }
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { input.setPreferredDevice(it) }
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "El micrófono no inició" }
        val start = SystemClock.elapsedRealtimeNanos()
        metadata.put("start_requested_elapsed_ns", start).put("state", "recording")
            .put("clock", "AudioTimestamp.TIMEBASE_BOOTTIME; fallback reception estimate is explicitly tagged")
            .put("routed_device_type", input.routedDevice?.type ?: -1)
        save(); running = true; state = AudioState("recording")
        thread = Thread({ capture(input) }, "VisioAudio").also { it.start() }
    }
    private fun capture(input: AudioRecord) {
        var frames = 0L; var clippedTotal = 0L; var anchors = 0L; var lastAnchor = 0L
        var failure: String? = null
        try {
            PcmWave(File(dir, "audio.wav")).use { wave ->
                File(dir, "audio_timeline.csv").bufferedWriter().use { timeline ->
                    timeline.appendLine("t_elapsed_ns,t_wall_ms,frame_offset,frames,read_elapsed_ns,hardware_frame_position,hardware_timestamp_ns,timestamp_status,clock_basis,rms,peak,clipped,system_silenced")
                    val samples = ShortArray(1600)
                    val impulses = org.pipalab.core.AcousticImpulseDetector()
                    while (running) {
                        val count = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "AudioRecord.read=$count" }
                        if (count == 0) { Thread.sleep(10); continue }
                        val received = SystemClock.elapsedRealtimeNanos()
                        val stamp = AudioTimestamp()
                        val status = input.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                        val hardware = status == AudioRecord.SUCCESS && stamp.nanoTime > 0
                        val elapsed = if (hardware) stamp.nanoTime + (frames - stamp.framePosition) * 1_000_000_000L / 16000
                            else received - count * 1_000_000_000L / 16000
                        if(frames==0L) metadata.put("first_sample_elapsed_ns",elapsed).put("first_sample_wall_ms",anchorWall+(elapsed-anchorNs)/1_000_000)
                        var squares = 0.0; var peak = 0; var clipped = 0
                        for (i in 0 until count) { val v = samples[i].toInt(); squares += v.toDouble() * v; peak = maxOf(peak, abs(v)); if (abs(v) >= 32760) clipped++ }
                        val rms = sqrt(squares / count) / 32768.0
                        val silenced = input.activeRecordingConfiguration?.isClientSilenced ?: false
                        impulses.add(samples,count).forEach { impulse ->
                            if(!silenced) onImpulse(elapsed+(impulse.frame-frames)*1_000_000_000L/16000,impulse.peak,impulse.rms)
                        }
                        wave.write(samples, count); clippedTotal += clipped
                        // Keep a sample-clock anchor each second; the PCM file retains every audio frame.
                        if (lastAnchor == 0L || received - lastAnchor >= 1_000_000_000L) {
                            timeline.appendLine(Csv.line(listOf(elapsed, anchorWall + (elapsed - anchorNs) / 1_000_000,
                                frames, count, received, if(hardware) stamp.framePosition else "", if(hardware) stamp.nanoTime else "",
                                status, if(hardware) "hardware_boottime" else "reception_estimate", rms, peak / 32768.0, clipped, silenced)))
                            timeline.flush(); lastAnchor = received; if (hardware) anchors++
                        }
                        frames += count
                        state = AudioState("recording", frames / 16000.0, rms, peak / 32768.0, clippedTotal, silenced = silenced)
                    }
                }
            }
        } catch (e: Exception) { failure = e.message ?: e.javaClass.simpleName }
        finally {
            runCatching { input.stop() }; input.release(); recorder = null
            val finalState = if (failure != null) "failed" else if (frames == 0L) "empty" else "closed"
            state = state.copy(state = finalState, seconds = frames / 16000.0, message = failure.orEmpty())
            metadata.put("state", finalState).put("frames", frames).put("duration_seconds", frames / 16000.0)
                .put("clipped_samples", clippedTotal).put("hardware_clock_anchors", anchors)
                .put("stop_elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("error", failure ?: JSONObject.NULL)
            save()
        }
    }
    fun stop(): JSONObject {
        running = false
        thread?.join(3000)
        check(thread?.isAlive != true) { "Audio pendiente de cerrar" }
        if (thread == null) { runCatching { recorder?.release() }; recorder = null }
        return JSONObject(metadata.toString())
    }
    fun failed(error: Exception): JSONObject {
        running = false; runCatching { recorder?.release() }; recorder = null
        state = AudioState("failed", message = error.message.orEmpty())
        metadata.put("state", "failed").put("error", error.message); save()
        return JSONObject(metadata.toString())
    }
}
