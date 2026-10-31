package org.pipalab.wear
import org.junit.Assert.*
import org.junit.Test
class StreamStatsTest {
    @Test fun cadenceCountsGapsAndDuplicatesWithoutInfinity() {
        val stats = StreamStats()
        assertEquals(0, stats.add(1_000_000_000, 20_000_000))
        assertEquals(0, stats.add(1_020_000_000, 20_000_000))
        assertEquals(2, stats.add(1_080_000_000, 20_000_000))
        stats.add(1_080_000_000, 20_000_000)
        assertEquals(1, stats.nonIncreasing)
        assertEquals(2, stats.estimatedMissing)
        assertTrue(stats.hz().isFinite())
    }
    @Test fun noFrequencyBeforeTwoSamples() {
        val stats = StreamStats()
        stats.add(42)
        assertEquals(0.0, stats.hz(), 0.0)
    }
}
