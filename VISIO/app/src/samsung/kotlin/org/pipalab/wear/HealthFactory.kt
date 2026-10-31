package org.pipalab.wear

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

object HealthFactory {
    fun create(context: Context, handler: Handler, sink: HealthSink): HealthSource = SamsungSource(context, handler, sink)
}

/** Solo API pública de Samsung 1.4.1. Sin reflexión ni tipos supuestos según modelo. */
private class SamsungSource(context: Context, private val handler: Handler, private val sink: HealthSink) : HealthSource {
    private val trackers = mutableMapOf<HealthTrackerType, HealthTracker>()
    private var closed = false
    private var flushCallback: (() -> Unit)? = null
    private val flushing = mutableSetOf<HealthTrackerType>()
    private val sequence = AtomicLong(0)
    private val wanted = listOf(HealthTrackerType.HEART_RATE_CONTINUOUS, HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
    private val service = HealthTrackingService(object : ConnectionListener {
        override fun onConnectionSuccess() { handler.post { if (!closed) configure() } }
        override fun onConnectionEnded() { handler.post { if (!closed) { sink.status("Samsung: desconectado"); sink.error("samsung", "connection_ended") } } }
        override fun onConnectionFailed(exception: HealthTrackerException) { handler.post {
            if (!closed) {
                sink.error("samsung_connection", "code=${exception.errorCode}; ${exception.message}")
                sink.status("Samsung no disponible (${exception.errorCode})")
                sink.info("samsung_connection_error", exception.errorCode)
                // hasResolution()/resolve() exigen INTERNET en la referencia oficial.
                // La registradora offline no los llama; la instalación/actualización se hace fuera de la sesión.
            }
        } }
    }, context)
    override fun start() {
        sink.status("Conectando Samsung…")
        try { service.connectService() } catch (e: Exception) { sink.error("samsung_connect", e.toString()); sink.status("Samsung: error de conexión") }
    }
    private fun configure() {
        try {
            val capability = service.trackingCapability
            val supported = capability.supportHealthTrackerTypes
            sink.info("samsung_sdk_reported_version", capability.version)
            sink.info("samsung_supported_trackers", JSONArray(supported.map { it.name }))
            sink.info("samsung_unsupported_requested", JSONArray(wanted.filter { it !in supported }.map { it.name }))
            wanted.filter { it in supported }.forEach { type ->
                try {
                    val tracker = service.getHealthTracker(type)
                    trackers[type] = tracker
                    tracker.setEventListener(listener(type))
                } catch (e: Exception) { sink.error(type.name, e.toString()) }
            }
            sink.info("samsung_started_trackers", JSONArray(trackers.keys.map { it.name }))
            sink.status("Samsung: ${trackers.size}/${wanted.size} flujos")
        } catch (e: Exception) { sink.error("samsung_capability", e.toString()); sink.status("Samsung: fallo de capacidad") }
    }
    private fun <T> value(d: DataPoint, key: ValueKey<T>): T? = try { d.getValue(key) } catch (_: IllegalArgumentException) { null }
    private fun values(type: HealthTrackerType, d: DataPoint): Pair<String, List<Any?>> = when (type) {
        HealthTrackerType.HEART_RATE_CONTINUOUS -> "heart_rate" to listOf(value(d, ValueKey.HeartRateSet.HEART_RATE), value(d, ValueKey.HeartRateSet.HEART_RATE_STATUS),
            value(d, ValueKey.HeartRateSet.IBI_LIST)?.let { JSONArray(it).toString() }, value(d, ValueKey.HeartRateSet.IBI_STATUS_LIST)?.let { JSONArray(it).toString() })
        HealthTrackerType.PPG_CONTINUOUS -> "ppg" to listOf(value(d, ValueKey.PpgSet.PPG_GREEN), value(d, ValueKey.PpgSet.GREEN_STATUS),
            value(d, ValueKey.PpgSet.PPG_IR), value(d, ValueKey.PpgSet.IR_STATUS), value(d, ValueKey.PpgSet.PPG_RED), value(d, ValueKey.PpgSet.RED_STATUS))
        HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS -> "temperatura" to listOf(value(d, ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
            value(d, ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE), value(d, ValueKey.SkinTemperatureSet.STATUS))
        HealthTrackerType.EDA_CONTINUOUS -> "eda" to listOf(value(d, ValueKey.EdaSet.SKIN_CONDUCTANCE), value(d, ValueKey.EdaSet.STATUS))
        else -> error("Tracker no solicitado")
    }
    private fun listener(type: HealthTrackerType) = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: List<DataPoint>) {
            val receivedNs = SystemClock.elapsedRealtimeNanos(); val receivedWall = System.currentTimeMillis()
            val id = sequence.incrementAndGet()
            // Copiar antes de salir del callback: el SDK puede reutilizar sus estructuras.
            try {
                val copy = data.map { it.timestamp to values(type, it) }
                handler.post {
                    if (!closed) copy.forEachIndexed { index, (time, payload) ->
                        sink.row(payload.first, time, receivedNs, receivedWall, id, index, copy.size, payload.second)
                    }
                }
            } catch (e: Exception) { handler.post { if (!closed) sink.error(type.name, "decode: $e") } }
        }
        override fun onError(error: HealthTracker.TrackerError) { handler.post {
            if (!closed) { sink.error(type.name, error.toString()); sink.status("Samsung: $error") }
        } }
        override fun onFlushCompleted() { handler.post {
            flushing.remove(type)
            if (flushing.isEmpty()) { val done = flushCallback; flushCallback = null; done?.invoke() }
        } }
    }
    override fun flush(done: () -> Unit) {
        if (trackers.isEmpty()) { done(); return }
        flushCallback = done; flushing.addAll(trackers.keys)
        trackers.forEach { (type, tracker) ->
            try { tracker.flush() } catch (e: Exception) { sink.error(type.name, "flush: $e") }
        }
        // El servicio aplica un límite de 15 s y deja flush_completed=false si no hay confirmación.
    }
    override fun stop() {
        closed = true
        trackers.forEach { (_, tracker) -> runCatching { tracker.unsetEventListener() } }
        trackers.clear(); flushCallback = null; flushing.clear()
        runCatching { service.disconnectService() }
    }
}
