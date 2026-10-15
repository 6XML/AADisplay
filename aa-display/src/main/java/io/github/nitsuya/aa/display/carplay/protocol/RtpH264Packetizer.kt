package io.github.nitsuya.aa.display.carplay.protocol

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 6184 H.264 over RTP: single NAL or FU-A fragments.
 * Access units may contain Annex-B start codes or length-prefixed NALs from MediaCodec.
 */
class RtpH264Packetizer(
    private val payloadType: Int = 96,
    private val ssrc: Int = 0xAAD1501A.toInt(),
    private val maxPayload: Int = 1200,
) {
    private val seq = AtomicInteger((System.nanoTime() and 0xFFFF).toInt())

    fun packetize(accessUnit: ByteArray, ptsUs: Long, markerOnLast: Boolean = true): List<ByteArray> {
        val timestamp = ((ptsUs * 90) / 1000).toInt() // 90 kHz
        val nals = splitNals(accessUnit)
        if (nals.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>()
        for ((index, nal) in nals.withIndex()) {
            val lastNal = index == nals.lastIndex
            out += packetizeNal(nal, timestamp, markerOnLast && lastNal)
        }
        return out
    }

    private fun packetizeNal(nal: ByteArray, timestamp: Int, marker: Boolean): List<ByteArray> {
        if (nal.isEmpty()) return emptyList()
        if (nal.size + 12 <= maxPayload) {
            return listOf(buildRtp(nal, timestamp, marker))
        }
        // FU-A
        val nalHeader = nal[0].toInt() and 0xFF
        val nri = nalHeader and 0x60
        val type = nalHeader and 0x1F
        val payload = nal.copyOfRange(1, nal.size)
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val room = maxPayload - 12 - 2 // RTP + FU indicator/header
            val len = minOf(room, payload.size - offset)
            val last = offset + len >= payload.size
            val fuIndicator = (nri or 28).toByte() // type 28 = FU-A
            var fuHeader = type
            if (first) fuHeader = fuHeader or 0x80
            if (last) fuHeader = fuHeader or 0x40
            val body = ByteArray(2 + len)
            body[0] = fuIndicator
            body[1] = fuHeader.toByte()
            System.arraycopy(payload, offset, body, 2, len)
            chunks += buildRtp(body, timestamp, marker && last)
            offset += len
            first = false
        }
        return chunks
    }

    private fun buildRtp(payload: ByteArray, timestamp: Int, marker: Boolean): ByteArray {
        val seqNum = seq.getAndIncrement() and 0xFFFF
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte() // V=2
        packet[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        packet[2] = (seqNum shr 8).toByte()
        packet[3] = seqNum.toByte()
        packet[4] = (timestamp shr 24).toByte()
        packet[5] = (timestamp shr 16).toByte()
        packet[6] = (timestamp shr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = ssrc.toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }

    companion object {
        fun splitNals(accessUnit: ByteArray): List<ByteArray> {
            if (accessUnit.isEmpty()) return emptyList()
            // Annex-B
            if (hasStartCode(accessUnit)) {
                val nals = ArrayList<ByteArray>()
                var i = 0
                while (i < accessUnit.size) {
                    val sc = startCodeLen(accessUnit, i)
                    if (sc == 0) {
                        i++
                        continue
                    }
                    val start = i + sc
                    var next = start
                    while (next < accessUnit.size) {
                        val nsc = startCodeLen(accessUnit, next)
                        if (nsc > 0) break
                        next++
                    }
                    if (start < next) nals += accessUnit.copyOfRange(start, next)
                    i = next
                }
                return nals
            }
            // AVCC length-prefixed
            val nals = ArrayList<ByteArray>()
            var i = 0
            while (i + 4 <= accessUnit.size) {
                val len = ByteBuffer.wrap(accessUnit, i, 4).int
                i += 4
                if (len <= 0 || i + len > accessUnit.size) break
                nals += accessUnit.copyOfRange(i, i + len)
                i += len
            }
            return if (nals.isEmpty()) listOf(accessUnit) else nals
        }

        private fun hasStartCode(data: ByteArray): Boolean =
            startCodeLen(data, 0) > 0 ||
                (data.size > 4 && startCodeLen(data, 1) > 0)

        private fun startCodeLen(data: ByteArray, i: Int): Int {
            if (i + 3 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                return 4
            }
            if (i + 2 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            ) {
                return 3
            }
            return 0
        }
    }
}
