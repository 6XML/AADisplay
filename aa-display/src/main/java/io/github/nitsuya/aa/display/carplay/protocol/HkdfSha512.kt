package io.github.nitsuya.aa.display.carplay.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA-512 (RFC 5869) used by AirPlay / HomeKit pairing. */
object HkdfSha512 {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(64) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(prk, "HmacSHA512"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, copy)
            offset += copy
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: String, info: String, length: Int): ByteArray =
        derive(ikm, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8), length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
