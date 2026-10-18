package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay / HomeKit post-pairing control framing.
 *
 * Wire: `uint16_le plaintextLen || ChaCha20-Poly1305(ciphertext||tag)`.
 * AAD = the 2-byte length; nonce = 4 zero bytes + 8-byte LE counter (per direction).
 * Max plaintext per frame matches DiPlay ControlCipher (0x4000).
 */
class ControlChannelCrypto(
    private val writeKey: ByteArray,
    private val readKey: ByteArray,
) {
    private val writeCounter = AtomicLong(0)
    private val readCounter = AtomicLong(0)

    fun writeMessage(out: OutputStream, plaintext: ByteArray) {
        writeFrames(out, plaintext)
    }

    /**
     * One ChaCha frame per part. iPhone's event `POST /command` seals the RTSP
     * header and the plist body as two frames; a single combined frame is what
     * this HU retransmits the detect over.
     */
    fun writeFrames(out: OutputStream, vararg parts: ByteArray) {
        for (part in parts) {
            if (part.isEmpty()) continue
            var offset = 0
            while (offset < part.size) {
                val n = minOf(MAX_PLAINTEXT, part.size - offset)
                val chunk = part.copyOfRange(offset, offset + n)
                out.write(sealFrame(chunk))
                offset += n
            }
        }
        out.flush()
    }

    /**
     * Read one complete RTSP/HTTP message (headers + Content-Length body) from
     * consecutive encrypted frames.
     */
    fun readMessage(input: InputStream): ByteArray? {
        val acc = ArrayList<Byte>(4096)
        while (true) {
            val frame = readFrame(input) ?: return if (acc.isEmpty()) null else acc.toByteArray()
            for (b in frame) acc.add(b)
            val bytes = acc.toByteArray()
            val headerEnd = indexOfCrlfCrlf(bytes)
            if (headerEnd < 0) continue
            val headerText = bytes.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
            val contentLen = contentLengthOf(headerText)
            val total = headerEnd + 4 + contentLen
            if (bytes.size >= total) {
                return bytes.copyOfRange(0, total)
            }
        }
    }

    private fun sealFrame(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT) { "control frame plaintext > $MAX_PLAINTEXT" }
        val len = plaintext.size
        val aad = byteArrayOf((len and 0xff).toByte(), ((len ushr 8) and 0xff).toByte())
        val nonce = nonceFromCounter(writeCounter.getAndIncrement())
        val sealed = chachaSeal(writeKey, nonce, aad, plaintext)
            ?: throw IllegalStateException("control seal failed")
        return aad + sealed
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val len = b0 or (b1 shl 8)
        if (len < 0 || len > MAX_PLAINTEXT) {
            Log.e(CarPlayConsts.TAG, "control frame bad length=$len")
            return null
        }
        val aad = byteArrayOf(b0.toByte(), b1.toByte())
        val cipherAndTag = ByteArray(len + TAG_LEN)
        var got = 0
        while (got < cipherAndTag.size) {
            val n = input.read(cipherAndTag, got, cipherAndTag.size - got)
            if (n < 0) return null
            got += n
        }
        val nonce = nonceFromCounter(readCounter.getAndIncrement())
        return chachaOpen(readKey, nonce, aad, cipherAndTag)
    }

    companion object {
        const val MAX_PLAINTEXT = 0x4000
        private const val TAG_LEN = 16

        fun nonceFromCounter(counter: Long): ByteArray {
            val n = ByteArray(12)
            var c = counter
            for (i in 0 until 8) {
                n[4 + i] = (c and 0xff).toByte()
                c = c ushr 8
            }
            return n
        }

        fun chachaSeal(
            key: ByteArray,
            nonce12: ByteArray,
            aad: ByteArray,
            plaintext: ByteArray,
        ): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce12),
                )
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "ChaCha20-Poly1305 seal failed", e)
                null
            }
        }

        fun chachaOpen(
            key: ByteArray,
            nonce12: ByteArray,
            aad: ByteArray,
            ciphertextAndTag: ByteArray,
        ): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce12),
                )
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                cipher.doFinal(ciphertextAndTag)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "ChaCha20-Poly1305 open failed", e)
                null
            }
        }

        private fun indexOfCrlfCrlf(bytes: ByteArray): Int {
            if (bytes.size < 4) return -1
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == '\r'.code.toByte() &&
                    bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() &&
                    bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i
                }
            }
            return -1
        }

        private fun contentLengthOf(headerText: String): Int {
            for (line in headerText.split("\r\n")) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                if (line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                    return line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            return 0
        }
    }
}
