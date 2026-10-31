package org.pipalab.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ActivityCaptureTest {
    @Test fun rejectsUnboundedRecordingAndInvalidIdentifiers() {
        for (duration in listOf(0, 14, 601, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            ActivityCapture("id", "rest", "Reposo", "", true, duration, 1)
        }
        assertThrows(IllegalArgumentException::class.java) { ActivityCapture("../id", "rest", "Reposo", "", false, 60, 1) }
        assertThrows(IllegalArgumentException::class.java) { ActivityCapture("id", "rest", " ", "", false, 60, 1) }
        assertEquals(60, ActivityCapture("id", "rest", "Reposo", "Descripción libre", false, 60, 1).durationSeconds)
    }
    @Test fun filesExcludeCredentialsAndUnfinishedTransfers() {
        for (name in listOf("audio.wav", "audio.json", "meta.json", "audio_timeline.csv")) assertTrue(ActivityTypes.transferable(name))
        for (name in listOf("pairing.key", "audio.wav.part", "../audio.wav", "x/y.csv", "x.json")) assertFalse(ActivityTypes.transferable(name))
    }
    @Test fun pcmIsLosslessAndHeaderMatchesPayload() {
        val file = File.createTempFile("visio-audio", ".wav")
        try {
            PcmWave(file).use { it.write(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE), 5) }
            val bytes = file.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(54, bytes.size); assertEquals(46, b.getInt(4)); assertEquals(16000, b.getInt(24)); assertEquals(10, b.getInt(40))
            assertEquals(Short.MIN_VALUE, b.getShort(44)); assertEquals(Short.MAX_VALUE, b.getShort(52))
        } finally { file.delete() }
    }
    @Test fun interruptedPcmRetainsOnlyCompleteSamples() {
        val file = File.createTempFile("visio-recover", ".wav")
        try {
            file.writeBytes(PcmWave.header(0, 16000) + byteArrayOf(1, 0, 2, 0, 9))
            assertEquals(2, PcmWave.recover(file)); assertEquals(48, file.length())
            assertEquals(4, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        } finally { file.delete() }
    }
}
