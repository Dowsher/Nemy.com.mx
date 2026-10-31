package org.pipalab.core

import kotlin.math.*

/** Accelerometer-only, time-based raise/hold/return detector. Scores are not probabilities. */
class GestureDetector {
    companion object { const val VERSION="motion-personal-v2" }
    data class Candidate(val startNs:Long,val endNs:Long,val gestures:Int,val maxAngle:Double,
        val peakSpeed:Double,val holdSeconds:Double,val consistency:Double,val fingerprint:List<Double>,
        val positiveSimilarity:Double,val negativeSimilarity:Double,val method:String="consistent_gestures",val evidenceWindows:Int=0)
    private data class Gesture(val start:Long,val end:Long,val angle:Double,val speed:Double,val hold:Double,val direction:DoubleArray)
    private var gravity=doubleArrayOf(0.0,0.0,0.0)
    private var baseline=gravity.copyOf()
    private var lastSample=0L
    private var quietSince=0L
    private var ready=false
    private var raised=0L
    private var stableSince=0L
    private var longestHold=0.0
    private var maximumAngle=0.0
    private var peakSpeed=0.0
    private var peakDynamic=0.0
    private var holdDirection=gravity.copyOf()
    private var lastAlert=Long.MIN_VALUE/2
    private var potentialUntil=0L
    private var lastTemplateCheck=0L
    private var firstTemplateMatch=0L
    private val gestures=ArrayDeque<Gesture>()
    private val samples=ArrayDeque<MotionPoint>()
    var profile=MotionProfile(); private set
    fun setProfile(value:MotionProfile) { profile=value }
    fun calibrated()=ready
    /** A transport/contact reset clears evidence, but does not erase the alert cooldown. */
    fun reset() { lastSample=0;quietSince=0;ready=false;raised=0;stableSince=0;longestHold=0.0;gestures.clear();samples.clear();potentialUntil=0;lastTemplateCheck=0;firstTemplateMatch=0 }
    fun stage(now:Long)=when {
        !ready || lastSample==0L || now<lastSample || now-lastSample>5_000_000_000L -> -1
        now<potentialUntil -> 2
        raised>0 || firstTemplateMatch>0 && now-firstTemplateMatch<90_000_000_000L || gestures.any{now-it.end<180_000_000_000L} -> 1
        else -> 0
    }
    fun accel(t:Long,x:Double,y:Double,z:Double):Candidate? {
        if(!x.isFinite() || !y.isFinite() || !z.isFinite()) {reset();return null}
        if(t<=lastSample)return null
        val raw=doubleArrayOf(x,y,z);val magnitude=norm(raw)
        if(magnitude !in 2.0..25.0) {reset();return null}
        if(lastSample>0 && t-lastSample>350_000_000L)reset()
        if(lastSample==0L){gravity=raw;baseline=raw.copyOf();lastSample=t;quietSince=t;samples.add(MotionPoint(t,x,y,z));return null}
        val dt=(t-lastSample)/1e9;lastSample=t
        val previous=gravity.copyOf();val alpha=1-exp(-dt/.30)
        for(i in 0..2)gravity[i]+=alpha*(raw[i]-gravity[i])
        val speed=angle(previous,gravity)/dt
        val dynamic=norm(DoubleArray(3){raw[it]-gravity[it]})
        samples.addLast(MotionPoint(t,x,y,z));while(samples.isNotEmpty() && t-samples.first().ns>12_000_000_000L)samples.removeFirst()
        val quiet=speed<9 && dynamic<.65 && magnitude in 8.0..11.7
        if(!ready) {
            if(!quiet)quietSince=t
            if(quiet){baseline=gravity.copyOf();if(t-quietSince>=4_000_000_000L)ready=true}
            return null
        }
        personalized(t)?.let{return it}
        val tilt=angle(gravity,baseline)
        while(gestures.isNotEmpty() && t-gestures.first().end>180_000_000_000L)gestures.removeFirst()
        if(raised==0L) {
            if(tilt>40 && speed>12 && dynamic<5) {
                raised=t;maximumAngle=tilt;peakSpeed=speed;peakDynamic=dynamic;stableSince=0;longestHold=0.0;holdDirection=gravity.copyOf();quietSince=t
            } else {
                // Follow a new sustained resting posture without treating a slow posture change as a gesture.
                if(!quiet)quietSince=t
                if(quiet && t-quietSince>20_000_000_000L){baseline=gravity.copyOf();gestures.clear()}
            }
            return null
        }
        maximumAngle=max(maximumAngle,tilt);peakSpeed=max(peakSpeed,speed);peakDynamic=max(peakDynamic,dynamic)
        if(tilt>32 && speed<18 && dynamic<1.2) {
            if(stableSince==0L)stableSince=t
            val held=(t-stableSince)/1e9
            if(held>longestHold){longestHold=held;holdDirection=gravity.copyOf()}
        } else stableSince=0
        if(tilt>=24 && t-raised<=30_000_000_000L)return null
        val from=raised;raised=0;quietSince=t
        if(tilt>=24 || t-from !in 2_000_000_000L..25_000_000_000L || longestHold<1.2 || peakDynamic>6)return null
        val g=Gesture(from,t,maximumAngle,peakSpeed,longestHold,holdDirection.copyOf())
        if(gestures.lastOrNull()?.let{t-it.end<2_000_000_000L}==true)return null
        if(gestures.lastOrNull()?.let{angle(it.direction,g.direction)>35 || abs(it.angle-g.angle)>35}==true)gestures.clear()
        gestures.addLast(g)
        val fingerprint=MotionFingerprint.extract(samples.toList()) ?: return null
        val similarity=profile.similarity(fingerprint)
        // With sparse labels, a rejected example requires an extra consistent repetition;
        // it never establishes an absolute veto or creates an alert by itself.
        val nearRejected=profile.negativeGroups>0 && similarity.second>.58 && similarity.second>similarity.first+.08
        val required=if(nearRejected)3 else 2
        if(gestures.size<required || t-lastAlert<300_000_000_000L)return null
        val recent=gestures.toList();val consistency=1-angle(recent.first().direction,g.direction)/180
        val result=Candidate(recent.first().start,t,recent.size,recent.maxOf{it.angle},recent.maxOf{it.speed},recent.map{it.hold}.average(),consistency,
            fingerprint,similarity.first,similarity.second)
        lastAlert=t;potentialUntil=t+30_000_000_000L;gestures.clear();return result
    }
    private fun personalized(t:Long):Candidate? {
        if(profile.positiveGroups<4 || profile.negativeGroups<1 || t-lastAlert<300_000_000_000L || t-lastTemplateCheck<1_000_000_000L)return null
        lastTemplateCheck=t
        if(firstTemplateMatch>0 && t-firstTemplateMatch>90_000_000_000L)firstTemplateMatch=0
        val f=MotionFingerprint.extract(samples.toList()) ?: return null
        if(f[5]<25 || f[4]<3 || f[6] !in .15.. .95)return null
        val (positive,negative)=profile.similarity(f)
        if(negative>=positive){firstTemplateMatch=0;return null}
        if(positive<.95 || positive-negative<.12)return null
        if(firstTemplateMatch==0L){firstTemplateMatch=t;return null}
        // Two non-overlapping high-match windows. Many overlapping windows are not independent votes.
        if(t-firstTemplateMatch<8_000_000_000L)return null
        val from=firstTemplateMatch-8_000_000_000L
        lastAlert=t;potentialUntil=t+30_000_000_000L;firstTemplateMatch=0;gestures.clear();raised=0
        return Candidate(from,t,0,f[5],0.0,0.0,0.0,f,positive,negative,"personal_motion_windows",2)
    }
}

internal fun norm(v:DoubleArray)=sqrt(v.sumOf{it*it})
internal fun angle(a:DoubleArray,b:DoubleArray):Double {
    val denominator=norm(a)*norm(b)
    return if(denominator<1e-6)180.0 else acos((a.indices.sumOf{a[it]*b[it]}/denominator).coerceIn(-1.0,1.0))*180/PI
}
