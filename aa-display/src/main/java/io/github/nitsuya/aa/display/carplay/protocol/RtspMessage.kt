package io.github.nitsuya.aa.display.carplay.protocol

/**
 * Minimal RTSP request/response used by wireless CarPlay control.
 */
data class RtspMessage(
    val firstLine: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
) {
    val isRequest: Boolean
        get() = !firstLine.startsWith("RTSP/", ignoreCase = true)

    val method: String?
        get() = if (isRequest) firstLine.substringBefore(' ').uppercase() else null

    val cSeq: Int
        get() = headers.entries.firstOrNull { it.key.equals("CSeq", ignoreCase = true) }
            ?.value?.trim()?.toIntOrNull() ?: -1

    fun toBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append(firstLine).append("\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        if (body.isEmpty()) return head
        return head + body
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtspMessage) return false
        return firstLine == other.firstLine && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = firstLine.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    companion object {
        fun parse(raw: ByteArray): RtspMessage? {
            // Headers are ASCII; body may be binary plist — split on bytes, not UTF-8 round-trip.
            var headerEnd = -1
            for (i in 0..raw.size - 4) {
                if (raw[i] == '\r'.code.toByte() &&
                    raw[i + 1] == '\n'.code.toByte() &&
                    raw[i + 2] == '\r'.code.toByte() &&
                    raw[i + 3] == '\n'.code.toByte()
                ) {
                    headerEnd = i
                    break
                }
            }
            if (headerEnd < 0) return null
            val headerText = raw.copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty() || lines[0].isBlank()) return null
            val headers = linkedMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val bodyStart = headerEnd + 4
            val contentLen = headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.trim()?.toIntOrNull()
            val body = when {
                contentLen != null && contentLen >= 0 -> {
                    val end = minOf(raw.size, bodyStart + contentLen)
                    if (bodyStart >= raw.size) ByteArray(0) else raw.copyOfRange(bodyStart, end)
                }
                bodyStart < raw.size -> raw.copyOfRange(bodyStart, raw.size)
                else -> ByteArray(0)
            }
            return RtspMessage(lines[0], headers, body)
        }

        fun ok(cSeq: Int, extra: Map<String, String> = emptyMap()): RtspMessage {
            val headers = linkedMapOf(
                "CSeq" to cSeq.toString(),
                "Server" to "AADisplay/CarPlay",
            )
            headers.putAll(extra)
            return RtspMessage("RTSP/1.0 200 OK", headers)
        }
    }
}
