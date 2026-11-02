package org.pipalab.core
import org.junit.Assert.*
import org.junit.Test

class AcousticImpulseTest {
    @Test fun silenceAndSustainedSoundAreNotImpulseLabels() {
        val d=AcousticImpulseDetector()
        assertTrue(d.add(ShortArray(32000),32000).isEmpty())
        assertTrue(d.add(ShortArray(16000){12000},16000).isEmpty())
        assertTrue(d.add(ShortArray(1600),1600).isEmpty())
    }
    @Test fun quietImpulseWorksAndGradualEnvelopeDoesNotTrigger() {
        val d=AcousticImpulseDetector();d.add(ShortArray(20000),20000)
        assertEquals(1,(d.add(ShortArray(320){2200},320)+d.add(ShortArray(1600),1600)).size)
        val slow=AcousticImpulseDetector();slow.add(ShortArray(20000),20000)
        val ramp=ShortArray(3200){i->(i.coerceAtMost(3199-i)*5).toShort()}
        assertTrue((slow.add(ramp,ramp.size)+slow.add(ShortArray(1600),1600)).isEmpty())
    }
    @Test fun transientHasSampleClockAndCooldownAcrossReadChunks() {
        val d=AcousticImpulseDetector();d.add(ShortArray(20000),20000)
        val found=(d.add(ShortArray(240){16000},240)+d.add(ShortArray(1600),1600))
        assertEquals(1,found.size);assertEquals(20000L,found.single().frame)
        assertTrue((d.add(ShortArray(320){16000},320)+d.add(ShortArray(1600),1600)).isEmpty())
        d.add(ShortArray(32000),32000)
        assertEquals(1,(d.add(ShortArray(320){16000},320)+d.add(ShortArray(1600),1600)).size)
    }
}
