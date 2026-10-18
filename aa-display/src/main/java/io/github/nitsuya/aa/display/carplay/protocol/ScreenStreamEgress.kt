package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AirPlay screen stream (type 110) over TCP [dataPort].
 *
 * Frame = 128-byte header + payload. With HKP pairing, payload is ChaCha20-Poly1305
 * (AAD = header, nonce = 4×0 + LE counter); codec-config frames stay plaintext.
 * Header 8..16 = NTP 32.32 frame time as a little-endian u64 (DiPlay ScreenCodec.senderNanos
 * reads bytes 8..15 LE — a big-endian write yields a garbage sender time).
 * Key = HKDF(pairVerify.shared, "DataStream-Salt{id}", "DataStream-Output-Encryption-Key")
 * (DiPlay CarPlayMediaEngine.outputKey / dataStreamKey).
 *
 * Writes stay synchronous + per-frame flush: an earlier async sender with coalesced flush
 * flooded DiPlay (~2MB/s) and reset the control channel ("连不上"). Periodic hitch from
 * IDR bursts is mitigated in [CarPlayVideoEncoder] (intra-refresh when available, else
 * ~10s GOP) — not by reordering the wire path.
 */
class ScreenStreamEgress private constructor(
    private val socket: Socket,
    private val out: BufferedOutputStream,
    private val cryptor: ScreenChaChaCryptor?,
    /** Coded content size (matches SPS / encoder). */
    private val width: Int,
    private val height: Int,
    /** Accessory panel size from `/info` (header floats 56/60). */
    private val displayWidth: Int,
    private val displayHeight: Int,
    /**
     * DiPlay [ScreenCodec.detectConfig] wants `0000+"avcC"+record`.
     * Stock Audi MIB3 expects a bare ISO AVCDecoderConfigurationRecord (version=1 at [0]).
     */
    private val wrapAvccFourcc: Boolean,
) {
    private val closed = AtomicBoolean(false)
    private val framesSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val configSent = AtomicBoolean(false)
    /** CSM VideoStreamSender: hold P-frames until the first IDR reaches the HU. */
    private val firstIdrSent = AtomicBoolean(false)
    private var loggedWaitingForIdr = false

    fun hasSentConfig(): Boolean = configSent.get()

    fun stats(): Pair<Long, Long> = framesSent.get() to bytesSent.get()

    /**
     * Codec config — unencrypted. DiPlay [ScreenCodec.detectConfig] requires an ISO
     * AVCDecoderConfigurationRecord (or an `avcC` fourcc wrapper); length-prefixed /
     * Annex-B SPS+PPS is misread as H265 → black screen (`shown=0`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun sendCodecConfig(parameterSets: ByteArray, timestampNs: Long): Boolean {
        if (closed.get() || parameterSets.isEmpty()) return false
        val avcc = toAvcDecoderConfigurationRecord(parameterSets) ?: run {
            Log.e(CarPlayConsts.TAG, "screen CONFIG: cannot build avcC from ${parameterSets.size}B")
            return false
        }
        val body = if (wrapAvccFourcc) {
            // DiPlay detectConfig: fourcc at index≥4.
            ByteArray(4) + "avcC".toByteArray(Charsets.US_ASCII) + avcc
        } else {
            // Stock MIB3 / airplay-rs: bare AVCDecoderConfigurationRecord.
            avcc
        }
        val header = ByteArray(HEADER_LEN)
        putU32Le(header, 0, body.size)
        header[4] = OPCODE_CONFIG
        // CSM VideoStreamSender.send(OP_CONFIG): header[6] |= 0x04 only (has-size),
        // then f32 LE width@16 / height@20. Do NOT set 0x10/"encrypted" — CONFIG body
        // stays plaintext; Audi closes if it tries to ChaCha-open bare avcC.
        header[6] = FLAG_HAS_SIZE
        // Wall-clock NTP 32.32 (epoch 1900). Relative MediaClock nanos → sec≈0 made
        // Audi discard every frame (DiPlay self-heals; stock does not).
        putWallNtp3232Le(header, 8)
        putF32Le(header, 16, width.toFloat())
        putF32Le(header, 20, height.toFloat())
        if (wrapAvccFourcc) {
            // DiPlay also fills source-rect + display floats (airplay-rs layout).
            val w = width.toFloat()
            val h = height.toFloat()
            val dw = displayWidth.toFloat()
            val dh = displayHeight.toFloat()
            putF32Le(header, 32, 0f)
            putF32Le(header, 36, 0f)
            putF32Le(header, 40, w)
            putF32Le(header, 44, h)
            putF32Le(header, 48, 0f)
            putF32Le(header, 52, 0f)
            putF32Le(header, 56, dw)
            putF32Le(header, 60, dh)
        }
        return writeFrame(header, body).also {
            if (it) {
                configSent.set(true)
                Log.i(
                    CarPlayConsts.TAG,
                    "screen CONFIG sent body=${body.size}B avcC=${avcc.size}B " +
                        "fourcc=$wrapAvccFourcc content=${width}x$height " +
                        "display=${displayWidth}x$displayHeight " +
                        "flags=0x${"%02x".format(header[6])} plaintext " +
                        "head=${body.take(12).joinToString("") { b -> "%02x".format(b) }}",
                )
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendVideo(accessUnit: ByteArray, timestampNs: Long, isKey: Boolean): Boolean {
        if (closed.get() || accessUnit.isEmpty()) return false
        val avcc = toAvcc(accessUnit)
        if (avcc.isEmpty()) return false
        // CSM: skip non-IDR until the first IDR — Audi otherwise opens a decode
        // session on P-frames and tears the control channel ~12s later.
        val idr = isKey || containsIdrNal(avcc)
        if (!firstIdrSent.get() && !idr) {
            if (!loggedWaitingForIdr) {
                loggedWaitingForIdr = true
                Log.i(CarPlayConsts.TAG, "screen waiting for first IDR; skipping P-frames")
            }
            return true
        }
        val overhead = if (cryptor != null) TAG_LEN else 0
        val header = ByteArray(HEADER_LEN)
        putU32Le(header, 0, avcc.size + overhead)
        header[4] = OPCODE_VIDEO
        // CSM leaves header[5]/[6] at 0 for VIDEO; keyframe is inferred from NALs.
        // Length field already includes +TAG when encrypted (HU detects from size).
        putWallNtp3232Le(header, 8)
        // clockIdentity at 40..48 left 0 (NTP path)
        val payload = if (cryptor != null) {
            cryptor.seal(avcc, header) ?: return false
        } else {
            avcc
        }
        return writeFrame(header, payload).also { ok ->
            if (ok && idr) firstIdrSent.set(true)
            if (ok && idr) {
                val n = framesSent.get()
                if (n <= 5 || n % 30L == 0L) {
                    Log.d(
                        CarPlayConsts.TAG,
                        "screen VIDEO keyframe au=${avcc.size} wire=${payload.size} " +
                            "frames=$n encrypt=${cryptor != null}",
                    )
                }
            }
        }
    }

    private fun writeFrame(header: ByteArray, payload: ByteArray): Boolean {
        return try {
            synchronized(out) {
                out.write(header)
                out.write(payload)
                out.flush()
            }
            framesSent.incrementAndGet()
            bytesSent.addAndGet((header.size + payload.size).toLong())
            true
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "screen dataPort write failed", e)
            close()
            false
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            out.close()
        } catch (_: Throwable) {
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        Log.i(
            CarPlayConsts.TAG,
            "screen egress closed frames=${framesSent.get()} bytes=${bytesSent.get()}",
        )
    }

    companion object {
        const val HEADER_LEN = 128
        private const val TAG_LEN = 16
        private const val OPCODE_VIDEO: Byte = 0x00
        private const val OPCODE_CONFIG: Byte = 0x01
        /** CSM VideoStreamSender: CONFIG sets this bit then writes width/height floats. */
        private const val FLAG_HAS_SIZE: Byte = 0x04

        fun open(
            host: InetAddress,
            dataPort: Int,
            sessionKey: ByteArray?,
            streamConnectionId: Long,
            width: Int,
            height: Int,
            displayWidth: Int = width,
            displayHeight: Int = height,
            /** false = bare avcC (Audi MIB3); true = DiPlay fourcc wrapper. */
            wrapAvccFourcc: Boolean = true,
        ): ScreenStreamEgress? {
            if (dataPort <= 0 || dataPort > 65535) {
                Log.e(CarPlayConsts.TAG, "screen dataPort invalid=$dataPort")
                return null
            }
            return try {
                val socket = io.github.nitsuya.aa.display.carplay.CarPlayAccessoryNet.openTcp()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, dataPort), 10_000)
                socket.soTimeout = 0
                val cryptor = if (sessionKey != null && sessionKey.isNotEmpty()) {
                    ScreenChaChaCryptor.fromSessionKey(sessionKey, streamConnectionId)
                } else {
                    Log.w(CarPlayConsts.TAG, "screen dataPort plaintext (no sessionKey)")
                    null
                }
                Log.i(
                    CarPlayConsts.TAG,
                    "screen dataPort connected $host:$dataPort " +
                        "streamId=$streamConnectionId encrypt=${cryptor != null} " +
                        "content=${width}x$height display=${displayWidth}x$displayHeight " +
                        "fourcc=$wrapAvccFourcc",
                )
                ScreenStreamEgress(
                    socket,
                    BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                    cryptor,
                    width,
                    height,
                    displayWidth,
                    displayHeight,
                    wrapAvccFourcc,
                )
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "screen dataPort connect failed $host:$dataPort", e)
                null
            }
        }

        /** Length-prefixed NALs for VIDEO frames (ChaCha plaintext). */
        fun toAvcc(accessUnit: ByteArray): ByteArray {
            val nals = RtpH264Packetizer.splitNals(accessUnit)
            if (nals.isEmpty()) return accessUnit
            if (!hasAnnexBStartCode(accessUnit) && looksLikeLengthPrefixed(accessUnit)) {
                return accessUnit
            }
            val out = ByteArray(nals.sumOf { 4 + it.size })
            var o = 0
            for (nal in nals) {
                out[o] = ((nal.size ushr 24) and 0xff).toByte()
                out[o + 1] = ((nal.size ushr 16) and 0xff).toByte()
                out[o + 2] = ((nal.size ushr 8) and 0xff).toByte()
                out[o + 3] = (nal.size and 0xff).toByte()
                System.arraycopy(nal, 0, out, o + 4, nal.size)
                o += 4 + nal.size
            }
            return out
        }

        /**
         * ISO-14496-15 AVCDecoderConfigurationRecord from MediaCodec CODEC_CONFIG
         * (Annex-B, length-prefixed, or already-avcC).
         */
        fun toAvcDecoderConfigurationRecord(parameterSets: ByteArray): ByteArray? {
            if (looksLikeAvcC(parameterSets)) return parameterSets
            val nals = RtpH264Packetizer.splitNals(parameterSets)
            val sps = nals.firstOrNull { (it.firstOrNull()?.toInt()?.and(0x1f) ?: -1) == 7 }
            val pps = nals.firstOrNull { (it.firstOrNull()?.toInt()?.and(0x1f) ?: -1) == 8 }
            if (sps == null || pps == null || sps.size < 4) {
                Log.w(
                    CarPlayConsts.TAG,
                    "screen CONFIG missing SPS/PPS nals=${nals.size} " +
                        "types=${nals.map { it.firstOrNull()?.toInt()?.and(0x1f) }}",
                )
                return null
            }
            // configurationVersion=1, profile/compat/level from SPS, lengthSizeMinusOne=3,
            // numOfSPS=1 | 0xE0, sps u16be, numOfPPS=1, pps u16be — DiPlay test avcC shape.
            val out = ByteArray(11 + sps.size + pps.size)
            out[0] = 1
            out[1] = sps[1]
            out[2] = sps[2]
            out[3] = sps[3]
            out[4] = 0xFF.toByte()
            out[5] = 0xE1.toByte()
            out[6] = ((sps.size ushr 8) and 0xff).toByte()
            out[7] = (sps.size and 0xff).toByte()
            System.arraycopy(sps, 0, out, 8, sps.size)
            var o = 8 + sps.size
            out[o++] = 1
            out[o++] = ((pps.size ushr 8) and 0xff).toByte()
            out[o++] = (pps.size and 0xff).toByte()
            System.arraycopy(pps, 0, out, o, pps.size)
            return out
        }

        private fun hasAnnexBStartCode(data: ByteArray): Boolean {
            if (data.size < 4) return false
            return (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) ||
                (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte())
        }

        private fun looksLikeLengthPrefixed(data: ByteArray): Boolean {
            if (data.size < 5) return false
            val len = ByteBuffer.wrap(data, 0, 4).int
            return len > 0 && len + 4 <= data.size
        }

        /** CSM VideoStreamSender.containsIdrNal — length-prefixed NALs, type 5. */
        private fun containsIdrNal(avcc: ByteArray): Boolean {
            var i = 0
            while (i + 4 <= avcc.size) {
                val len = ByteBuffer.wrap(avcc, i, 4).int
                if (len <= 0 || i + 4 + len > avcc.size) return false
                if ((avcc[i + 4].toInt() and 0x1f) == 5) return true
                i += 4 + len
            }
            return false
        }

        /** DiPlay ScreenCodec.looksLikeAvcC — raw ISO record starting with version=1. */
        private fun looksLikeAvcC(payload: ByteArray): Boolean {
            if (payload.size < 9) return false
            if (payload[0].toInt() != 1) return false
            if ((payload[5].toInt() and 0x1f) < 1) return false
            val spsLength = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
            if (8 + spsLength > payload.size) return false
            return (payload[8].toInt() and 0x1f) == 7
        }

        private fun putU32Le(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v ushr 8) and 0xff).toByte()
            buf[off + 2] = ((v ushr 16) and 0xff).toByte()
            buf[off + 3] = ((v ushr 24) and 0xff).toByte()
        }

        /**
         * Little-endian NTP 32.32 from wall clock (epoch 1900), matching CarPlayTiming /
         * airplay-rs NetworkClock — not relative MediaClock nanos.
         */
        private fun putWallNtp3232Le(buf: ByteArray, off: Int) {
            val wallMs = System.currentTimeMillis()
            val sec = wallMs / 1000L + NTP_UNIX_DELTA
            val frac = ((wallMs % 1000L) * 0x100000000L) / 1000L
            var raw = (sec shl 32) or (frac and 0xffffffffL)
            for (i in 0 until 8) {
                buf[off + i] = (raw and 0xff).toByte()
                raw = raw ushr 8
            }
        }

        private const val NTP_UNIX_DELTA = 2_208_988_800L

        private fun putF32Le(buf: ByteArray, off: Int, v: Float) {
            val bits = java.lang.Float.floatToIntBits(v)
            putU32Le(buf, off, bits)
        }
    }
}

