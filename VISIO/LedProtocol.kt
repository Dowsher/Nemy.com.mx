package org.pipalab.core
import java.nio.ByteBuffer
import java.util.UUID

/** Fixed-size protocol fits the minimum BLE MTU; no sensor values or consumption type are transmitted. */
object LedProtocol {
    val SERVICE=UUID.fromString("a7d84e20-2d6a-4a31-995f-4a9e778cdc11")
    val RX=UUID.fromString("a7d84e21-2d6a-4a31-995f-4a9e778cdc11")
    val TX=UUID.fromString("a7d84e22-2d6a-4a31-995f-4a9e778cdc11")
    fun command(id:UUID,durationMs:Int):ByteArray {
        require(durationMs in 100..10000)
        return ByteBuffer.allocate(19).put(1).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).putShort(durationMs.toShort()).array()
    }
    data class Ack(val id:UUID,val status:Int)
    fun ack(bytes:ByteArray):Ack {
        require(bytes.size==18 && bytes[0]==2.toByte())
        val b=ByteBuffer.wrap(bytes);b.get()
        return Ack(UUID(b.long,b.long),b.get().toInt() and 255)
    }
}
