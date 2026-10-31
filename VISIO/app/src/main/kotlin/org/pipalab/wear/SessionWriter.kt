package org.pipalab.wear

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.pipalab.core.*

/** Acceso confinado al HandlerThread de captura. CSV UTF-8; escritura incremental, RAM acotada. */
class SessionWriter(context: Context, config: SessionConfig) {
    val compact = context.getSharedPreferences("setup",Context.MODE_PRIVATE).getBoolean("compact",true)
    val startNs = SystemClock.elapsedRealtimeNanos()
    val startWall = System.currentTimeMillis()
    val dir: File
    val stats = mutableMapOf<String, StreamStats>()
    private data class OpenCsv(val hour: Long, val stream: FileOutputStream, val writer: BufferedWriter, val fileName: String, var index: Long = 0)
    private val open = mutableMapOf<String, OpenCsv>()
    private var closed = false
    private val minutes=MinuteBook()
    private val liveIndices=mutableMapOf<String,Long>()
    private val storedCounts=mutableMapOf<String,Long>()
    private val fileIndices=mutableMapOf<String,Long>()
    private val measurementStreams=setOf("acelerometro","heart_rate","temperatura")
    private val detector=GestureDetector()
    private var worn=false
    private val pendingWindows=mutableMapOf<String,TrainingWindow>()
    private val training=TrainingBuffer(startWall,{r->persist(r.stream,r.elapsed,r.values,r.wall)}, {w->
        pendingWindows[w.id]=w
        persist("ventanas",SystemClock.elapsedRealtimeNanos(),listOf(w.id,w.kind,w.start,w.end,w.availableFrom,"open"),System.currentTimeMillis())
    })
    val meta = JSONObject().put("schema_version", 2).put("status", "recording").put("finalized", false)
        .put("participant", config.participant).put("wrist", config.wrist).put("dominant_hand", config.dominant)
        .put("consent", config.consent).put("power_mode", config.power)
        .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
        .put("android_release", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
        .put("build_display", Build.DISPLAY).put("app_version", BuildConfig.VERSION_NAME)
        .put("samsung_enabled", BuildConfig.SAMSUNG_ENABLED).put("start_elapsed_ns", startNs).put("start_wall_ms", startWall)
        .put("sampling_period_us", 40000).put("sensor_profile","essential_v2").put("detector_version",GestureDetector.VERSION).put("timestamp_wall_policy", "IMU: wall at session anchor + elapsed delta; reloj.csv tracks wall changes")
        .put("samsung_timestamp_policy", "source retained; epoch/elapsed inferred at reception; unknown uses receipt, never a guaranteed sample clock")
        .put("offbody_timestamp_policy", "cached pre-session state applies at receipt; source_elapsed_ns retains sensor timestamp; unknown until first event")
        .put("network_permission", false).put("sync_policy", "manual marker then 3 claps; align IMU peaks offline; no microphone")
    init {
        require(config.consent && config.participant.matches(Regex("[A-Za-z0-9_-]{1,24}")))
        val root = File(requireNotNull(context.getExternalFilesDir(null)) { "Almacenamiento no disponible" }, "sesiones")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(startWall))
        dir = File(root, "${config.participant}_${stamp}_${UUID.randomUUID().toString().take(8)}")
        check(dir.mkdirs()) { "No se pudo crear sesión" }
        meta.put("session", dir.name)
        meta.put("schema_version",if(compact)3 else 2).put("storage_policy",if(compact)"compact_v2" else "full_raw")
            .put("retention",JSONObject().put("summary_seconds",60).put("continuous_slow_streams",JSONArray(listOf("heart_rate","temperatura"))).put("memory_buffer_seconds",210).put("control_seconds",10)
                .put("control_period_seconds",300).put("event_context_seconds",30).put("manual_seconds",30))
        meta.put("permissions_granted", JSONObject().also { grants ->
            (Permissions.foreground() + listOfNotNull(Permissions.background())).forEach { permission ->
                grants.put(permission, context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        })
        meta.put("health_packages", JSONArray().also { packages ->
            listOf("com.samsung.android.service.health", "com.samsung.android.sensor.manager").forEach { name ->
                runCatching { context.packageManager.getPackageInfo(name, 0) }.getOrNull()?.let {
                    packages.put(JSONObject().put("package", name).put("version_name", it.versionName).put("version_code", it.longVersionCode))
                }
            }
        })
        saveMeta()
    }
    fun wall(t: Long) = startWall + (t - startNs) / 1_000_000
    private fun escape(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
    }
    fun write(name: String, t: Long, values: List<Any?>, wallMs: Long = wall(t), period: Long = 0) {
        check(!closed)
        val stat = stats.getOrPut(name) { StreamStats() }
        val previous = stat.last
        val missing = stat.add(t, period)
        val fields=Schema.columns.getValue(name).split(',').mapIndexed{i,k->k to (values.getOrNull(i)?.toString() ?: "")}.toMap()
        if(compact) minutes.add(name,wallMs,fields)
        if(name=="offbody") {worn=fields["worn"]=="1";if(!worn)detector.reset()}
        if(compact && name in measurementStreams) {
            val index=(liveIndices[name] ?: 0)+1;liveIndices[name]=index
            PhoneBridge.offer(dir.name,"live_"+name,name,index,listOf(t,wallMs)+values,false)
            if(name=="acelerometro") training.add(TrainingRow(name,index,t,wallMs,values.toList()))
            else persist(name,t,values,wallMs)

            if(worn && name=="acelerometro") detector.accel(t,fields.getValue("x_m_s2").toDouble(),fields.getValue("y_m_s2").toDouble(),fields.getValue("z_m_s2").toDouble())?.let{c->
                training.preserve(UUID.randomUUID().toString(),"patron_sin_confirmar",wall(c.startNs)-30000,wall(c.endNs)+30000)
            }
        } else persist(name,t,values,wallMs)
        if (missing > 0) write("huecos", t, listOf(name, previous, t - previous, missing, "internal_estimate"))
    }
    private fun persist(name:String,t:Long,values:List<Any?>,wallMs:Long) {
        val fixed = name in setOf("marcadores", "huecos", "errores", "reloj", "pantalla", "bateria", "offbody","resumen_minuto","ventanas")
        val hour = if (fixed) 0 else ((t - startNs).coerceAtLeast(0) / 3_600_000_000_000L)
        var csv = open[name]
        if (csv == null || csv.hour != hour) {
            csv?.let { it.writer.flush(); it.stream.fd.sync(); it.writer.close() }
            val file = File(dir, if (fixed) "$name.csv" else "%s_%03d.csv".format(name, hour))
            val empty = !file.exists() || file.length() == 0L
            val out = FileOutputStream(file, true)
            csv = OpenCsv(hour, out, BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 65536), file.name,fileIndices[file.name] ?: 0L)
            open[name] = csv
            if (empty) { csv.writer.write("t_elapsed_ns,t_wall_ms,${Schema.columns.getValue(name)}"); csv.writer.newLine() }
        }
        csv.writer.write((listOf(t, wallMs) + values).joinToString(",") { escape(it) }); csv.writer.newLine()
        csv.index++
        fileIndices[csv.fileName]=csv.index
        storedCounts[name]=(storedCounts[name] ?: 0)+1
        PhoneBridge.offer(dir.name, csv.fileName, name, csv.index, listOf(t, wallMs) + values)
    }
    fun retain(id:String,kind:String,from:Long,to:Long):TrainingWindow=training.preserve(id,kind,from,to)
    private fun summaries(all:Boolean=false) {
        if(!compact)return
        val now=System.currentTimeMillis()
        minutes.ready(now,all).forEach{s->
            val channels=JSONObject();s.channels.forEach{(k,v)->channels.put(k,JSONObject().put("n",v.count).put("mean",v.mean).put("min",v.min).put("max",v.max).put("sd",v.sd))}
            persist("resumen_minuto",SystemClock.elapsedRealtimeNanos(),listOf(s.stream,s.start,s.start+60000,s.rows,s.first,s.last,channels.toString(),JSONObject(s.quality).toString()),s.start)
        }
        pendingWindows.values.filter{now>=it.end || all}.toList().forEach{w->
            persist("ventanas",SystemClock.elapsedRealtimeNanos(),listOf(w.id,w.kind,w.start,w.end,w.availableFrom,if(now>=w.end)"closed" else "interrupted"),now)
            pendingWindows.remove(w.id)
        }
    }
    fun error(source: String, message: String) = write("errores", SystemClock.elapsedRealtimeNanos(), listOf(source, message))
    fun put(key: String, value: Any) { meta.put(key, value); saveMeta() }
    fun saveMeta() {
        meta.put("last_checkpoint_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        meta.put("streams", JSONObject().also { o -> stats.forEach { (k, v) -> o.put(k, v.json()) } })
        meta.put("stored_rows",JSONObject(storedCounts))
        val atomic = AtomicFile(File(dir, "meta.json"))
        val output = atomic.startWrite()
        try { output.write(meta.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun checkpoint(durable: Boolean = false) {
        summaries()
        open.values.forEach { it.writer.flush(); if (durable) it.stream.fd.sync() }
        if (durable) saveMeta()
    }
    fun finish(endNs: Long, reason: String, flushComplete: Boolean) {
        if (closed) return
        for (name in listOf("acelerometro")) {
            val stat = stats[name]
            val first = stat?.first?.takeIf { it > 0 } ?: endNs
            val last = stat?.last?.takeIf { it > 0 } ?: startNs
            if (first - startNs > 60_000_000) write("huecos", first, listOf(name, startNs, first - startNs, (first - startNs) / 40_000_000, "leading_estimate"))
            if (stat != null && endNs - last > 60_000_000) write("huecos", endNs, listOf(name, last, endNs - last, (endNs - last) / 40_000_000, "trailing_estimate"))
        }
        meta.put("end_elapsed_ns", endNs).put("end_wall_ms", wall(endNs)).put("stop_reason", reason)
            .put("flush_completed", flushComplete).put("finalized", true).put("status", if (reason == "user") "completed" else "interrupted")
        summaries(true);checkpoint(true)
        open.values.forEach { it.writer.close() }; open.clear(); closed = true
    }
}
