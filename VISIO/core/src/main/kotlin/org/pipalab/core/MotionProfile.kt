package org.pipalab.core

import kotlin.math.*

data class MotionPoint(val ns:Long,val x:Double,val y:Double,val z:Double)
data class MotionTemplate(val group:String,val positive:Boolean,val values:List<Double>)
/** Event-grouped exemplars; unlabeled controls and test notifications are never negative labels. */
data class MotionProfile(val templates:List<MotionTemplate> = emptyList()) {
    val positiveGroups get()=templates.filter{it.positive}.map{it.group}.distinct().size
    val negativeGroups get()=templates.filter{!it.positive}.map{it.group}.distinct().size
    private val scale:List<Double> = MotionFingerprint.scales.indices.map{i->
        val values=templates.mapNotNull{it.values.getOrNull(i)}.sorted()
        if(values.size<4)MotionFingerprint.scales[i] else {
            val median=values[values.size/2];val deviations=values.map{abs(it-median)}.sorted()
            max(MotionFingerprint.floors[i],deviations[deviations.size/2]*1.4826)
        }
    }
    fun similarity(values:List<Double>):Pair<Double,Double> {
        fun best(positive:Boolean)=templates.asSequence().filter{it.positive==positive && it.values.size==values.size}
            .map{t->val distance=values.indices.sumOf{i->((values[i]-t.values[i])/scale[i]).pow(2)}/values.size;exp(-distance/2)}.maxOrNull() ?: 0.0
        return best(true) to best(false)
    }
}
/** Eight-second, time-normalized motion descriptor. Incomplete or gapped windows are rejected. */
object MotionFingerprint {
    val scales=listOf(1.0,1.0,1.0,.5,20.0,30.0,.25)
    val floors=listOf(.06,.06,.06,.10,2.0,5.0,.05)
    fun extract(input:List<MotionPoint>):List<Double>? {
        if(input.isEmpty())return null
        val end=input.last().ns;val points=input.filter{it.ns>=end-8_000_000_000L}
        if(points.size<64 || end-points.first().ns<7_500_000_000L)return null
        if(points.zipWithNext().any{(a,b)->b.ns-a.ns !in 1..350_000_000L})return null
        if(points.any{!it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()})return null
        var gravity=doubleArrayOf(points.first().x,points.first().y,points.first().z)
        val origin=gravity.copyOf();var last=points.first().ns;var duration=0.0;var dynamic2=0.0;var speedSum=0.0;var maxTilt=0.0;var quiet=0.0
        val means=DoubleArray(3)
        for(p in points.drop(1)) {
            val dt=(p.ns-last)/1e9;last=p.ns;val raw=doubleArrayOf(p.x,p.y,p.z);val before=gravity.copyOf()
            val alpha=1-exp(-dt/.30);for(i in 0..2)gravity[i]+=alpha*(raw[i]-gravity[i])
            val magnitude=norm(gravity);if(magnitude<1)return null
            val dynamic=norm(DoubleArray(3){raw[it]-gravity[it]});val speed=angle(before,gravity)/dt
            duration+=dt;dynamic2+=dynamic*dynamic*dt;speedSum+=speed*dt;maxTilt=max(maxTilt,angle(origin,gravity))
            if(dynamic<1.2 && speed<18)quiet+=dt
            for(i in 0..2)means[i]+=gravity[i]/magnitude*dt
        }
        return means.map{it/duration}+listOf(sqrt(dynamic2/duration),speedSum/duration,maxTilt,quiet/duration)
    }
    fun windows(points:List<MotionPoint>):List<List<Double>> {
        val sorted=points.sortedBy{it.ns}.distinctBy{it.ns};if(sorted.isEmpty())return emptyList()
        val result=ArrayList<List<Double>>();var end=sorted.first().ns+8_000_000_000L;var left=0;var right=0
        while(end<=sorted.last().ns) {
            while(left<sorted.size && sorted[left].ns<end-8_000_000_000L)left++
            while(right<sorted.size && sorted[right].ns<=end)right++
            extract(sorted.subList(left,right))?.takeIf{it[5]>=20 && it[4]>=3}?.let{result.add(it)}
            end+=4_000_000_000L
        }
        return result
    }
}
