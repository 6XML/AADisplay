package io.github.nitsuya.aa.display.carplay.protocol

import java.io.ByteArrayOutputStream

/** HomeKit / AirPlay TLV8 (values >255 coalesced by repeating type). */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x10

    const val METHOD_PAIR_SETUP = 0
    const val METHOD_PAIR_SETUP_AUTH = 1

    /** HAP kPairingFlag_Transient = 1<<4 */
    const val FLAG_TRANSIENT = 0x00000010

    fun encode(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in entries) {
            var offset = 0
            if (value.isEmpty()) {
                out.write(type and 0xFF)
                out.write(0)
                continue
            }
            while (offset < value.size) {
                val chunk = minOf(255, value.size - offset)
                out.write(type and 0xFF)
                out.write(chunk)
                out.write(value, offset, chunk)
                offset += chunk
            }
        }
        return out.toByteArray()
    }

    fun encode(vararg entries: Pair<Int, ByteArray>): ByteArray = encode(entries.toList())

    fun u8(v: Int): ByteArray = byteArrayOf(v.toByte())

    fun u32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    fun decode(body: ByteArray): Map<Int, ByteArray> {
        val acc = linkedMapOf<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 2 <= body.size) {
            val type = body[i].toInt() and 0xFF
            val len = body[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > body.size) break
            val stream = acc.getOrPut(type) { ByteArrayOutputStream() }
            stream.write(body, i, len)
            i += len
        }
        return acc.mapValues { it.value.toByteArray() }
    }

    fun errorOf(map: Map<Int, ByteArray>): Int? =
        map[ERROR]?.firstOrNull()?.toInt()?.and(0xFF)

    fun stateOf(map: Map<Int, ByteArray>): Int? =
        map[STATE]?.firstOrNull()?.toInt()?.and(0xFF)
}
