package org.pipalab.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object LocalProtocol {
    val SERVICE: UUID = UUID.fromString("a7d84e00-2d6a-4a31-995f-4a9e778cdc11")
    val RX: UUID = UUID.fromString("a7d84e01-2d6a-4a31-995f-4a9e778cdc11")
    val TX: UUID = UUID.fromString("a7d84e02-2d6a-4a31-995f-4a9e778cdc11")
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MAX_FRAME = 262144
    fun random(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256")); doFinal(bytes) }
    fun hello(key: ByteArray, nonce: ByteArray): ByteArray = byteArrayOf(16) + nonce + hmac(key, byteArrayOf(16)+nonce)
    fun verifyHello(key: ByteArray, frame: ByteArray): ByteArray {
        require(frame.size == 65 && frame[0] == 16.toByte())
        val nonce = frame.copyOfRange(1,33)
        require(MessageDigest.isEqual(frame.copyOfRange(33,65), hmac(key,byteArrayOf(16)+nonce))) { "Vínculo no autorizado" }
        return nonce
    }
    fun reply(key: ByteArray, client: ByteArray, server: ByteArray): ByteArray =
        byteArrayOf(17)+server+hmac(key,byteArrayOf(17)+client+server)
    fun verifyReply(key: ByteArray, client: ByteArray, frame: ByteArray): ByteArray {
        require(frame.size == 65 && frame[0] == 17.toByte())
        val server = frame.copyOfRange(1,33)
        require(MessageDigest.isEqual(frame.copyOfRange(33,65),hmac(key,byteArrayOf(17)+client+server))) { "Reloj no autorizado" }
        return server
    }
    fun packets(frame: ByteArray, mtu: Int): List<ByteArray> {
        require(frame.size in 1..MAX_FRAME)
        val payload = (mtu-4).coerceIn(16,508)
        val out = mutableListOf<ByteArray>()
        var pos = 0
        val firstSize = minOf(frame.size, payload-4)
        out.add(byteArrayOf(1)+ByteBuffer.allocate(4).putInt(frame.size).array()+frame.copyOfRange(0,firstSize))
        pos = firstSize
        while (pos < frame.size) { val end = minOf(pos+payload,frame.size); out.add(byteArrayOf(0)+frame.copyOfRange(pos,end)); pos=end }
        return out
    }
    class Assembler {
        private var total = 0
        private val buffer = ByteArrayOutputStream()
        fun accept(packet: ByteArray): ByteArray? {
            require(packet.isNotEmpty())
            val start = packet[0] == 1.toByte()
            if (start) { require(packet.size >= 5); total=ByteBuffer.wrap(packet,1,4).int; require(total in 1..MAX_FRAME); buffer.reset() }
            else require(packet[0] == 0.toByte() && total > 0)
            buffer.write(packet, if(start) 5 else 1, packet.size-if(start) 5 else 1)
            require(buffer.size() <= total)
            return if (buffer.size() == total) buffer.toByteArray().also { total=0; buffer.reset() } else null
        }
    }
}
class SecureSession(root: ByteArray, client: ByteArray, server: ByteArray, private val isPhone: Boolean) {
    private val key = SecretKeySpec(LocalProtocol.hmac(root, "PipaLab BLE v1".toByteArray()+client+server), "AES")
    private var sent = 0L
    private var received = 0L
    private fun nonce(phone: Boolean, seq: Long) = ByteBuffer.allocate(12).putInt(if(phone) 0x50484f4e else 0x57415443).putLong(seq).array()
    @Synchronized fun encrypt(text: String): ByteArray {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val seq = ++sent
        val header = byteArrayOf(32)+ByteBuffer.allocate(8).putLong(seq).array()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,nonce(isPhone,seq))); cipher.updateAAD(header)
        return header+cipher.doFinal(zipped)
    }
    @Synchronized fun decrypt(frame: ByteArray): String {
        require(frame.size >= 25 && frame[0] == 32.toByte())
        val seq = ByteBuffer.wrap(frame,1,8).long
        require(seq > received) { "Mensaje repetido" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,nonce(!isPhone,seq))); cipher.updateAAD(frame.copyOfRange(0,9))
        val bytes=cipher.doFinal(frame.copyOfRange(9,frame.size))
        val plain=GZIPInputStream(bytes.inputStream()).use { input ->
            val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=LocalProtocol.MAX_FRAME); out.write(buffer,0,n) }
            out.toByteArray()
        }
        require(plain.size <= LocalProtocol.MAX_FRAME)
        received=seq
        return plain.toString(Charsets.UTF_8)
    }
}
/** Clave del vínculo importada una vez; queda envuelta con Android Keystore. */
object PairingKeys {
    fun load(context: Context): ByteArray? {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias="pipalab-local-link"
        if (!ks.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
        val key=ks.getKey(alias,null) as SecretKey
        val prefs=context.getSharedPreferences("local-link",Context.MODE_PRIVATE)
        val incoming=java.io.File(context.filesDir,"pairing.key")
        if (incoming.exists()) {
            val raw=incoming.readBytes(); require(raw.size == 32)
            val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key)
            prefs.edit().putString("wrapped",Base64.getEncoder().encodeToString(c.doFinal(raw)))
                .putString("iv",Base64.getEncoder().encodeToString(c.iv)).commit()
            check(incoming.delete()); raw.fill(0)
        }
        val wrapped=prefs.getString("wrapped",null) ?: return null
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,Base64.getDecoder().decode(prefs.getString("iv",""))))
        return c.doFinal(Base64.getDecoder().decode(wrapped))
    }
}