/** Per-frame ChaCha20-Poly1305 for screen payloads (header as AAD). */
class ScreenChaChaCryptor(
    private val key: ByteArray,
) {
    private val counter = AtomicLong(0)

    fun seal(plaintext: ByteArray, headerAad: ByteArray): ByteArray? {
        val nonce = ControlChannelCrypto.nonceFromCounter(counter.getAndIncrement())
        return ControlChannelCrypto.chachaSeal(key, nonce, headerAad, plaintext)
    }

    companion object {
        fun fromSessionKey(sessionKey: ByteArray, streamConnectionId: Long): ScreenChaChaCryptor {
            // Unsigned decimal id — match Apple PRIu64 / airplay-rs.
            // CSM: StringBuilder.append(long) → signed decimal. IDs are masked to
            // Long.MAX_VALUE so signed == unsigned; keep the same formatter.
            val idStr = streamConnectionId.toString()
            val salt = "DataStream-Salt$idStr"
            val key = HkdfSha512.derive(
                sessionKey,
                salt,
                "DataStream-Output-Encryption-Key",
                32,
            )
            Log.i(
                CarPlayConsts.TAG,
                "screen DataStream key derived id=$idStr saltLen=${salt.length} key=${key.size}B",
            )
            return ScreenChaChaCryptor(key)
        }
    }
}
