package org.pipalab.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM mono signed 16 bit, little endian. Header is patched at close and recoverable after interruption. */
class PcmWave(private val file: File, val sampleRate: Int = 16000) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    var frames = 0L; private set
    private var closed = false
    init { require(sampleRate in 8000..48000); output.setLength(0); output.write(header(0, sampleRate)) }
    fun write(samples: ShortArray, count: Int) {
        check(!closed); require(count in 0..samples.size)
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        output.write(bytes.array()); frames += count
    }
    fun checkpoint() { output.fd.sync() }
    override fun close() {
        if (closed) return
        output.seek(0); output.write(header(frames * 2, sampleRate)); output.fd.sync(); output.close(); closed = true
    }
    companion object {
        fun header(bytes: Long, rate: Int): ByteArray {
            require(bytes >= 0 && bytes <= Int.MAX_VALUE - 36 && bytes % 2 == 0L)
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes.toInt()).array()
        }
        fun recover(file: File, sampleRate: Int = 16000): Long {
            if (!file.exists() || file.length() < 44) return 0
            RandomAccessFile(file, "rw").use { out ->
                val n = (out.length() - 44) / 2 * 2
                out.setLength(n + 44); out.seek(0); out.write(header(n, sampleRate)); out.fd.sync()
                return n / 2
            }
        }
    }
}
