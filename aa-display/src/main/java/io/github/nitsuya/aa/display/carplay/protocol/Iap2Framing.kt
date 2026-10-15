package io.github.nitsuya.aa.display.carplay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iAP2 wire framing (detect + FF5A link packets + control-session messages).
 *
 * Detect is only `FF 55 02 00 EE 10`. All subsequent packets use `FF 5A` with
 * header/payload checksums (8-bit two's complement). See docs/CARPLAY_REFS.md.
 */
object Iap2Framing {
    val DETECT_MARKER: ByteArray =
        byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

    const val LINK_START = 0xFF5A
    const val CSM_START = 0x4040

    const val CONTROL_SYN = 0x80
    const val CONTROL_ACK = 0x40
    const val CONTROL_EAK = 0x20
    const val CONTROL_RST = 0x10

    /** Session type 0 = control, 2 = external accessory. */
    const val SESSION_TYPE_CONTROL = 0
    const val SESSION_TYPE_EA = 2

    fun checksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var sum = 0
        for (i in offset until offset + length) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return (-sum) and 0xFF
    }

    fun checksumOk(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Boolean {
        var sum = 0
        for (i in offset until offset + length) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return sum == 0
    }

    data class LinkPacket(
        val control: Int,
        val seq: Int,
        val ack: Int,
        val sessionId: Int,
        val payload: ByteArray,
    ) {
        val hasSyn: Boolean get() = (control and CONTROL_SYN) != 0
        val hasAck: Boolean get() = (control and CONTROL_ACK) != 0
        val hasRst: Boolean get() = (control and CONTROL_RST) != 0
    }

    data class LinkSyncPayload(
        val version: Int = 1,
        val maxOutgoing: Int = 5,
        val maxPacketLength: Int = 0x0800,
        val retransmitTimeoutMs: Int = 1500,
        val cumAckTimeoutMs: Int = 73,
        val maxRetransmissions: Int = 30,
        val maxCumAcks: Int = 3,
        val sessions: List<Triple<Int, Int, Int>>,
    ) {
        fun pack(): ByteArray {
            val out = ByteBuffer.allocate(10 + sessions.size * 3).order(ByteOrder.BIG_ENDIAN)
            out.put(version.toByte())
            out.put(maxOutgoing.toByte())
            out.putShort(maxPacketLength.toShort())
            out.putShort(retransmitTimeoutMs.toShort())
            out.putShort(cumAckTimeoutMs.toShort())
            out.put(maxRetransmissions.toByte())
            out.put(maxCumAcks.toByte())
            for ((id, type, ver) in sessions) {
                out.put(id.toByte())
                out.put(type.toByte())
                out.put(ver.toByte())
            }
            return out.array()
        }

        fun controlSessionId(): Int =
            sessions.firstOrNull { it.second == SESSION_TYPE_CONTROL }?.first
                ?: sessions.firstOrNull()?.first
                ?: 0x0A

        companion object {
            fun parse(payload: ByteArray): LinkSyncPayload? {
                if (payload.size < 10) return null
                val version = payload[0].toInt() and 0xFF
                if (version != 1) return null
                val sessions = mutableListOf<Triple<Int, Int, Int>>()
                var i = 10
                while (i + 2 < payload.size) {
                    sessions += Triple(
                        payload[i].toInt() and 0xFF,
                        payload[i + 1].toInt() and 0xFF,
                        payload[i + 2].toInt() and 0xFF,
                    )
                    i += 3
                }
                return LinkSyncPayload(
                    version = version,
                    maxOutgoing = payload[1].toInt() and 0xFF,
                    maxPacketLength =
                        ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF),
                    retransmitTimeoutMs =
                        ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF),
                    cumAckTimeoutMs =
                        ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF),
                    maxRetransmissions = payload[8].toInt() and 0xFF,
                    maxCumAcks = payload[9].toInt() and 0xFF,
                    sessions = sessions,
                )
            }

            /** Prefer CarPlay-common sessions (id 1/3); also advertise 10/11 for lab stacks. */
            fun defaultForSource(): LinkSyncPayload = LinkSyncPayload(
                sessions = listOf(
                    Triple(0x01, SESSION_TYPE_CONTROL, 2),
                    Triple(0x03, SESSION_TYPE_EA, 1),
                    Triple(0x0A, SESSION_TYPE_CONTROL, 1),
                    Triple(0x0B, SESSION_TYPE_EA, 1),
                ),
            )
        }
    }

    data class ControlMessage(
        val messageId: Int,
        val parameters: Map<Int, ByteArray>,
    )

    fun encodeLinkPacket(
        control: Int,
        seq: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val totalLen = if (payload.isEmpty()) 9 else 10 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        header.putShort(LINK_START.toShort())
        header.putShort(totalLen.toShort())
        header.put(control.toByte())
        header.put(seq.toByte())
        header.put(ack.toByte())
        header.put(sessionId.toByte())
        val headerBytes = header.array()
        val out = ByteArray(totalLen)
        System.arraycopy(headerBytes, 0, out, 0, 8)
        out[8] = checksum(headerBytes).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 9, payload.size)
            out[9 + payload.size] = checksum(payload).toByte()
        }
        return out
    }

    /**
     * Try to consume one detect marker or one FF5A link packet from [data] at [offset].
     * @return Pair(consumedBytes, packet-or-null-for-detect) or null if need more bytes / resync.
     */
    fun tryConsume(data: ByteArray, offset: Int, length: Int): ConsumeResult? {
        if (length <= 0) return null
        // Detect marker
        if (length >= DETECT_MARKER.size &&
            data[offset] == DETECT_MARKER[0] &&
            data[offset + 1] == DETECT_MARKER[1]
        ) {
            var match = true
            for (i in DETECT_MARKER.indices) {
                if (data[offset + i] != DETECT_MARKER[i]) {
                    match = false
                    break
                }
            }
            if (match) return ConsumeResult.Detect(DETECT_MARKER.size)
        }
        // Link packet
        if (length >= 2 &&
            (data[offset].toInt() and 0xFF) == 0xFF &&
            (data[offset + 1].toInt() and 0xFF) == 0x5A
        ) {
            if (length < 9) return null
            if (!checksumOk(data, offset, 9)) {
                return ConsumeResult.Skip(1)
            }
            val totalLen =
                ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (totalLen < 9 || totalLen > 4106) return ConsumeResult.Skip(1)
            if (length < totalLen) return null
            val control = data[offset + 4].toInt() and 0xFF
            val seq = data[offset + 5].toInt() and 0xFF
            val ack = data[offset + 6].toInt() and 0xFF
            val sessionId = data[offset + 7].toInt() and 0xFF
            val payload = if (totalLen > 9) {
                if (!checksumOk(data, offset + 9, totalLen - 9)) {
                    return ConsumeResult.Skip(1)
                }
                data.copyOfRange(offset + 9, offset + totalLen - 1)
            } else {
                ByteArray(0)
            }
            return ConsumeResult.Packet(
                totalLen,
                LinkPacket(control, seq, ack, sessionId, payload),
            )
        }
        // Resync: advance one byte when not at a known preamble.
        return ConsumeResult.Skip(1)
    }

    sealed class ConsumeResult {
        abstract val consumed: Int

        data class Detect(override val consumed: Int) : ConsumeResult()
        data class Packet(override val consumed: Int, val packet: LinkPacket) : ConsumeResult()
        data class Skip(override val consumed: Int) : ConsumeResult()
    }

    fun encodeCsm(messageId: Int, parameters: Map<Int, ByteArray> = emptyMap()): ByteArray {
        var paramsSize = 0
        for (value in parameters.values) {
            paramsSize += 4 + value.size
        }
        val total = 6 + paramsSize
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putShort(CSM_START.toShort())
        out.putShort(total.toShort())
        out.putShort(messageId.toShort())
        for ((id, value) in parameters) {
            out.putShort((4 + value.size).toShort())
            out.putShort(id.toShort())
            out.put(value)
        }
        return out.array()
    }

    fun tryParseCsm(body: ByteArray): ControlMessage? {
        if (body.size < 6) return null
        if ((body[0].toInt() and 0xFF) != 0x40 || (body[1].toInt() and 0xFF) != 0x40) return null
        val declared =
            ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        if (declared != body.size) return null
        val messageId =
            ((body[4].toInt() and 0xFF) shl 8) or (body[5].toInt() and 0xFF)
        val params = linkedMapOf<Int, ByteArray>()
        var pos = 6
        while (pos + 4 <= body.size) {
            val size =
                ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            val id =
                ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)
            if (size < 4 || pos + size > body.size) break
            params[id] = body.copyOfRange(pos + 4, pos + size)
            pos += size
        }
        return ControlMessage(messageId, params)
    }

    fun utf8Param(value: String): ByteArray =
        (value + "\u0000").toByteArray(Charsets.UTF_8)

    fun u16ListParam(ids: IntArray): ByteArray {
        val out = ByteBuffer.allocate(ids.size * 2).order(ByteOrder.BIG_ENDIAN)
        for (id in ids) out.putShort(id.toShort())
        return out.array()
    }
}
