package org.pipalab.core
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID
class LedProtocolTest {
    @Test fun commandFitsMinimumMtuAndCarriesNoHealthData() {
        val id=UUID.randomUUID();val bytes=LedProtocol.command(id,5000)
        assertEquals(19,bytes.size);val b=ByteBuffer.wrap(bytes)
        assertEquals(1,b.get().toInt());assertEquals(id.mostSignificantBits,b.long);assertEquals(id.leastSignificantBits,b.long);assertEquals(5000,b.short.toInt())
        assertThrows(IllegalArgumentException::class.java){LedProtocol.command(id,0)}
        assertThrows(IllegalArgumentException::class.java){LedProtocol.command(id,10001)}
    }
    @Test fun ackRequiresMatchingWireFormat() {
        val id=UUID.randomUUID()
        val a=ByteBuffer.allocate(18).put(2).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).put(0).array()
        assertEquals(LedProtocol.Ack(id,0),LedProtocol.ack(a))
        assertThrows(IllegalArgumentException::class.java){LedProtocol.ack(a.copyOf(17))}
        assertThrows(IllegalArgumentException::class.java){LedProtocol.ack(a.also{it[0]=1})}
    }
}
