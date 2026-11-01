package org.pipalab.core

import org.junit.Assert.*
import org.junit.Test

class SessionControlTest {
    @Test fun commandsAreWholeFinalPhrases() {
        assertEquals(SessionVoice.Command.START,SessionVoice.command("¡ACCIÓN!"))
        assertEquals(SessionVoice.Command.STOP,SessionVoice.command("Corte."))
        listOf("voy a decir acción", "hice un corte", "no corte", "acción y corte", "reacción", "").forEach { assertNull(SessionVoice.command(it)) }
    }
    @Test fun duplicateCallbacksCannotRestartOrStopTwice() {
        val g=SessionGate();assertTrue(g.start());assertFalse(g.start());g.acknowledged()
        assertTrue(g.stop());assertFalse(g.stop());g.acknowledged();assertEquals(SessionGate.Phase.SAVING,g.phase)
        g.finish();assertFalse(g.start())
    }
    @Test fun cutDuringCameraStartupIsFinal() {
        val g=SessionGate();g.start();g.stop();g.acknowledged();assertEquals(SessionGate.Phase.SAVING,g.phase)
    }
    @Test fun clockRemovesWatchProcessingTime() {
        val p=ClockProbe(1_000_000_000,6_010_000_000,6_030_000_000,1_040_000_000)
        assertTrue(p.valid);assertEquals(20_000_000,p.roundTrip);assertEquals(5_000_000_000,p.offset)
    }
    @Test fun clockRejectsBadOrStaleSamplesAndPrefersSmallRtt() {
        val c=SessionClock()
        assertFalse(c.add(ClockProbe(100,300,500,150)))
        c.add(ClockProbe(1000,2020,2030,1050));c.add(ClockProbe(2000,3005,3010,2015))
        assertEquals(10,c.best(2020)!!.roundTrip);assertNull(c.best(40_000_000_000))
    }
}
