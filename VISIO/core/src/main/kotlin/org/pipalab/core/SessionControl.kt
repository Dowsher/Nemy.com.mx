package org.pipalab.core

import java.text.Normalizer
import java.util.Locale

/** Final utterances only. Descriptions containing the words must never operate the camera. */
object SessionVoice {
    enum class Command { START, STOP }
    fun command(text: String): Command? {
        val word = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).trim().trim('.', ',', '!', '¡', '?', '¿').trim()
        return when(word) { "accion" -> Command.START; "corte" -> Command.STOP; else -> null }
    }
}

/** NTP-style estimate between elapsedRealtimeNanos clocks. Offset = watch - phone. */
data class ClockProbe(val phoneSend: Long, val watchReceive: Long, val watchSend: Long, val phoneReceive: Long) {
    val roundTrip: Long get() = (phoneReceive-phoneSend)-(watchSend-watchReceive)
    val offset: Long get() = ((watchReceive-phoneSend)+(watchSend-phoneReceive))/2
    val valid: Boolean get() = phoneSend>0 && watchReceive>0 && watchSend>=watchReceive &&
        phoneReceive>=phoneSend && roundTrip in 0..5_000_000_000L
}

class SessionClock {
    private val probes = ArrayDeque<ClockProbe>()
    @Synchronized fun add(probe:ClockProbe):Boolean {
        if(!probe.valid) return false
        probes.removeAll { probe.phoneReceive-it.phoneReceive>30_000_000_000L ||
            kotlin.math.abs(probe.offset-it.offset)>2_000_000_000L }
        probes.addLast(probe)
        while(probes.size>12) probes.removeFirst()
        return true
    }
    @Synchronized fun best(now:Long):ClockProbe? = probes.filter { now-it.phoneReceive in 0..30_000_000_000L }.minByOrNull { it.roundTrip }
    @Synchronized fun clear() = probes.clear()
}

/** A new id is required for every recording; repeated recognition callbacks are harmless. */
class SessionGate {
    enum class Phase { READY, STARTING, RECORDING, SAVING, FINISHED }
    var phase = Phase.READY; private set
    fun start():Boolean { if(phase!=Phase.READY)return false; phase=Phase.STARTING; return true }
    fun acknowledged() { if(phase==Phase.STARTING)phase=Phase.RECORDING }
    fun stop():Boolean { if(phase !in setOf(Phase.STARTING,Phase.RECORDING))return false;phase=Phase.SAVING;return true }
    fun finish() {phase=Phase.FINISHED}
}
