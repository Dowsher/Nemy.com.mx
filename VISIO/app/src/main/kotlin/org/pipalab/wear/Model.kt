package org.pipalab.wear

import android.app.Activity
import android.content.Context
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class SessionConfig(val participant: String, val wrist: String, val dominant: String, val power: String, val consent: Boolean,
    val capture: org.pipalab.core.ActivityCapture? = null)
data class RecorderStatus(
    val active: Boolean = false, val stopping: Boolean = false, val seconds: Long = 0,
    val hz: Map<String, Double> = emptyMap(), val counts: Map<String, Long> = emptyMap(),
    val battery: Int = -1, val event: String? = null, val message: String = "Listo",
    val session: String = "", val health: String = "Sin comprobar", val captureId: String = "",
    val activity: String = "", val durationSeconds: Int = 0, val audio: AudioState = AudioState()
)
object Live { val state = MutableStateFlow(RecorderStatus()) }
val EVENT_NAMES = listOf("PIPA_SIM", "CIGARRO", "VAPE", "ENCENDEDOR_SOLO", "COMER", "BEBER", "TELEFONO", "CEPILLARSE", "OTRO", "REPOSO")

interface HealthSource {
    fun start()
    fun flush(done: () -> Unit)
    fun stop()
}

/** Una implementación tipada usa el AAR; la variante IMU declara explícitamente su ausencia. */
interface HealthSink {
    fun row(stream: String, sourceMs: Long, receivedNs: Long, receivedWall: Long, batch: Long, index: Int, size: Int, values: List<Any?>)
    fun info(key: String, value: Any)
    fun status(text: String)
    fun error(source: String, message: String)
}

object Schema {
    val columns = mapOf(
        "resumen_minuto" to "source_stream,minute_start_ms,minute_end_ms,samples,first_sample_ms,last_sample_ms,stats_json,quality_json",
        "ventanas" to "window_id,kind,start_wall_ms,end_wall_ms,available_from_ms,state",
        "acelerometro" to "x_m_s2,y_m_s2,z_m_s2,accuracy,received_elapsed_ns",
        "giroscopio" to "x_rad_s,y_rad_s,z_rad_s,accuracy,received_elapsed_ns",
        "offbody" to "worn,accuracy,received_elapsed_ns,source_elapsed_ns",
        "bateria" to "percent,charging,temperature_c,voltage_mv,charge_counter_uah",
        "pantalla" to "interactive",
        "reloj" to "actual_wall_ms,offset_ms",
        "marcadores" to "event,phase,event_id,note",
        "huecos" to "stream,previous_elapsed_ns,delta_ns,estimated_missing,kind",
        "errores" to "source,message",
        "heart_rate" to "source_timestamp_ms,received_elapsed_ns,received_wall_ms,clock_basis,batch_id,batch_index,batch_size,bpm,status,ibi_ms_json,ibi_status_json",
        "ppg" to "source_timestamp_ms,received_elapsed_ns,received_wall_ms,clock_basis,batch_id,batch_index,batch_size,green,green_status,ir,ir_status,red,red_status",
        "temperatura" to "source_timestamp_ms,received_elapsed_ns,received_wall_ms,clock_basis,batch_id,batch_index,batch_size,skin_c,ambient_c,status",
        "eda" to "source_timestamp_ms,received_elapsed_ns,received_wall_ms,clock_basis,batch_id,batch_index,batch_size,conductance_us,status"
    )
}

/** No inventa frecuencia cuando no hay dos muestras, ni cuenta timestamps duplicados como tiempo. */
class StreamStats {
    var count = 0L; private set
    var first = 0L; private set
    var last = 0L; private set
    var nonIncreasing = 0L; private set
    var estimatedMissing = 0L; private set
    fun add(t: Long, period: Long = 0L): Long {
        var lost = 0L
        if (count == 0L) first = t
        else if (t <= last) nonIncreasing++
        else if (period > 0 && t - last > period * 3 / 2) {
            lost = ((t - last + period / 2) / period - 1).coerceAtLeast(0)
            estimatedMissing += lost
        }
        last = maxOf(last, t); count++
        return lost
    }
    fun hz(): Double = if (count > 1 && last > first) (count - 1) * 1e9 / (last - first) else 0.0
    fun json() = JSONObject().put("count", count).put("first_elapsed_ns", first).put("last_elapsed_ns", last)
        .put("measured_hz", hz()).put("estimated_internal_missing", estimatedMissing).put("non_increasing", nonIncreasing)
}
