package org.pipalab.core

import kotlin.math.*

/** One raised-hand hold: the forearm rises, stays steady and leaves. Degrees and m/s²; X axis toward the hand. */
data class MotionEpisode(val startNs:Long,val endNs:Long,val stableSeconds:Double,val direction:List<Double>,val elevation:Double,
    val maxElevation:Double,val rollSpread:Double,val micro:Double,val rise:Double,val entrySpeed:Double,val holds:Int) {
    val seconds get()=(endNs-startNs)/1e9
    val roll get()=atan2(-direction[1],direction[2])*180/PI
    fun features()=direction+listOf(ln(max(stableSeconds,.5)),micro,if(rise.isFinite())rise else 0.0,rollSpread)
    /** Hand held at the mouth: high and steady for seconds, entered from a lower posture, left within a minute. */
    fun mouth()=stableSeconds>=4 && elevation>=30 && rise>=20 && seconds<=60 && micro<=1.5
    /** Smallest shape a personal example may extend the rule to; resting postures never qualify. */
    fun deliberate()=stableSeconds>=3 && elevation>=15 && rise>=10 && seconds<=60 && micro<=1.5
}

/** Streams accelerometer samples into raised-hand episodes. Constants use real time, so 25 and 50 Hz agree. */
class EpisodeTracker {
    private class Open(val start:Long,val pre:Double,val entrySpeed:Double,var maxElevation:Double) {
        var lastHigh=start;var stable=0.0;var run=0.0;var holds=0;var n=0;var mean=0.0
        val sum=DoubleArray(3);var rollCos=0.0;var rollSin=0.0;var block2=0.0;var blockTime=0.0;val blocks=ArrayList<Double>()
    }
    private var gravity=DoubleArray(3)
    private var lastSample=0L
    private var validSince=0L
    private var speed=0.0
    private var dynamic=0.0
    private var peak=0.0
    private var elevation=0.0
    private var open:Open?=null
    private val history=ArrayDeque<Pair<Long,Double>>()
    val last get()=lastSample
    fun ready()=lastSample>0 && lastSample-validSince>=3_000_000_000L
    /** Inside a raised hold that already meets the steady, high part of the mouth rule. */
    fun holding()=open?.let{it.run>=2 && elevation>=30} ?: false
    fun reset() {lastSample=0;validSince=0;speed=0.0;dynamic=0.0;peak=0.0;open=null;history.clear()}
    fun accel(t:Long,x:Double,y:Double,z:Double):MotionEpisode? {
        if(!x.isFinite() || !y.isFinite() || !z.isFinite()) {reset();return null}
        val raw=doubleArrayOf(x,y,z)
        if(norm(raw) !in 2.0..25.0) {reset();return null}
        if(lastSample>0 && t<=lastSample)return null
        if(lastSample>0 && t-lastSample>350_000_000L)reset()
        if(lastSample==0L){gravity=raw;lastSample=t;validSince=t;return null}
        val dt=(t-lastSample)/1e9;lastSample=t
        val previous=gravity.copyOf();val alpha=1-exp(-dt/.30)
        for(i in 0..2)gravity[i]+=alpha*(raw[i]-gravity[i])
        val beta=1-exp(-dt/.40);val dyn=norm(DoubleArray(3){raw[it]-gravity[it]})
        speed+=beta*(angle(previous,gravity)/dt-speed);dynamic+=beta*(dyn-dynamic)
        peak=max(speed,peak*exp(-dt/1.5))
        val magnitude=norm(gravity);val u=DoubleArray(3){gravity[it]/magnitude}
        elevation=asin(u[0].coerceIn(-1.0,1.0))*180/PI
        if(history.isEmpty() || t-history.last().first>=200_000_000L) {
            history.addLast(t to elevation);while(t-history.first().first>12_000_000_000L)history.removeFirst()
        }
        val steady=speed<30 && dynamic<1.5
        val o=open
        if(o==null) {
            if(elevation>=15 && ready()) {
                // Without 3 s of earlier posture the rise is unknown, so the episode cannot meet the rule.
                val pre=if(t-history.first().first>=3_000_000_000L)history.filter{t-it.first<=10_000_000_000L}.minOf{it.second} else Double.NaN
                open=Open(t,pre,peak,elevation)
            }
            return null
        }
        // The hold ends when the hand drops 20° below where it was held, or turns to a clearly different posture.
        val floor=if(o.n>0)max(10.0,o.mean-20) else 10.0
        val inside=o.stable<1 || angle(u,o.sum)<=40
        if(elevation>=floor && inside)o.lastHigh=t
        o.maxElevation=max(o.maxElevation,elevation)
        if(steady && elevation>=floor && inside) {
            o.stable+=dt;o.run+=dt;for(i in 0..2)o.sum[i]+=u[i]*dt
            o.n++;o.mean+=(elevation-o.mean)/o.n
            val roll=atan2(-u[1],u[2]);o.rollCos+=cos(roll)*dt;o.rollSin+=sin(roll)*dt
            o.block2+=dyn*dyn*dt;o.blockTime+=dt
            if(o.blockTime>=1) {if(o.blocks.size<240)o.blocks.add(sqrt(o.block2/o.blockTime));o.block2=0.0;o.blockTime=0.0}
        } else {if(o.run>=2)o.holds++;o.run=0.0}
        if(t-o.lastHigh<=1_500_000_000L)return null
        open=null
        if(o.run>=2)o.holds++
        if(o.stable<3)return null
        val length=norm(o.sum);val direction=o.sum.map{it/length}
        val held=asin(direction[0].coerceIn(-1.0,1.0))*180/PI
        val resultant=(sqrt(o.rollCos*o.rollCos+o.rollSin*o.rollSin)/o.stable).coerceIn(1e-12,1.0)
        // Median of one-second blocks: a single bump does not decide whether the hand was steady.
        val micro=if(o.blocks.isEmpty())sqrt(o.block2/o.blockTime.coerceAtLeast(1e-9)) else o.blocks.sorted().let{if(it.size%2==1)it[it.size/2] else (it[it.size/2-1]+it[it.size/2])/2}
        return MotionEpisode(o.start,o.lastHigh,o.stable,direction,held,o.maxElevation,sqrt(-2*ln(resultant))*180/PI,micro,held-o.pre,o.entrySpeed,o.holds)
    }
}

/** Offline extraction with the same tracker the live detector runs, so stored and live features agree. */
object MotionEpisodes {
    val scales=listOf(.25,.25,.25,.5,.35,15.0,5.0)
    val floors=listOf(.06,.06,.06,.15,.08,5.0,1.5)
    fun extract(points:List<MotionPoint>):List<MotionEpisode> {
        val tracker=EpisodeTracker()
        return points.sortedBy{it.ns}.distinctBy{it.ns}.mapNotNull{tracker.accel(it.ns,it.x,it.y,it.z)}
    }
    /** A review labels a window, not which raised hold was the act. An alert names its own hold; a confirmed
     *  mark keeps its most mouth-like hold; a discarded window rejects every deliberate hold inside it. */
    fun exemplars(episodes:List<MotionEpisode>,confirmed:Boolean,ownStart:Long=-1,ownEnd:Long=-1):List<MotionEpisode> {
        val deliberate=episodes.filter{it.deliberate()}
        val own=if(ownStart<0)emptyList() else deliberate.filter{it.startNs<=ownEnd && it.endNs>=ownStart}
        return when {
            own.isNotEmpty() -> own.take(1)
            confirmed -> listOfNotNull(deliberate.maxByOrNull{(if(it.mouth())100.0 else 0.0)+it.elevation})
            else -> deliberate.take(4)
        }
    }
}
