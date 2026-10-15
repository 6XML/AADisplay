package io.github.nitsuya.aa.display.carplay

import android.util.Log
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.HkdfSha512
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay main audio (SETUP type 100) egress. v1 plays a synthesized 440 Hz tone.
 *
 * DiPlay host [com.shilapi.xcertplay.airplay.AudioStream] wire format: one UDP datagram
 * = 12-byte RTP header + ChaCha20-Poly1305 ciphertext + 16-byte tag + trailing 8-byte
 * little-endian nonce (four leading zero bytes); AAD = RTP bytes 4..12 (timestamp+SSRC).
 * Key = HKDF(pairVerify.shared, "DataStream-Salt$streamConnectionID",
 * "DataStream-Output-Encryption-Key") — same derivation as the screen DataStream.
 *
 * audioFormat 0x10 = 16 kHz mono s16 LPCM; DiPlay renders LPCM straight to AudioTrack
 * (it byte-swaps, so samples are written big-endian here).
 */
class CarPlayAudioEgress(
    private val host: InetAddress,
    private val dataPort: Int,
    val controlPort: Int,
    private val key: ByteArray,
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "carplay-audio-tx", isDaemon = true) { pump() }
        Log.i(CarPlayConsts.TAG, "CarPlay audio TX started → $host:$dataPort (control=$controlPort)")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        Log.i(CarPlayConsts.TAG, "CarPlay audio TX stopped")
    }

    private fun pump() {
        val socket = DatagramSocket()
        val target = InetSocketAddress(host, dataPort)
        val rtp = ByteArray(12)
        val ssrc = SSRC
        var sequence = 0
        var timestamp = 0
        var nonceCounter = 0L
        var sampleTime = 0.0
        val samples = ShortArray(SAMPLES_PER_PACKET)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val aad = ByteArray(8)
        var packets = 0
        try {
            while (running.get()) {
                for (i in samples.indices) {
                    val value = (AMPLITUDE * sin(2.0 * PI * FREQUENCY_HZ * sampleTime / SAMPLE_RATE))
                        .toInt()
                    samples[i] = value.coerceIn(-32_768, 32_767).toShort()
                    sampleTime += 1.0 / SAMPLE_RATE
                }
                val payload = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val v = samples[i].toInt()
                    payload[i * 2] = (v ushr 8).toByte()
                    payload[i * 2 + 1] = v.toByte()
                }
                rtp[0] = 0x80.toByte()
                rtp[1] = PAYLOAD_TYPE.toByte()
                rtp[2] = ((sequence ushr 8) and 0xff).toByte()
                rtp[3] = (sequence and 0xff).toByte()
                rtp[4] = ((timestamp ushr 24) and 0xff).toByte()
                rtp[5] = ((timestamp ushr 16) and 0xff).toByte()
                rtp[6] = ((timestamp ushr 8) and 0xff).toByte()
                rtp[7] = (timestamp and 0xff).toByte()
                rtp[8] = ((ssrc ushr 24) and 0xff).toByte()
                rtp[9] = ((ssrc ushr 16) and 0xff).toByte()
                rtp[10] = ((ssrc ushr 8) and 0xff).toByte()
                rtp[11] = (ssrc and 0xff).toByte()

                // AAD = timestamp + SSRC; nonce = 4×0 + 8-byte LE counter.
                System.arraycopy(rtp, 4, aad, 0, 8)
                val nonce = ByteBuffer.allocate(12)
                for (i in 0 until 8) {
                    nonce.put(4 + i, (nonceCounter and 0xff).toByte())
                    nonceCounter = nonceCounter ushr 8
                }
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
                timestamp = (timestamp + SAMPLES_PER_PACKET) and 0x7fffffff
                packets++
                if (packets <= 3 || packets % 500 == 0) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "CarPlay audio packet=$packets bytes=${datagram.size} ts=$timestamp",
                    )
                }
                Thread.sleep(PACKET_INTERVAL_MS)
            }
        } catch (e: Throwable) {
            if (running.get()) Log.w(CarPlayConsts.TAG, "CarPlay audio TX ended", e)
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            Log.i(CarPlayConsts.TAG, "CarPlay audio TX thread done packets=$packets")
        }
    }

    companion object {
        private const val FREQUENCY_HZ = 440.0
        private const val AMPLITUDE = 0.25
        private const val SAMPLE_RATE = 16_000
        private const val PACKET_INTERVAL_MS = 20L
        private val SAMPLES_PER_PACKET = (SAMPLE_RATE * PACKET_INTERVAL_MS / 1000L).toInt()
        private const val PAYLOAD_TYPE = 96
        private const val SSRC = 0xAAD15A01

        /**
         * Derives the audio DataStream key and answers SETUP(type=100); null when the
         * host declines or the exchange fails.
         */
        fun open(
            host: InetAddress,
            setupResponse: RtspMessage,
            sharedSecret: ByteArray,
            streamConnectionId: Long,
        ): CarPlayAudioEgress? {
            val dict = BinaryPlist.asDict(BinaryPlist.decode(setupResponse.body))
            val streams = dict?.get("streams") as? List<*>
            val first = BinaryPlist.asDict(streams?.firstOrNull())
            val dataPort = BinaryPlist.intOf(first?.get("dataPort"))?.toInt()?.and(0xffff) ?: 0
            val controlPort = BinaryPlist.intOf(first?.get("controlPort"))?.toInt()?.and(0xffff) ?: 0
            if (dataPort <= 0) {
                Log.w(CarPlayConsts.TAG, "CarPlay audio SETUP missing dataPort dict=$dict")
                return null
            }
            val key = HkdfSha512.derive(
                sharedSecret,
                "DataStream-Salt${java.lang.Long.toUnsignedString(streamConnectionId)}",
                "DataStream-Output-Encryption-Key",
                32,
            )
            val egress = CarPlayAudioEgress(host, dataPort, controlPort, key)
            Log.i(
                CarPlayConsts.TAG,
                "CarPlay audio SETUP ok dataPort=$dataPort controlPort=$controlPort key=${key.size}B",
            )
            return egress
        }

        /** AudioFormat bit: 16 kHz mono s16 LPCM (DiPlay AudioStreamCodec PCM_FORMAT[0x10]). */
        const val AUDIO_FORMAT_PCM_16K_MONO = 0x10L
    }
}
