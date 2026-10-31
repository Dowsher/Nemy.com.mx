package org.pipalab.core
import kotlin.math.*

/** Reglas exploratorias de movimiento. No clasifica sustancias ni diagnostica consumo. */
class GestureDetector {
    data class Candidate(val startNs: Long, val endNs: Long, val gestures: Int, val maxAngle: Double, val peakGyro: Double)
    private var baseline = doubleArrayOf(0.0, 0.0, 0.0)
    private var gravity = doubleArrayOf(0.0, 0.0, 0.0)
    private var warmupStart = 0L
    private var ready = false
    private var gyro = 0.0
    private var lastGyroNs = 0L
    private var raised = 0L
    private var stableStart = 0L
    private var held = false
    private var peak = 0.0
    private var angleMax = 0.0
    private var lastAlert = Long.MIN_VALUE / 2
    private var lastSample = 0L
    private var potentialUntil = 0L
    private val gestures = ArrayDeque<Pair<Long, Long>>()
    fun reset() { warmupStart = 0; ready = false; raised = 0; stableStart = 0; held = false; gestures.clear(); lastSample = 0; potentialUntil=0 }
    fun gyro(t: Long, x: Double, y: Double, z: Double) { gyro = sqrt(x*x+y*y+z*z); lastGyroNs = t }
    fun calibrated() = ready
    /** -1 unavailable; 0 no current pattern; 1 one gesture/in progress; 2 recent two-gesture candidate. */
    fun stage(now:Long):Int = when {
        !ready || lastSample==0L || now-lastSample>10_000_000_000L -> -1
        now<potentialUntil -> 2
        raised>0 || gestures.any{now-it.second<180_000_000_000L} -> 1
        else -> 0
    }
    fun accel(t: Long, x: Double, y: Double, z: Double): Candidate? {
        if (t <= lastSample) return null
        if (lastSample > 0 && t-lastSample > 5_000_000_000L) reset()
        lastSample = t
        if (warmupStart == 0L) { warmupStart = t; gravity = doubleArrayOf(x,y,z); baseline = gravity.copyOf() }
        doubleArrayOf(x,y,z).forEachIndexed { i, v -> gravity[i] = 0.92*gravity[i] + 0.08*v }
        if (!ready) {
            if (gyro < 0.3) baseline = gravity.copyOf() else warmupStart = t
            if (t-warmupStart >= 5_000_000_000L) ready = true
            return null
        }
        if (t-lastGyroNs > 2_000_000_000L) return null
        val gn = sqrt(gravity.sumOf { it*it }); val bn = sqrt(baseline.sumOf { it*it })
        if (gn < 1 || bn < 1) return null
        val angle = acos((gravity.indices.sumOf { gravity[it]*baseline[it] } / (gn*bn)).coerceIn(-1.0,1.0))*180/PI
        if (raised == 0L) {
            if (angle > 45 && gyro > 0.35) { raised = t; peak = gyro; angleMax = angle; held = false; stableStart = 0 }
        } else {
            peak = max(peak, gyro); angleMax = max(angleMax, angle)
            if (angle > 35 && gyro < 0.45) {
                if (stableStart == 0L) stableStart = t
                if (t-stableStart >= 1_500_000_000L) held = true
            } else stableStart = 0
            if (angle < 22 || t-raised > 30_000_000_000L) {
                val from = raised; raised = 0
                if (held && angle < 22 && t-from in 2_000_000_000L..25_000_000_000L) {
                    gestures.addLast(from to t)
                    while (gestures.isNotEmpty() && t-gestures.first().second > 180_000_000_000L) gestures.removeFirst()
                    if (gestures.size >= 2 && t-lastAlert > 300_000_000_000L) {
                        lastAlert = t
                        potentialUntil=t+30_000_000_000L
                        val candidate = Candidate(gestures.first().first, t, gestures.size, angleMax, peak)
                        gestures.clear()
                        return candidate
                    }
                }
            }
        }
        return null
    }
}
