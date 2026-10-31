package org.pipalab.core
import org.junit.Assert.*
import org.junit.Test

class TrainingDataTest {
    @Test fun minuteMeansUseAllValidIntervalsAndIgnoreInvalidHeartRate() {
        val b=MinuteBook()
        b.add("heart_rate",60001,mapOf("bpm" to "90","status" to "1","ibi_ms_json" to "[600,800,900]","ibi_status_json" to "[0,0,-1]"))
        b.add("heart_rate",61001,mapOf("bpm" to "0","status" to "0","ibi_ms_json" to "[1000]","ibi_status_json" to "[0]"))
        val s=b.ready(140000).single()
        assertEquals(2,s.rows);assertEquals(90.0,s.channels.getValue("bpm").mean,.001)
        assertEquals(800.0,s.channels.getValue("ibi_ms_json").mean,.001)
        assertEquals(3,s.channels.getValue("ibi_ms_json").count)
        assertTrue(b.ready(141000).isEmpty())
        b.add("heart_rate",62000,mapOf("bpm" to "100","status" to "1"))
        assertEquals(95.0,b.ready(150000).single().channels.getValue("bpm").mean,.001)
    }
    @Test fun compactBufferKeepsControlsAndRequestedWindowWithoutDuplicates() {
        val saved=mutableListOf<TrainingRow>();val windows=mutableListOf<TrainingWindow>()
        val b=TrainingBuffer(1000,saved::add,windows::add)
        for(i in 0..1199) b.add(TrainingRow("acelerometro",i.toLong(),i*100_000_000L,1000+i*100L,listOf(i)))
        assertEquals(101,saved.size)
        b.preserve("event1","confirmed",100000,110000)
        val count=saved.size;assertEquals(202,count)
        b.preserve("event1","confirmed",100000,110000)
        b.preserve("event2","false_positive",100000,110000)
        assertEquals(count,saved.size)
        assertEquals(saved.size,saved.map{it.stream to it.sequence}.toSet().size)
    }
    @Test fun oldWindowReportsMissingBufferInsteadOfInventingSamples() {
        val saved=mutableListOf<TrainingRow>();val b=TrainingBuffer(1000,saved::add,{})
        for(i in 0..400) b.add(TrainingRow("acelerometro",i.toLong(),i*1_000_000_000L,1000+i*1000L,emptyList()))
        val before=saved.size;val w=b.preserve("old","manual",1000,2000)
        assertTrue(w.availableFrom>w.end);assertEquals(before,saved.size)
    }
    @Test fun zoomKeepsAnchorAndPanClampsToRealSession() {
        val w=TimeWindow(10000,30000)
        assertEquals(TimeWindow(15000,25000),w.transform(2.0,0.0,.5,0,60000))
        assertEquals(TimeWindow(0,20000),w.transform(1.0,10.0,.5,0,60000))
        assertEquals(TimeWindow(40000,60000),w.transform(1.0,-10.0,.5,0,60000))
        assertEquals(TimeWindow(0,60000),w.transform(.1,0.0,.5,0,60000))
        assertEquals(w,w.transform(1.0,0.0,Double.NaN,0,60000))
    }
}
