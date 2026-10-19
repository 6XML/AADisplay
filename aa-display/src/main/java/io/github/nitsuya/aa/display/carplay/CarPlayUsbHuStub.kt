package io.github.nitsuya.aa.display.carplay

import android.util.Log
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lab-only accessory-role (Vehicle / USBHost) iAP2 responder for the wired path.
 *
 * When [enabled] (file `/data/local/tmp/aad_iap2_hu_stub` present), phone outbound
 * CSM frames are answered locally so M2 can advance past `0x1D00` without a Mac
 * CarPlay Simulator / `host_iap2_hu_smoke.py` usbmux peer. `0x4301` advertises
 * [airplayHost]:[airplayPort] (default loopback + this process's RTSP listen).
 *
 * Mirrors `archive/usb-carplay/usb-gadget/scripts/host_iap2_hu_smoke.py` / ncm_carplayd IdentifyInfo.
 */
class CarPlayUsbHuStub(
    private val airplayHost: String,
    private val airplayPort: Int,
    private val onHuBytes: (ByteArray) -> Unit,
) {
    private val mySeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var peerSeq = 0
    private var session = 0x01
    private val saw1d00 = AtomicBoolean(false)
    private val sawAa00 = AtomicBoolean(false)
    private val saw4300 = AtomicBoolean(false)
    private val pending = java.io.ByteArrayOutputStream()

    fun onPhoneBytes(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(pending) {
            pending.write(chunk)
            drainLocked()
        }
    }

    private fun drainLocked() {
        val data = pending.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val result = Iap2Framing.tryConsume(data, offset, data.size - offset) ?: break
            when (result) {
                is Iap2Framing.ConsumeResult.Detect -> offset += result.consumed
                is Iap2Framing.ConsumeResult.Skip -> offset += result.consumed
                is Iap2Framing.ConsumeResult.Packet -> {
                    offset += result.consumed
                    handlePacket(result.packet)
                }
            }
        }
        pending.reset()
        if (offset < data.size) {
            pending.write(data, offset, data.size - offset)
        }
    }

    private fun handlePacket(packet: Iap2Framing.LinkPacket) {
        peerSeq = packet.seq
        if (packet.sessionId != 0) session = packet.sessionId
        if (packet.payload.isEmpty()) return
        val msg = Iap2Framing.tryParseCsm(packet.payload) ?: return
        Log.i(
            CarPlayConsts.TAG,
            "USB HU-stub RX 0x${"%04x".format(msg.messageId)}",
        )
        when (msg.messageId) {
            Iap2Csm.START_IDENTIFICATION -> {
                if (!saw1d00.compareAndSet(false, true)) return
                replyCsm(buildIdentInfo())
            }
            Iap2Csm.REQUEST_AUTH_CERTIFICATE -> {
                if (!sawAa00.compareAndSet(false, true)) return
                replyCsm(buildAuthCert())
            }
            Iap2Csm.REQUEST_AUTH_CHALLENGE -> {
                val challenge = msg.parameters[0x0000] ?: ByteArray(32)
                replyCsm(
                    Iap2Framing.encodeCsm(
                        Iap2Csm.AUTH_RESPONSE,
                        mapOf(0x0000 to ByteArray(challenge.size) { i -> (challenge[i].toInt() xor 0x5A).toByte() }),
                    ),
                )
            }
            Iap2Csm.CARPLAY_AVAILABILITY -> {
                if (!saw4300.compareAndSet(false, true)) return
                replyCsm(buildCarPlayStart())
                Log.i(
                    CarPlayConsts.TAG,
                    "USB HU-stub TX 0x4301 $airplayHost:$airplayPort",
                )
            }
            else -> { /* ignore Accepted / AA05 / etc. */ }
        }
    }

    private fun replyCsm(csm: ByteArray) {
        val seq = mySeq.incrementAndGet() and 0xFF
        val frame = Iap2Framing.encodeLinkPacket(
            control = Iap2Framing.CONTROL_ACK,
            seq = seq,
            ack = peerSeq,
            sessionId = session,
            payload = csm,
        )
        onHuBytes(frame)
    }

    private fun buildIdentInfo(): ByteArray {
        val name = "AAD-HU-Stub"
        val sent = u16List(0x4154, 0x5000, 0x5002, 0x5200, 0x5203, 0x4301)
        val rcv = u16List(
            0x4E0A, 0x4E0B, 0x4E0E, 0x4155, 0x5001, 0x5201, 0x5202,
            0x4300, 0xAA00, 0xAA02, 0xAA05, 0x1D00, 0x1D02,
        )
        val usb = Iap2Csm.encodeGroup(
            linkedMapOf(
                0x0000 to u16(1),
                0x0001 to Iap2Framing.utf8Param("IAP2-USBHost"),
                0x0002 to ByteArray(0),
                0x0003 to byteArrayOf(0x02),
                0x0004 to ByteArray(0),
            ),
        )
        val veh = Iap2Csm.encodeGroup(
            linkedMapOf(
                0x0000 to u16(2),
                0x0001 to Iap2Framing.utf8Param(name),
                0x0002 to byteArrayOf(0x00),
                0x0006 to Iap2Framing.utf8Param(name),
            ),
        )
        return Iap2Framing.encodeCsm(
            Iap2Csm.IDENTIFICATION_INFORMATION,
            linkedMapOf(
                0x0000 to Iap2Framing.utf8Param(name),
                0x0001 to Iap2Framing.utf8Param("AAD-USB-HU"),
                0x0002 to Iap2Framing.utf8Param("AADisplay"),
                0x0003 to Iap2Framing.utf8Param("2026.10.19.stub"),
                0x0004 to Iap2Framing.utf8Param("1.0.0"),
                0x0005 to Iap2Framing.utf8Param("1.0.0"),
                0x0006 to sent,
                0x0007 to rcv,
                0x0008 to byteArrayOf(0x02),
                0x0009 to u16(0),
                0x000C to Iap2Framing.utf8Param("en"),
                0x000D to Iap2Framing.utf8Param("en"),
                0x0010 to usb,
                0x0014 to veh,
            ),
        )
    }

    private fun buildAuthCert(): ByteArray {
        val leaf = ByteArray(256).also { it[0] = 0x30; it[1] = 0x82.toByte() }
        return Iap2Framing.encodeCsm(
            Iap2Csm.AUTH_CERTIFICATE,
            linkedMapOf(0x0000 to leaf, 0x0001 to byteArrayOf(0x00)),
        )
    }

    private fun buildCarPlayStart(): ByteArray {
        val wired = Iap2Csm.encodeGroup(
            linkedMapOf(0x0000 to Iap2Framing.utf8Param(airplayHost)),
        )
        val portBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(airplayPort).array()
        return Iap2Framing.encodeCsm(
            Iap2Csm.CARPLAY_START_SESSION,
            linkedMapOf(
                0x0000 to wired,
                0x0002 to portBytes,
                0x0003 to Iap2Framing.utf8Param("AA:BB:CC:DD:EE:01"),
                0x0004 to ByteArray(32) { it.toByte() },
                0x0005 to Iap2Framing.utf8Param("320.17"),
            ),
        )
    }

    private fun u16(v: Int): ByteArray =
        byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u16List(vararg ids: Int): ByteArray {
        val out = ByteArray(ids.size * 2)
        var i = 0
        for (id in ids) {
            out[i++] = ((id shr 8) and 0xFF).toByte()
            out[i++] = (id and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val STUB_FLAG = "/data/local/tmp/aad_iap2_hu_stub"

        fun enabled(): Boolean =
            try {
                File(STUB_FLAG).exists()
            } catch (_: Throwable) {
                false
            }
    }
}
