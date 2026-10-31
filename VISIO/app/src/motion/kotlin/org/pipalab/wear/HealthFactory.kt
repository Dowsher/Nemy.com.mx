package org.pipalab.wear
import android.content.Context
import android.os.Handler
object HealthFactory {
    fun create(context: Context, handler: Handler, sink: HealthSink): HealthSource = object : HealthSource {
        override fun start() { sink.info("samsung_state", "not_bundled_motion_only"); sink.status("IMU: SDK Samsung no incluido") }
        override fun flush(done: () -> Unit) = done()
        override fun stop() {}
    }
}
