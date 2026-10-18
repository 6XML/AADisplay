package io.github.nitsuya.aa.display.carplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.HkdfSha512
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Audi / MIB3 media audio: AirPlay stream **type 102** AAC-LC (CSM audi.11 path).
 *
 * Wire matches CSM [AudioRtpSender]: RTP PT=102, ChaCha20-Poly1305 over the AAC
 * access unit, AAD = RTP timestamp+SSRC, trailing 8-byte LE nonce.
 *
 * Cadence is locked to 1024 samples / sampleRate (≈21.3 ms @ 48 kHz). Bursting
 * AUs as fast as the encoder produced them (plus zero-padding short PCM pulls)
 * made the HU jitter buffer thrash → audible 杂音 even though the stream played.
 */
class CarPlayAacAudioEgress(
    private val host: InetAddress,
    private val dataPort: Int,
    private val controlPort: Int,
    private val key: ByteArray,
    private val sampleRate: Int,
    private val formatBit: Long,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var txSocket: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            CoreApi.setCarPlayAudioEgressEnabled(true)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "setCarPlayAudioEgressEnabled(true) failed", e)
        }
        worker = thread(name = "carplay-audio-aac", isDaemon = true) { pump() }
        Log.i(
            CarPlayConsts.TAG,
            "CarPlay AAC media TX → $host:$dataPort control=$controlPort " +
                "rate=$sampleRate format=0x${formatBit.toString(16)}",
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            txSocket?.close()
        } catch (_: Throwable) {
        }
        txSocket = null
        worker?.interrupt()
        worker = null
        Log.i(CarPlayConsts.TAG, "CarPlay AAC media TX stopped")
    }

    private fun pump() {
        val socket = CarPlayAccessoryNet.openUdp()
        txSocket = socket
        val target = InetSocketAddress(host, dataPort)
        val encoder = AacEncoder(sampleRate, CHANNELS, BITRATE)
        if (!encoder.start()) {
            Log.e(CarPlayConsts.TAG, "AAC encoder start failed — no media audio")
            running.set(false)
            socket.close()
            return
        }
        // Drop capture filled before TX arm so the first AU is live.
        repeat(8) {
            val got = try {
                CoreApi.pullCarPlayAudio(64 * 1024)
            } catch (_: Throwable) {
                ByteArray(0)
            }
            if (got.isEmpty()) return@repeat
        }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val rtp = ByteArray(12)
        val aad = ByteArray(8)
        var sequence = 0
        // CSM increments before first seal → first nonce counter = 1.
        var nonceCounter = 1L
        var packets = 0
        var pulled = 0L
        val ssrc = 0
        val frameBytes = SAMPLES_PER_FRAME * CHANNELS * 2
        val pullBuf = ByteArray(frameBytes * 2)
        val pendingAus = ArrayDeque<ByteArray>()
        val frameNs = SAMPLES_PER_FRAME * 1_000_000_000L / sampleRate
        var nextSendAt = System.nanoTime()
        CarPlayMediaClock.ensureRunning()
        // One step of exactly 1024 samples per sent AU. Deriving this from
        // microseconds ((n * 1024 * 1e6 / rate) * rate / 1e6) truncates to 1023
        // @ 48 kHz, so the HU jitter buffer slips ~1 sample/frame and after a
        // minute or two underruns into 杂音 then silence.
        var rtpTimestamp = CarPlayMediaClock.rtpTimestamp(sampleRate)
        try {
            while (running.get()) {
                // Pace before encode. Encoding first ate the 21ms slot, the thread
                // stayed >1 frame late, and dropping those AUs opened a hole. Audi
                // then keeps the mixer silent while RTP continues.
                val now = System.nanoTime()
                val sleepNs = nextSendAt - now
                if (sleepNs > 0L) {
                    val ms = sleepNs / 1_000_000L
                    val ns = (sleepNs % 1_000_000L).toInt()
                    Thread.sleep(ms, ns)
                } else if (sleepNs < -frameNs * 8) {
                    nextSendAt = System.nanoTime()
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay AAC cadence realign behindMs=${-sleepNs / 1_000_000L} " +
                            "packet=$packets q=${pendingAus.size}",
                    )
                }

                if (pendingAus.size < 2) {
                    val got = try {
                        CoreApi.pullCarPlayAudio(pullBuf.size)
                    } catch (_: Throwable) {
                        ByteArray(0)
                    }
                    if (got.isNotEmpty()) {
                        pulled += got.size
                        for (au in encoder.encode(got, got.size)) {
                            pendingAus.addLast(au)
                        }
                    }
                }
                while (pendingAus.size > MAX_QUEUED_AUS) {
                    pendingAus.removeFirst()
                }

                if (pendingAus.isEmpty()) {
                    Thread.sleep(2)
                    continue
                }

                val au = pendingAus.removeFirst()
                var payload = stripAdtsIfPresent(au)
                if (payload.size > MAX_AU) {
                    Log.w(CarPlayConsts.TAG, "AAC AU too large ${payload.size}B — drop")
                    nextSendAt += frameNs
                    continue
                }
                val ts = rtpTimestamp
                rtpTimestamp = (rtpTimestamp + SAMPLES_PER_FRAME) and 0x7fffffff
                rtp[0] = 0x80.toByte()
                rtp[1] = RTP_PT.toByte()
                rtp[2] = ((sequence ushr 8) and 0xff).toByte()
                rtp[3] = (sequence and 0xff).toByte()
                rtp[4] = ((ts ushr 24) and 0xff).toByte()
                rtp[5] = ((ts ushr 16) and 0xff).toByte()
                rtp[6] = ((ts ushr 8) and 0xff).toByte()
                rtp[7] = (ts and 0xff).toByte()
                rtp[8] = ((ssrc ushr 24) and 0xff).toByte()
                rtp[9] = ((ssrc ushr 16) and 0xff).toByte()
                rtp[10] = ((ssrc ushr 8) and 0xff).toByte()
                rtp[11] = (ssrc and 0xff).toByte()
                System.arraycopy(rtp, 4, aad, 0, 8)
                val nonce = ByteBuffer.allocate(12)
                var nc = nonceCounter
                for (i in 0 until 8) {
                    nonce.put(4 + i, (nc and 0xff).toByte())
                    nc = nc ushr 8
                }
                nonceCounter++
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce.array()),
                )
                cipher.updateAAD(aad)
                val sealed = cipher.doFinal(payload)
                val datagram = ByteArray(12 + sealed.size + 8)
                System.arraycopy(rtp, 0, datagram, 0, 12)
                System.arraycopy(sealed, 0, datagram, 12, sealed.size)
                nonce.position(4)
                for (i in 0 until 8) {
                    datagram[12 + sealed.size + i] = nonce.get()
                }
                socket.send(DatagramPacket(datagram, datagram.size, target))
                sequence = (sequence + 1) and 0xffff
                packets++
                nextSendAt += frameNs
                if (packets <= 5 || packets % 500 == 0) {
                    val head = payload.take(4).joinToString("") { "%02x".format(it) }
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay AAC packet=$packets au=${payload.size}B " +
                            "wire=${datagram.size}B ts=$ts head=$head " +
                            "pulled=${pulled / 1024}KB q=${pendingAus.size}",
                    )
                }
            }
        } catch (e: Throwable) {
            if (running.get()) Log.w(CarPlayConsts.TAG, "CarPlay AAC TX ended", e)
        } finally {
            encoder.close()
            if (txSocket === socket) txSocket = null
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            Log.i(CarPlayConsts.TAG, "CarPlay AAC TX thread done packets=$packets")
        }
    }

    /** MediaCodec sometimes prepends a 7/9-byte ADTS header; CarPlay wants raw AAC. */
    private fun stripAdtsIfPresent(au: ByteArray): ByteArray {
        if (au.size < 7) return au
        val b0 = au[0].toInt() and 0xff
        val b1 = au[1].toInt() and 0xff
        if (b0 != 0xff || (b1 and 0xf0) != 0xf0) return au
        val protectionAbsent = (b1 and 0x01) != 0
        val hdr = if (protectionAbsent) 7 else 9
        if (au.size <= hdr) return au
        return au.copyOfRange(hdr, au.size)
    }

    private class AacEncoder(
        private val sampleRate: Int,
        private val channels: Int,
        private val bitRate: Int,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var codec: MediaCodec? = null
        private var carry = ByteArray(0)
        private var queuedSamples = 0L
        private var closed = false

        fun start(): Boolean {
            return try {
                try {
                    startWithProfile(true)
                } catch (_: Throwable) {
                    release()
                    startWithProfile(false)
                }
                Log.i(
                    CarPlayConsts.TAG,
                    "AAC encoder ${codec?.name} rate=$sampleRate ch=$channels br=$bitRate",
                )
                true
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "AAC encoder start failed", e)
                close()
                false
            }
        }

        private fun startWithProfile(lc: Boolean) {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            // Raw AAC access units (no ADTS). CarPlay type-102 expects this.
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (lc) {
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
        }

        fun encode(pcm: ByteArray, length: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            val c = codec ?: return out
            if (closed || length <= 0) return out
            var buf = pcm
            var len = length
            if (carry.isNotEmpty()) {
                val merged = ByteArray(carry.size + len)
                System.arraycopy(carry, 0, merged, 0, carry.size)
                System.arraycopy(pcm, 0, merged, carry.size, len)
                carry = ByteArray(0)
                buf = merged
                len = merged.size
            }
            val frameBytes = channels * 2
            // Only feed whole PCM frames; keep the remainder for the next call.
            val usable = len - (len % frameBytes)
            if (usable < len) {
                carry = buf.copyOfRange(usable, len)
            }
            var off = 0
            while (off < usable) {
                val inIdx = try {
                    // Must not block the RTP pacer. A 10s wait here starves the HU
                    // jitter buffer (underrun → 杂音 → silence) while PCM piles up.
                    c.dequeueInputBuffer(2_000L)
                } catch (_: Throwable) {
                    break
                }
                if (inIdx < 0) {
                    // Prepend leftover usable bytes back onto carry.
                    val left = buf.copyOfRange(off, usable)
                    carry = if (carry.isEmpty()) left else left + carry
                    break
                }
                val input = c.getInputBuffer(inIdx) ?: break
                input.clear()
                // Prefer one AAC frame (1024 samples) per queue — matches CSM spf=1024.
                val want = minOf(input.remaining(), usable - off, SAMPLES_PER_FRAME * frameBytes)
                val n = want - (want % frameBytes)
                if (n <= 0) {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs(), 0)
                    break
                }
                input.put(buf, off, n)
                c.queueInputBuffer(inIdx, 0, n, ptsUs(), 0)
                queuedSamples += n / frameBytes
                off += n
            }
            drain(out)
            return out
        }

        private fun drain(out: MutableList<ByteArray>) {
            val c = codec ?: return
            while (!closed) {
                val outIdx = try {
                    c.dequeueOutputBuffer(bufferInfo, 0L)
                } catch (_: Throwable) {
                    return
                }
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outIdx < 0 -> continue
                    else -> {
                        try {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                continue
                            }
                            val output = c.getOutputBuffer(outIdx) ?: continue
                            if (bufferInfo.size <= 0) continue
                            val au = ByteArray(bufferInfo.size)
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            output.get(au)
                            out.add(au)
                        } finally {
                            c.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }
        }

        private fun ptsUs(): Long = (queuedSamples * 1_000_000L) / sampleRate

        fun close() {
            if (closed) return
            closed = true
            release()
        }

        private fun release() {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            codec = null
        }
    }

    companion object {
        private const val CHANNELS = 2
        private const val BITRATE = 192_000
        private const val SAMPLES_PER_FRAME = 1024
        private const val RTP_PT = 102
        private const val MAX_AU = 1400
        /** ~85 ms @ 48 kHz. Above this, stop pulling so encode cannot outrun the pacer. */
        private const val MAX_QUEUED_AUS = 4

        const val AUDIO_FORMAT_AAC_LC_44100_STEREO = 4_194_304L // 0x400000
        const val AUDIO_FORMAT_AAC_LC_48000_STEREO = 8_388_608L // 0x800000
        const val STREAM_TYPE = 102L
        const val AUDIO_LATENCY_MS = 80L

        fun open(
            host: InetAddress,
            setupResponse: RtspMessage,
            sharedSecret: ByteArray,
            streamConnectionId: Long,
            sampleRate: Int,
            formatBit: Long,
        ): CarPlayAacAudioEgress? {
            val dict = BinaryPlist.asDict(BinaryPlist.decode(setupResponse.body))
            val streams = dict?.get("streams") as? List<*>
            val first = BinaryPlist.asDict(streams?.firstOrNull())
            val dataPort = BinaryPlist.intOf(first?.get("dataPort"))?.toInt()?.and(0xffff) ?: 0
            val controlPort = BinaryPlist.intOf(first?.get("controlPort"))?.toInt()?.and(0xffff) ?: 0
            if (dataPort <= 0) {
                Log.w(CarPlayConsts.TAG, "AAC audio SETUP missing dataPort dict=$dict")
                return null
            }
            val key = HkdfSha512.derive(
                sharedSecret,
                "DataStream-Salt${java.lang.Long.toUnsignedString(streamConnectionId)}",
                "DataStream-Output-Encryption-Key",
                32,
            )
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay AAC SETUP ok dataPort=$dataPort controlPort=$controlPort " +
                    "format=0x${formatBit.toString(16)} rate=$sampleRate key=${key.size}B",
            )
            return CarPlayAacAudioEgress(host, dataPort, controlPort, key, sampleRate, formatBit)
        }
    }
}
