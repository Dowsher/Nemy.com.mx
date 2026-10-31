package org.pipalab.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AdaptiveDetectorTest {
    private class Trace(val hz:Int=25) {
        val d=GestureDetector();var t=1_000_000_000L;var angle=0.0
        val alerts=mutableListOf<GestureDetector.Candidate>();val points=mutableListOf<MotionPoint>()
        fun move(seconds:Double,target:Double,ramp:Boolean=false,noise:Double=0.0) {
            val previous=angle;val n=(seconds*hz).toInt()
            repeat(n){i->t+=1_000_000_000L/hz;val a=if(ramp)previous+(target-previous)*(i+1)/n else target
                val x=9.81*sin(a)+noise*sin(i*2.0);val z=9.81*cos(a)
                points.add(MotionPoint(t,x,0.0,z));d.accel(t,x,0.0,z)?.let{alerts.add(it)}}
            angle=target
        }
        fun gesture(direction:Double=1.2) {move(1.5,direction,true);move(3.0,direction);move(1.5,0.0,true);move(3.0,0.0)}
    }
    @Test fun consistentGesturesWorkAt25And50HzWithoutGyroscope() {
        for(hz in listOf(25,50)) {
            val s=Trace(hz);s.move(12.0,0.0);assertTrue(s.d.calibrated());assertEquals(0,s.d.stage(s.t))
            s.gesture();assertTrue(s.alerts.isEmpty());s.gesture();assertEquals(1,s.alerts.size)
            assertEquals(2,s.alerts.single().gestures);assertEquals(2,s.d.stage(s.t))
            s.d.reset();s.move(6.0,0.0);s.gesture();s.gesture();assertEquals("Reconnect must not erase cooldown",1,s.alerts.size)
        }
    }
    @Test fun MissingDataAndNonFiniteInputCannotCompleteGesture() {
        val s=Trace();s.move(12.0,0.0);s.gesture();s.t+=2_000_000_000L;s.move(6.0,0.0);s.gesture()
        assertTrue(s.alerts.isEmpty());s.d.accel(s.t+1,Double.NaN,0.0,9.81);assertFalse(s.d.calibrated())
    }
    @Test fun oppositeTrajectoriesAreNotAConsistentSequence() {
        val s=Trace();s.move(12.0,0.0);s.gesture();s.gesture(-1.2);assertTrue(s.alerts.isEmpty())
    }
    @Test fun postureChangeOrBriefWaveDoesNotTrigger() {
        val s=Trace();s.move(12.0,0.0);repeat(4){s.move(.6,1.2,true);s.move(.2,1.2);s.move(.6,0.0,true);s.move(1.0,0.0)}
        s.move(20.0,1.2,true);s.move(30.0,1.2);assertTrue(s.alerts.isEmpty())
    }
    @Test fun fingerprintRejectsGapsAndIsStableAcrossSamplingRates() {
        val a=Trace(25);a.move(12.0,0.0);a.gesture();val b=Trace(50);b.move(12.0,0.0);b.gesture()
        val fa=MotionFingerprint.extract(a.points)!!;val fb=MotionFingerprint.extract(b.points)!!
        val profile=MotionProfile(listOf(MotionTemplate("event",true,fa)))
        assertTrue(profile.similarity(fb).first>.98)
        assertNull(MotionFingerprint.extract(a.points.filter{it.ns !in (a.t-3_000_000_000L)..(a.t-2_000_000_000L)}))
    }
    @Test fun correctedFalsePositiveRequiresAdditionalEvidence() {
        val seed=Trace();seed.move(12.0,0.0);seed.gesture();seed.gesture()
        val fp=seed.alerts.single().fingerprint
        val s=Trace();s.d.setProfile(MotionProfile(listOf(MotionTemplate("negative",false,fp))))
        s.move(12.0,0.0);s.gesture();s.gesture();assertTrue(s.alerts.isEmpty());s.gesture();assertEquals(1,s.alerts.size)
        assertEquals(3,s.alerts.single().gestures)
    }
    private fun shortMotion(s:Trace) {s.move(.8,1.2,true);s.move(.6,1.2);s.move(.8,0.0,true);s.move(.6,0.0)}
    @Test fun personalTemplatesRequireDistinctGroupsAndNonOverlappingEvidence() {
        val seed=Trace();seed.move(12.0,0.0);repeat(12){shortMotion(seed)}
        val features=MotionFingerprint.windows(seed.points)
        val positive=(1..4).flatMap{i->features.map{MotionTemplate("p$i",true,it)}}
        val negative=features.map{MotionTemplate("n",false,it.mapIndexed{i,v->if(i<3)-v else v})}
        val s=Trace();s.d.setProfile(MotionProfile(positive+negative));s.move(12.0,0.0)
        repeat(12){shortMotion(s)}
        val c=s.alerts.single();assertEquals("personal_motion_windows",c.method)
        assertEquals(2,c.evidenceWindows);assertTrue(c.endNs-c.startNs>=16_000_000_000L)
        val insufficient=Trace();insufficient.d.setProfile(MotionProfile(positive.filter{it.group!="p4"}+negative));insufficient.move(12.0,0.0)
        repeat(12){shortMotion(insufficient)};assertTrue(insufficient.alerts.isEmpty())
    }
    @Test fun conflictingPersonalExamplesNeverCreateTemplateAlert() {
        val seed=Trace();seed.move(12.0,0.0);repeat(12){shortMotion(seed)}
        val features=MotionFingerprint.windows(seed.points)
        val profile=MotionProfile((1..4).flatMap{i->features.map{MotionTemplate("p$i",true,it)}}+features.map{MotionTemplate("n",false,it)})
        val s=Trace();s.d.setProfile(profile);s.move(12.0,0.0);repeat(12){shortMotion(s)};assertTrue(s.alerts.isEmpty())
    }
}
