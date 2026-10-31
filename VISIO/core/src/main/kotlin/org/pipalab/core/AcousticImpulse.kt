package org.pipalab.core

import kotlin.math.abs
import kotlin.math.sqrt

data class AcousticImpulse(val frame: Long, val peak: Double, val rms: Double)

/** Demonstration trigger for short acoustic transients, NOT a finger-snap/lighter classifier. */
class AcousticImpulseDetector(private val rate: Int = 16000) {
    private val window = rate / 100
    private var inWindow = 0
    private var square = 0.0
    private var peak = 0.0
    private var frame = 0L
    private var background = .004
    private var onset = -1L
    private var maxPeak = 0.0
    private var maxRms = 0.0
    private var last = -rate * 3L
    fun add(samples: ShortArray, count: Int): List<AcousticImpulse> {
        require(count in 0..samples.size)
        val result = ArrayList<AcousticImpulse>()
        for(i in 0 until count) {
            val x = samples[i].toDouble() / 32768.0
            square += x*x; peak = maxOf(peak, abs(x)); frame++; inWindow++
            if(inWindow < window) continue
            val rms = sqrt(square / inWindow)
            val loud = rms > maxOf(.035, background * 7) && peak > .15
            if(frame <= rate) background = background * .9 + rms * .1
            else if(loud) {
                if(onset < 0) { onset = frame-window; maxPeak = peak; maxRms = rms }
                maxPeak = maxOf(maxPeak,peak); maxRms = maxOf(maxRms,rms)
            } else {
                if(onset >= 0) {
                    val duration = frame-window-onset
                    if(duration in window.toLong()..(rate * .12).toLong() && onset-last >= rate*2L) {
                        result.add(AcousticImpulse(onset,maxPeak,maxRms)); last=onset
                    }
                    onset=-1
                }
                background = background*.98 + rms.coerceAtMost(.04)*.02
            }
            square=0.0; peak=0.0; inWindow=0
        }
        return result
    }
}
