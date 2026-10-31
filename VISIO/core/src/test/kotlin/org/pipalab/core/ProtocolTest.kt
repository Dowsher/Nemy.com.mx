package org.pipalab.core
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun csvRoundTripsQuotedMultilineSensorFields() {
        val values=listOf("123","1,2","\"texto\"","dos\nlÃ­neas","","[800,810]")
        assertEquals(listOf(values,values),Csv.rows((Csv.line(values)+"\r\n"+Csv.line(values)+"\n").reader()).toList())
    }
    @Test fun csvRejectsTruncatedQuotedRow() {
        assertThrows(IllegalArgumentException::class.java){Csv.rows("a,\"unfinished".reader()).toList()}
    }
    @Test fun fragmentationWorksForSmallAndLargeMtu() {
        val data=ByteArray(100_003){(it%251).toByte()}
        for(mtu in listOf(23,247,517)) {
            val a=LocalProtocol.Assembler(); var result:ByteArray?=null
            for(p in LocalProtocol.packets(data,mtu)) { assertTrue(p.size<=mtu-3); a.accept(p)?.let{result=it} }
            assertArrayEquals(data,result)
        }
    }
    @Test fun untrustedLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java){LocalProtocol.Assembler().accept(byteArrayOf(1,127,127,127,127))}
    }
    @Test fun mutualAuthenticationRejectsDifferentKey() {
        val key=LocalProtocol.random(); val c=LocalProtocol.random(); val s=LocalProtocol.random()
        assertArrayEquals(c,LocalProtocol.verifyHello(key,LocalProtocol.hello(key,c)))
        assertArrayEquals(s,LocalProtocol.verifyReply(key,c,LocalProtocol.reply(key,c,s)))
        assertThrows(IllegalArgumentException::class.java){LocalProtocol.verifyHello(LocalProtocol.random(),LocalProtocol.hello(key,c))}
    }
    @Test fun encryptedExchangeRejectsTamperReplayAndReconnectReplay() {
        val key=LocalProtocol.random(); val c=LocalProtocol.random(); val s=LocalProtocol.random()
        val phone=SecureSession(key,c,s,true); val watch=SecureSession(key,c,s,false)
        val packet=phone.encrypt("datos confidenciales, \n[1,2,3]")
        assertFalse(packet.toString(Charsets.UTF_8).contains("confidenciales"))
        assertEquals("datos confidenciales, \n[1,2,3]",watch.decrypt(packet))
        assertThrows(IllegalArgumentException::class.java){watch.decrypt(packet)}
        assertEquals("reply",phone.decrypt(watch.encrypt("reply")))
        val damaged=phone.encrypt("next").also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
        assertThrows(Exception::class.java){watch.decrypt(damaged)}
        assertEquals("after tamper",watch.decrypt(phone.encrypt("after tamper")))
        val newWatch=SecureSession(key,LocalProtocol.random(),s,false)
        assertThrows(Exception::class.java){newWatch.decrypt(phone.encrypt("old session"))}
    }
}
