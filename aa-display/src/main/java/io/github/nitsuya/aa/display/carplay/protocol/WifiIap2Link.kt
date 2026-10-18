package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_ACK
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_SYN
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.LinkSyncPayload
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.SESSION_TYPE_FILE_TRANSFER
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi iAP2. The accessory delivers frames on the event socket. The phone's
 * detect, SYN|ACK and control-session messages go out on the RTSP control
 * socket (`POST /command`), which is what [sendFrame] must write.
 *
 * iPhone Frida (mediaserverd): screen SETUP → detect on control → HU SYN on
 * control. Announce only after screen SETUP — never queue a detect from an
 * early event marker, or flush during the screen SETUP exchange sends it too
 * soon. After `0xAA05` the phone bursts session/language/time/name/location;
 * the HU then asks for NowPlaying / telephony / lists. Empty ACK alone makes
 * MIB3 tear AirPlay control down in ~12s with no picture.
 *
 * MIB3 NowPlaying / artwork ride this link ([NowPlayingEgress]); DiPlay keeps
 * Bluetooth [Iap2Session] for the same CSMs and only uses Wi‑Fi for detect/SYN echo.
 */
class WifiIap2Link(
    private val linkSync: ByteArray = ByteArray(0),
    /** Former Audi CSM Wi‑Fi iAP path ([CarPlayHuProfile.usesMib3WirelessPath]). */
    private val mib3Style: Boolean = false,
    private val onNowPlayingUpdates: (() -> Unit)? = null,
    private val onFileTransferDatagram: ((ByteArray) -> Unit)? = null,
    private val sendFrame: (ByteArray) -> Boolean,
) : NowPlayingEgress {
    private val running = AtomicBoolean(true)
    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private var seq = 0
    private var peerSeq = 0
    private var controlSessionId = 1
    private var fileTransferSessionId = 0x0C
    private var synAck: ByteArray? = null
    private var linkUp = false
    private var detectArmed = false
    private var detectSent = false
    private var synHandled = false
    private var identAccepted = false
    /** 0 idle, 1 waiting for 0xAA06, 2 waiting for 0xAA01, 3 waiting for 0xAA03, 4 done. */
    private var authStage = 0

    private val fileTransferQueue = ArrayDeque<ByteArray>()
    private var peerMaxOutgoing = PHONE_MAX_OUTSTANDING
    private var lastPeerAck: Int? = null
    private var unackedCount = 0

    /** True when this link is the MIB3 NowPlaying transport (not DiPlay echo-only). */
    val isMib3NowPlayingEgress: Boolean
        get() = mib3Style

    fun start() {
        Log.i(
            CarPlayConsts.TAG,
            if (mib3Style) {
                "Wi-Fi iAP2 Audi wait for screen SETUP before detect"
            } else {
                "Wi-Fi iAP2 wait for HU (no phone SYN) btLsp=${linkSync.size}B"
            },
        )
    }

    fun hasSyn(): Boolean = synchronized(lock) { synHandled }

    /**
     * Audi: arm detect after screen SETUP, then send one. Further HU detects
     * are retransmits and are ignored.
     */
    fun announce() {
        if (!mib3Style || !running.get()) return
        synchronized(lock) {
            detectArmed = true
            if (detectSent || synHandled) return
            detectSent = true
            val ok = sendFrame(Iap2Framing.DETECT_MARKER)
            Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 detect sent ok=$ok")
        }
    }

    fun close() {
        running.set(false)
        synchronized(lock) {
            fileTransferQueue.clear()
        }
    }

    fun onInbound(frame: ByteArray) {
        if (!running.get() || frame.isEmpty()) return
        synchronized(lock) {
            pending.write(frame)
            val buf = pending.toByteArray()
            var off = 0
            while (off < buf.size) {
                val result = Iap2Framing.tryConsume(buf, off, buf.size - off) ?: break
                off += result.consumed
                when (result) {
                    is Iap2Framing.ConsumeResult.Detect -> onDetect()
                    is Iap2Framing.ConsumeResult.Packet -> handle(result.packet)
                    is Iap2Framing.ConsumeResult.Skip -> Unit
                }
            }
            pending.reset()
            if (off < buf.size) pending.write(buf, off, buf.size - off)
        }
    }

    override fun sendNowPlaying(mediaItem: Map<Int, ByteArray>, playback: Map<Int, ByteArray>) {
        if (!mib3Style || !running.get()) return
        val params = linkedMapOf<Int, ByteArray>()
        if (mediaItem.isNotEmpty()) params[0] = Iap2Csm.encodeGroup(mediaItem)
        if (playback.isNotEmpty()) params[1] = Iap2Csm.encodeGroup(playback)
        if (params.isEmpty()) return
        synchronized(lock) {
            if (!synHandled) return
            sendControl(Iap2Framing.encodeCsm(Iap2Csm.NOW_PLAYING_UPDATE, params))
        }
    }

    override fun sendFileTransfer(datagram: ByteArray) {
        if (!mib3Style || !running.get()) return
        synchronized(lock) {
            if (!synHandled) return
            if (fileTransferQueue.isNotEmpty()) {
                if (fileTransferQueue.size < FILE_TRANSFER_QUEUE_CAP) {
                    fileTransferQueue += datagram
                } else {
                    Log.w(
                        CarPlayConsts.TAG,
                        "Wi-Fi iAP file-transfer queue full (${fileTransferQueue.size}) — dropping ${datagram.size}B",
                    )
                }
                return
            }
            if (unackedCount >= peerMaxOutgoing) {
                fileTransferQueue += datagram
                return
            }
            sendFileTransferLocked(datagram)
        }
    }

    private fun onDetect() {
        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 marker")
        if (mib3Style) {
            // Ignore pre-SETUP event detects — announce() after screen SETUP owns the one POST.
            if (detectArmed) announce()
            else Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 detect deferred until screen SETUP")
            return
        }
        sendFrame(Iap2Framing.DETECT_MARKER)
    }

    private fun handle(packet: Iap2Framing.LinkPacket) {
        Log.i(
            CarPlayConsts.TAG,
            "Wi-Fi iAP2 ctrl=0x${"%02x".format(packet.control)} seq=${packet.seq} " +
                "ack=${packet.ack} sess=${packet.sessionId} pay=${packet.payload.size}B",
        )
        if (packet.hasRst) {
            Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 RST")
            return
        }
        if (packet.hasSyn && packet.payload.isNotEmpty()) {
            if (mib3Style) handleAudiSyn(packet) else handleEchoSyn(packet)
            return
        }
        if (!mib3Style) {
            if (synAck != null && packet.hasAck && !linkUp) {
                linkUp = true
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 link up ack=${packet.ack}")
            }
            return
        }
        if (!synHandled) return
        if (packet.hasAck) {
            trackPeerAckLocked(packet.ack)
        }
        if (packet.payload.isEmpty()) return
        peerSeq = packet.seq
        if (packet.sessionId == fileTransferSessionId) {
            onFileTransferDatagram?.invoke(packet.payload)
            flushFileTransferQueueLocked()
            sendAckOnly()
            return
        }
        val replied = packet.sessionId == controlSessionId && handleAudiControl(packet.payload)
        if (!replied) sendAckOnly()
        flushFileTransferQueueLocked()
    }

    private fun handleEchoSyn(packet: Iap2Framing.LinkPacket) {
        val reply = synAck ?: Iap2Framing.encodeLinkPacket(
            CONTROL_SYN or CONTROL_ACK,
            seq,
            packet.seq,
            0,
            packet.payload,
        )
        if (sendFrame(reply)) synAck = reply
        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 SYN|ACK ${reply.size}B seq=$seq ack=${packet.seq}")
    }

    private fun handleAudiSyn(packet: Iap2Framing.LinkPacket) {
        peerSeq = packet.seq
        if (synHandled) {
            synAck?.let { sendFrame(it) }
            return
        }
        val peer = LinkSyncPayload.parse(packet.payload)
        if (peer == null) {
            Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 SYN unparseable (${packet.payload.size}B)")
            return
        }
        // Ensure a file-transfer session exists for NowPlaying artwork. Some HUs omit it
        // from SYN; without a negotiated FT id, SETUP is ignored / cancelled.
        val sessions = peer.sessions.toMutableList()
        if (sessions.none { it.second == SESSION_TYPE_FILE_TRANSFER }) {
            sessions += Triple(0x0C, SESSION_TYPE_FILE_TRANSFER, 2)
        }
        val phone = peer.copy(
            maxOutgoing = PHONE_MAX_OUTSTANDING,
            maxPacketLength = PHONE_MAX_PACKET,
            sessions = sessions,
        )
        controlSessionId = phone.controlSessionId()
        fileTransferSessionId =
            phone.sessions.firstOrNull { it.second == SESSION_TYPE_FILE_TRANSFER }?.first
                ?: 0x0C
        peerMaxOutgoing = phone.maxOutgoing.coerceIn(1, 255)
        seq = SecureRandom().nextInt(255) + 1
        val reply = Iap2Framing.encodeLinkPacket(
            CONTROL_SYN or CONTROL_ACK,
            seq,
            packet.seq,
            0,
            phone.pack(),
        )
        if (!sendFrame(reply)) return
        synAck = reply
        synHandled = true
        Log.i(
            CarPlayConsts.TAG,
            "Wi-Fi iAP2 SYN|ACK ${reply.size}B seq=$seq ack=${packet.seq} " +
                "sessions=${phone.sessions} ctrl=$controlSessionId ft=$fileTransferSessionId " +
                "maxOut=${phone.maxOutgoing} maxPkt=${phone.maxPacketLength}",
        )
        sendControl(Iap2Csm.empty(Iap2Csm.START_IDENTIFICATION))
        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 StartIdentification (0x1D00) sess=$controlSessionId")
    }

    /** @return true when a control reply already carried the ack. */
    private fun handleAudiControl(payload: ByteArray): Boolean {
        val msg = Iap2Framing.tryParseCsm(payload) ?: run {
            Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 control not CSM (${payload.size}B)")
            return false
        }
        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 CSM 0x${"%04x".format(msg.messageId)}")
        when (msg.messageId) {
            Iap2Csm.IDENTIFICATION_INFORMATION -> {
                if (identAccepted) return false
                identAccepted = true
                sendControl(Iap2Csm.empty(Iap2Csm.IDENTIFICATION_ACCEPTED))
                sendControl(certRequest())
                authStage = 1
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0x1D02 + 0xAA00")
            }
            Iap2Csm.IDENTIFICATION_REJECTED -> {
                Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 IdentificationRejected")
                return false
            }
            Iap2Csm.AUTH_SERIAL -> {
                if (authStage != 1) return false
                sendControl(Iap2Csm.empty(Iap2Csm.REQUEST_AUTH_CERTIFICATE))
                authStage = 2
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0xAA06 → 0xAA00")
            }
            Iap2Csm.AUTH_CERTIFICATE -> {
                if (authStage != 1 && authStage != 2) return false
                val challenge = ByteArray(20).also { SecureRandom().nextBytes(it) }
                sendControl(Iap2Csm.requestAuthChallenge(challenge))
                authStage = 3
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0xAA02 20B")
            }
            Iap2Csm.AUTH_RESPONSE -> {
                if (authStage != 3) return false
                sendControl(Iap2Csm.empty(Iap2Csm.AUTH_SUCCEEDED))
                authStage = 4
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 AuthenticationSucceeded (0xAA05)")
                sendPhoneBurst()
            }
            Iap2Csm.AUTH_FAILED -> {
                Log.w(CarPlayConsts.TAG, "Wi-Fi iAP2 AuthenticationFailed")
                return false
            }
            // requestAppLaunch (cn.audi.*) — iPhone LINK-ACKs only.
            0xEA02 -> {
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 requestAppLaunch (0xEA02) ack")
                return false
            }
            // StartCommunicationsUpdates → CommunicationsUpdate (idle SIM card).
            0x4157 -> {
                sendControl(COMMUNICATIONS_UPDATE_IDLE)
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0x4157 → 0x4158")
            }
            // StartCallStateUpdates → CallStateUpdate status=0 (no call).
            0x4154 -> {
                sendControl(
                    Iap2Framing.encodeCsm(0x4155, mapOf(0x0002 to byteArrayOf(0))),
                )
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0x4154 → 0x4155")
            }
            // StartListUpdates → empty ListUpdate (no recents / favorites).
            0x4170 -> {
                sendControl(
                    Iap2Framing.encodeCsm(
                        0x4171,
                        linkedMapOf(
                            0x0000 to byteArrayOf(0),
                            0x0002 to u16(0),
                            0x0005 to byteArrayOf(0),
                            0x0007 to u16(0),
                        ),
                    ),
                )
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0x4170 → 0x4171")
            }
            Iap2Csm.START_NOW_PLAYING_UPDATES -> {
                // Idle first (Frida iPhone), then arm Store-driven incremental updates.
                sendControl(NOW_PLAYING_UPDATE_IDLE)
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 0x5000 → 0x5001 idle + arm")
                onNowPlayingUpdates?.invoke()
            }
            // VehicleStatusUpdate from the HU — ack only.
            0xA101 -> {
                Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 VehicleStatusUpdate (0xA101) ack")
                return false
            }
            // PASCD location samples (~1Hz). iPhone LINK-ACKs only; missing ACKs
            // stop the stream and the HU closes control ~10s later.
            0xFFFB -> {
                Log.d(CarPlayConsts.TAG, "Wi-Fi iAP2 PASCD (0xFFFB) ack")
                return false
            }
            else -> return false
        }
        return true
    }

    /**
     * Messages the iPhone sends on this MMI immediately after 0xAA05, before the
     * HU's requestAppLaunch / Start* subscriptions.
     */
    private fun sendPhoneBurst() {
        val sessionId = UUID.randomUUID().toString().uppercase()
        sendControl(Iap2Csm.wirelessSessionIdentifier(sessionId))
        sendControl(
            Iap2Framing.encodeCsm(0xA100, mapOf(0x0006 to ByteArray(0))),
        )
        sendControl(
            Iap2Framing.encodeCsm(0x4E0A, mapOf(0x0000 to Iap2Framing.utf8Param("zh"))),
        )
        sendControl(deviceTime())
        sendControl(Iap2Csm.deviceInformationUpdate("iPhone"))
        // StartLocationInformation + GPRMCDataStatusValuesNotification (Frida exact).
        sendControl(START_LOCATION_INFORMATION)
        sendControl(GPRMC_DATA_STATUS)
        Log.i(CarPlayConsts.TAG, "Wi-Fi iAP2 phone burst session=$sessionId")
    }

    private fun u16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    private fun certRequest(): ByteArray =
        Iap2Framing.encodeCsm(
            Iap2Csm.REQUEST_AUTH_CERTIFICATE,
            mapOf(0x0000 to ByteArray(0)),
        )

    private fun deviceTime(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(Date(now))) 1 else 0
        val seconds = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(now / 1000).array()
        val tz = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(offsetMin.toShort()).array()
        return Iap2Framing.encodeCsm(
            0x4E0B,
            linkedMapOf(
                0x0000 to seconds,
                0x0001 to tz,
                0x0002 to byteArrayOf(dst.toByte()),
            ),
        )
    }

    private fun sendControl(csm: ByteArray) {
        seq = (seq + 1) and 0xFF
        sendFrame(
            Iap2Framing.encodeLinkPacket(
                CONTROL_ACK,
                seq,
                peerSeq,
                controlSessionId,
                csm,
            ),
        )
    }

    private fun sendFileTransferLocked(datagram: ByteArray) {
        seq = (seq + 1) and 0xFF
        unackedCount++
        sendFrame(
            Iap2Framing.encodeLinkPacket(
                CONTROL_ACK,
                seq,
                peerSeq,
                fileTransferSessionId,
                datagram,
            ),
        )
    }

    private fun flushFileTransferQueueLocked() {
        if (fileTransferQueue.isEmpty() || !synHandled) return
        while (unackedCount < peerMaxOutgoing && fileTransferQueue.isNotEmpty()) {
            sendFileTransferLocked(fileTransferQueue.removeFirst())
        }
    }

    private fun trackPeerAckLocked(ack: Int) {
        val last = lastPeerAck
        if (last == null || ack == last) {
            if (last == null) {
                lastPeerAck = ack
                unackedCount = 0
            }
            return
        }
        val delta = (ack - last + 256) % 256
        if (delta in 1 until 128) {
            unackedCount = (unackedCount - delta).coerceAtLeast(0)
            lastPeerAck = ack
        }
    }

    private fun sendAckOnly() {
        sendFrame(
            Iap2Framing.encodeLinkPacket(
                CONTROL_ACK,
                seq,
                peerSeq,
                0,
                ByteArray(0),
            ),
        )
    }

    private companion object {
        const val PHONE_MAX_OUTSTANDING = 0x7F
        const val PHONE_MAX_PACKET = 0xFFFF
        const val FILE_TRANSFER_QUEUE_CAP = 4_096

        /** iPhone Frida mediaserverd → Audi MMI after 0xAA05. */
        private val START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )
        private val GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** Idle NowPlayingUpdate — empty media/playback groups (Frida L185). */
        private val NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** CommunicationsUpdate — China SIM card, signal=1 (Frida L190). */
        private val COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
