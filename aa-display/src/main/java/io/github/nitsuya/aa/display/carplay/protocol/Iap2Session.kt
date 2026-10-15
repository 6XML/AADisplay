package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_ACK
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.CONTROL_SYN
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Framing.LinkSyncPayload
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Source-role (device / fake-iPhone) iAP2 state machine over RFCOMM.
 *
 * Against a HU / diplay receiver:
 * 1. Detect (`FF 55 02 00 EE 10`)
 * 2. Link Synchronization (SYN / SYN+ACK / ACK)
 * 3. Identification (`0x1D00` → `0x1D01` → `0x1D02`)
 * 4. Accessory authentication (`0xAA00`…`0xAA05`)
 * 5. Wireless handoff: transport id / `0x4E0D` / `0x5702` / `0x4300` →
 *    `0x5703` Wi-Fi creds and/or `0x4301` CarPlayStartSession (media TCP follows)
 */
class Iap2Session(
    private val sendRaw: (ByteArray) -> Unit,
    private val bluetoothTransportId: () -> String = { "00:00:00:00:00:00" },
    private val onLinkReady: (() -> Unit)? = null,
    private val onIdentificationComplete: (() -> Unit)? = null,
    private val onAuthComplete: (() -> Unit)? = null,
    private val onWifiConfig: ((Iap2Csm.WifiConfig) -> Unit)? = null,
    private val onCarPlayStart: ((Iap2Csm.CarPlayStart) -> Unit)? = null,
    private val onNowPlayingUpdates: (() -> Unit)? = null,
    private val onFileTransferDatagram: ((ByteArray) -> Unit)? = null,
) {
    private enum class State {
        Idle,
        Detect,
        Negotiate,
        LinkReady,
        Identifying,
        Identified,
        Authenticating,
        Authenticated,
        Dead,
    }

    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private var state = State.Idle

    private var ourSeq: Int = 0
    private var peerSeq: Int = 0
    private var controlSessionId: Int = 0x01
    private var fileTransferSessionId: Int = 0x0C
    private var ourLsp: LinkSyncPayload = LinkSyncPayload.defaultForSource()
    private var peerLsp: LinkSyncPayload? = null
    private var nowPlayingUpdatesRequested = false

    // Sender-side window: datagrams queued until the peer's cumulative ack frees space.
    // The DiPlay link engine drops packets beyond peer maxOutgoing (+slack), so unbounded
    // back-to-back sends (artwork bursts) would tear the link.
    private val fileTransferQueue = ArrayDeque<ByteArray>()
    private var peerMaxOutgoing = 5
    private var lastPeerAck: Int? = null
    private var unackedCount = 0

    private val detectSeen = AtomicBoolean(false)
    private val synSent = AtomicBoolean(false)
    private val startIdentSent = AtomicBoolean(false)
    private val authStarted = AtomicBoolean(false)
    private val wirelessStarted = AtomicBoolean(false)
    private var pendingChallenge: ByteArray? = null

    @Volatile
    var linkReady: Boolean = false
        private set

    @Volatile
    var identificationComplete: Boolean = false
        private set

    @Volatile
    var authenticationComplete: Boolean = false
        private set

    fun onTransportConnected() {
        synchronized(lock) {
            resetLocked()
            state = State.Detect
        }
        Log.i(CarPlayConsts.TAG, "iAP transport up → detect")
        // DiPlay may pad RFCOMM reads to 512B of repeated detect; we must speak first.
        sendRaw(Iap2Framing.DETECT_MARKER)
        Thread({
            repeat(8) { i ->
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                synchronized(lock) {
                    if (state != State.Detect && state != State.Negotiate) return@Thread
                    if (detectSeen.get() && synSent.get()) return@Thread
                    if (!detectSeen.get()) {
                        sendRaw(Iap2Framing.DETECT_MARKER)
                        Log.i(CarPlayConsts.TAG, "iAP detect retransmit #${i + 1}")
                    } else {
                        maybeSendSynLocked("probe-$i")
                    }
                }
            }
        }, "iap2-detect-probe").apply { isDaemon = true }.start()
    }

    fun onTransportClosed() {
        synchronized(lock) {
            state = State.Dead
            pending.reset()
            linkReady = false
        }
        Log.i(CarPlayConsts.TAG, "iAP transport closed")
    }

    fun onBytes(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (state == State.Idle || state == State.Dead) return
            pending.write(chunk)
            drainLocked()
        }
    }

    private fun resetLocked() {
        pending.reset()
        state = State.Idle
        ourSeq = 0
        peerSeq = 0
        controlSessionId = 0x01
        fileTransferSessionId = 0x0C
        ourLsp = LinkSyncPayload.defaultForSource()
        peerLsp = null
        fileTransferQueue.clear()
        peerMaxOutgoing = 5
        lastPeerAck = null
        unackedCount = 0
        nowPlayingUpdatesRequested = false
        detectSeen.set(false)
        synSent.set(false)
        startIdentSent.set(false)
        authStarted.set(false)
        wirelessStarted.set(false)
        pendingChallenge = null
        linkReady = false
        identificationComplete = false
        authenticationComplete = false
    }

    private fun drainLocked() {
        val data = pending.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val result = Iap2Framing.tryConsume(data, offset, data.size - offset) ?: break
            when (result) {
                is Iap2Framing.ConsumeResult.Detect -> {
                    offset += result.consumed
                    handleDetectLocked()
                }
                is Iap2Framing.ConsumeResult.Packet -> {
                    offset += result.consumed
                    handlePacketLocked(result.packet)
                }
                is Iap2Framing.ConsumeResult.Skip -> {
                    offset += result.consumed
                }
            }
        }
        if (offset > 0) {
            pending.reset()
            if (offset < data.size) {
                pending.write(data, offset, data.size - offset)
            }
        }
    }

    private fun handleDetectLocked() {
        val first = detectSeen.compareAndSet(false, true)
        if (!first) return
        Log.i(CarPlayConsts.TAG, "iAP detect exchanged")
        sendRaw(Iap2Framing.DETECT_MARKER)
        if (state == State.Detect) {
            state = State.Negotiate
        }
        maybeSendSynLocked("after-detect")
    }

    private fun handlePacketLocked(packet: Iap2Framing.LinkPacket) {
        val head = packet.payload.take(8).joinToString(" ") { "%02x".format(it) }
        Log.i(
            CarPlayConsts.TAG,
            "iAP link ctrl=0x${"%02x".format(packet.control)} seq=${packet.seq} " +
                "ack=${packet.ack} sess=${packet.sessionId} pay=${packet.payload.size}B head=$head",
        )
        if (packet.hasRst) {
            Log.w(CarPlayConsts.TAG, "iAP peer RST")
            state = State.Dead
            linkReady = false
            return
        }
        if (packet.hasSyn) {
            handleSynLocked(packet)
            return
        }
        if (packet.hasAck && packet.payload.isEmpty() && state == State.Negotiate) {
            peerSeq = packet.seq
            markLinkReadyLocked("ack-to-syn")
            return
        }
        if (packet.payload.isNotEmpty() && !packet.hasSyn) {
            peerSeq = packet.seq
            sendLinkLocked(CONTROL_ACK, sessionId = 0, payload = ByteArray(0))
            if (packet.hasAck) {
                trackPeerAckLocked(packet.ack)
            }
            if (packet.sessionId == controlSessionId ||
                peerLsp?.sessions?.any { it.first == packet.sessionId && it.second == 0 } == true
            ) {
                handleControlPayloadLocked(packet.payload)
            } else if (packet.sessionId == fileTransferSessionId) {
                onFileTransferDatagram?.invoke(packet.payload)
                flushFileTransferQueueLocked()
            }
        }
    }

    /**
     * Peer cumulative-ack bookkeeping: bounds the unacked window so queued file-transfer
     * datagrams respect the peer's [LinkSyncPayload.maxOutgoing] (the DiPlay engine drops
     * anything beyond it).
     */
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

    private fun handleSynLocked(packet: Iap2Framing.LinkPacket) {
        val lsp = LinkSyncPayload.parse(packet.payload)
        if (lsp == null) {
            Log.w(CarPlayConsts.TAG, "iAP SYN payload unparseable (${packet.payload.size}B)")
            return
        }
        peerLsp = lsp
        peerSeq = packet.seq
        controlSessionId = lsp.controlSessionId()
        peerMaxOutgoing = lsp.maxOutgoing.coerceIn(1, 255)
        fileTransferSessionId =
            lsp.sessions.firstOrNull { it.second == Iap2Framing.SESSION_TYPE_FILE_TRANSFER }?.first
                ?: 0x0C
        val peerIds = lsp.sessions.map { it.first to it.second }.toSet()
        val intersect = ourLsp.sessions.filter { (id, type, _) -> (id to type) in peerIds }
        if (intersect.isNotEmpty()) {
            ourLsp = ourLsp.copy(sessions = intersect)
            controlSessionId = ourLsp.controlSessionId()
        } else {
            ourLsp = lsp
            controlSessionId = lsp.controlSessionId()
        }
        Log.i(
            CarPlayConsts.TAG,
            "iAP SYN peer sessions=${lsp.sessions} → controlSession=$controlSessionId",
        )
        if (state == State.Detect) state = State.Negotiate
        ourSeq = (ourSeq + 1) and 0xFF
        sendLinkLocked(
            CONTROL_SYN or CONTROL_ACK,
            sessionId = 0,
            payload = ourLsp.pack(),
            seqOverride = ourSeq,
        )
        synSent.set(true)
        if (packet.hasAck) {
            markLinkReadyLocked("syn-ack")
        }
    }

    private fun maybeSendSyn(reason: String) {
        synchronized(lock) {
            maybeSendSynLocked(reason)
        }
    }

    private fun maybeSendSynLocked(reason: String) {
        if (state != State.Detect && state != State.Negotiate) return
        if (synSent.get() && peerLsp != null) return
        if (synSent.get() && !detectSeen.get()) return
        ourSeq = (ourSeq + 1) and 0xFF
        sendLinkLocked(
            CONTROL_SYN,
            sessionId = 0,
            payload = ourLsp.pack(),
            seqOverride = ourSeq,
        )
        synSent.set(true)
        if (state == State.Detect) state = State.Negotiate
        Log.i(CarPlayConsts.TAG, "iAP SYN sent ($reason) seq=$ourSeq")
    }

    private fun markLinkReadyLocked(reason: String) {
        if (linkReady) return
        linkReady = true
        state = State.LinkReady
        Log.i(CarPlayConsts.TAG, "iAP link ready ($reason) controlSession=$controlSessionId")
        onLinkReady?.invoke()
        beginIdentificationLocked()
    }

    private fun beginIdentificationLocked() {
        if (!startIdentSent.compareAndSet(false, true)) return
        state = State.Identifying
        sendControlLocked(Iap2Csm.empty(Iap2Csm.START_IDENTIFICATION))
        Log.i(CarPlayConsts.TAG, "iAP StartIdentification (0x1D00) sent sess=$controlSessionId")
    }

    private fun handleControlPayloadLocked(payload: ByteArray) {
        val msg = Iap2Framing.tryParseCsm(payload) ?: run {
            Log.w(CarPlayConsts.TAG, "iAP control payload not CSM (${payload.size}B)")
            return
        }
        Log.i(
            CarPlayConsts.TAG,
            "iAP CSM id=0x${"%04x".format(msg.messageId)} params=${msg.parameters.size}",
        )
        when (msg.messageId) {
            Iap2Csm.START_IDENTIFICATION -> sendIdentificationInformationLocked()
            Iap2Csm.IDENTIFICATION_INFORMATION -> {
                logIdentificationSummary(msg)
                sendIdentificationAcceptedLocked()
            }
            Iap2Csm.IDENTIFICATION_ACCEPTED -> markIdentifiedLocked("peer-accepted")
            Iap2Csm.IDENTIFICATION_REJECTED ->
                Log.w(CarPlayConsts.TAG, "iAP IdentificationRejected")

            Iap2Csm.AUTH_CERTIFICATE -> handleAuthCertificateLocked(msg)
            Iap2Csm.AUTH_RESPONSE -> handleAuthResponseLocked(msg)
            Iap2Csm.AUTH_FAILED -> Log.w(CarPlayConsts.TAG, "iAP AuthenticationFailed from peer")
            Iap2Csm.AUTH_SUCCEEDED -> {
                // Unusual for accessory to send; treat as done if we were waiting.
                markAuthenticatedLocked("peer-AA05")
            }

            Iap2Csm.ACCESSORY_WIFI_INFO -> {
                val wifi = Iap2Csm.parseWifiConfig(msg)
                Log.i(
                    CarPlayConsts.TAG,
                    "iAP AccessoryWiFi ssid=${wifi.ssid} ch=${wifi.channel} " +
                        "sec=${wifi.securityType} passLen=${wifi.passphrase?.length ?: 0}",
                )
                onWifiConfig?.invoke(wifi)
            }
            Iap2Csm.CARPLAY_START_SESSION -> {
                val start = Iap2Csm.parseCarPlayStart(msg)
                Log.i(
                    CarPlayConsts.TAG,
                    "iAP CarPlayStartSession port=${start.port} deviceId=${start.deviceId} " +
                        "ssid=${start.ssid} ips=${start.ipAddresses} ver=${start.sourceVersion}",
                )
                onCarPlayStart?.invoke(start)
            }

            Iap2Csm.START_NOW_PLAYING_UPDATES -> {
                if (!nowPlayingUpdatesRequested) {
                    nowPlayingUpdatesRequested = true
                    Log.i(CarPlayConsts.TAG, "iAP StartNowPlayingUpdates (0x5000) — now playing stream armed")
                }
                onNowPlayingUpdates?.invoke()
            }

            Iap2Csm.REQUEST_AUTH_CERTIFICATE,
            Iap2Csm.REQUEST_AUTH_CHALLENGE,
            Iap2Csm.REQUEST_ACCESSORY_WIFI,
            Iap2Csm.CARPLAY_AVAILABILITY,
            -> Log.i(CarPlayConsts.TAG, "iAP CSM device-origin echo/ignore 0x${"%04x".format(msg.messageId)}")

            else ->
                Log.i(CarPlayConsts.TAG, "iAP CSM unhandled 0x${"%04x".format(msg.messageId)}")
        }
    }

    private fun logIdentificationSummary(msg: Iap2Framing.ControlMessage) {
        fun str(id: Int): String? = Iap2Csm.utf8(msg.parameters[id])
        Log.i(
            CarPlayConsts.TAG,
            "iAP IdentificationInformation name=${str(0)} model=${str(1)} " +
                "mfr=${str(2)} wirelessCarPlayParam=${msg.parameters.containsKey(0x0018)} " +
                "btTransport=${msg.parameters.containsKey(0x0011)}",
        )
    }

    private fun sendIdentificationAcceptedLocked() {
        sendControlLocked(Iap2Csm.empty(Iap2Csm.IDENTIFICATION_ACCEPTED))
        Log.i(CarPlayConsts.TAG, "iAP IdentificationAccepted (0x1D02) sent")
        markIdentifiedLocked("accepted-accessory")
    }

    private fun sendIdentificationInformationLocked() {
        val params = linkedMapOf(
            0x0000 to Iap2Framing.utf8Param("AADisplay"),
            0x0001 to Iap2Framing.utf8Param("CarPlaySource"),
            0x0002 to Iap2Framing.utf8Param("Nitsuya"),
            0x0003 to Iap2Framing.utf8Param("AAD-CP-0001"),
            0x0004 to Iap2Framing.utf8Param("1.0"),
            0x0005 to Iap2Framing.utf8Param("1.0"),
            0x0006 to Iap2Framing.u16ListParam(
                intArrayOf(0xAA01, 0xAA03, 0x5703, 0x4E01, 0xEA03, 0x4301),
            ),
            0x0007 to Iap2Framing.u16ListParam(
                intArrayOf(
                    0xAA00, 0xAA02, 0xAA04, 0xAA05,
                    0x5702, 0x4E0B, 0x4E0D, 0x4E0E,
                    0xEA00, 0xEA01, 0x4300,
                ),
            ),
            0x0008 to byteArrayOf(0x00),
            0x0009 to byteArrayOf(0x00, 0x00),
            0x000C to Iap2Framing.utf8Param("en"),
            0x000D to Iap2Framing.utf8Param("en"),
            0x0011 to Iap2Csm.encodeGroup(
                linkedMapOf(
                    0x0000 to byteArrayOf(0x00, 0x01),
                    0x0001 to Iap2Framing.utf8Param("AADisplay BT"),
                    0x0002 to ByteArray(0),
                ),
            ),
            0x0018 to Iap2Csm.encodeGroup(
                linkedMapOf(
                    0x0000 to byteArrayOf(0x00, 0x01),
                    0x0001 to Iap2Framing.utf8Param("AADisplay WiFi"),
                    0x0002 to ByteArray(0),
                    0x0004 to ByteArray(0),
                ),
            ),
        )
        sendControlLocked(Iap2Framing.encodeCsm(Iap2Csm.IDENTIFICATION_INFORMATION, params))
        Log.i(CarPlayConsts.TAG, "iAP IdentificationInformation (0x1D01) sent")
    }

    private fun markIdentifiedLocked(reason: String) {
        if (identificationComplete) return
        identificationComplete = true
        state = State.Identified
        Log.i(CarPlayConsts.TAG, "iAP identification complete ($reason)")
        onIdentificationComplete?.invoke()
        beginAuthenticationLocked()
    }

    private fun beginAuthenticationLocked() {
        if (!authStarted.compareAndSet(false, true)) return
        state = State.Authenticating
        sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_AUTH_CERTIFICATE))
        Log.i(CarPlayConsts.TAG, "iAP RequestAuthenticationCertificate (0xAA00) sent")
    }

    private fun handleAuthCertificateLocked(msg: Iap2Framing.ControlMessage) {
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationCertificate ${Iap2Csm.authCertificateSummary(msg)}")
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        pendingChallenge = challenge
        sendControlLocked(Iap2Csm.requestAuthChallenge(challenge))
        Log.i(CarPlayConsts.TAG, "iAP RequestAuthenticationChallenge (0xAA02) sent ${challenge.size}B")
    }

    private fun handleAuthResponseLocked(msg: Iap2Framing.ControlMessage) {
        val sig = msg.parameters[0x0000] ?: msg.parameters.values.firstOrNull()
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationResponse sig=${sig?.size ?: 0}B")
        // Source accepts accessory signature without local MFi verify (lab / diplay path).
        sendControlLocked(Iap2Csm.empty(Iap2Csm.AUTH_SUCCEEDED))
        Log.i(CarPlayConsts.TAG, "iAP AuthenticationSucceeded (0xAA05) sent")
        markAuthenticatedLocked("challenge-ok")
    }

    private fun markAuthenticatedLocked(reason: String) {
        if (authenticationComplete) return
        authenticationComplete = true
        state = State.Authenticated
        pendingChallenge = null
        Log.i(CarPlayConsts.TAG, "iAP authentication complete ($reason)")
        onAuthComplete?.invoke()
        beginWirelessHandoffLocked()
    }

    private fun beginWirelessHandoffLocked() {
        if (!wirelessStarted.compareAndSet(false, true)) return
        val btId = bluetoothTransportId().ifBlank { "00:00:00:00:00:00" }
        sendControlLocked(Iap2Csm.deviceTransportIdentifier(btId))
        Log.i(CarPlayConsts.TAG, "iAP DeviceTransportIdentifier (0x4E0E) bt=$btId")
        sendControlLocked(Iap2Csm.wirelessCarPlayUpdate(available = true))
        Log.i(CarPlayConsts.TAG, "iAP WirelessCarPlayUpdate AVAILABLE (0x4E0D)")
        sendControlLocked(Iap2Csm.empty(Iap2Csm.REQUEST_ACCESSORY_WIFI))
        Log.i(CarPlayConsts.TAG, "iAP RequestAccessoryWiFiConfiguration (0x5702)")
        sendControlLocked(Iap2Csm.carPlayAvailability(btId))
        Log.i(CarPlayConsts.TAG, "iAP CarPlayAvailability (0x4300) wireless=true")
    }

    private fun sendControlLocked(csm: ByteArray) {
        ourSeq = (ourSeq + 1) and 0xFF
        sendLinkLocked(
            CONTROL_ACK,
            sessionId = controlSessionId,
            payload = csm,
            seqOverride = ourSeq,
        )
    }

    private fun sendLinkLocked(
        control: Int,
        sessionId: Int,
        payload: ByteArray,
        seqOverride: Int? = null,
    ) {
        val seq = seqOverride ?: ourSeq
        val frame = Iap2Framing.encodeLinkPacket(
            control = control,
            seq = seq,
            ack = peerSeq,
            sessionId = sessionId,
            payload = payload,
        )
        sendRaw(frame)
    }

    // ------------------------------------------------------------------
    // CarPlay NowPlaying (0x5001) + iAP2 v2 file transfer (session 12)
    // ------------------------------------------------------------------

    /** True once the host sent StartNowPlayingUpdates (0x5000). */
    val nowPlayingUpdates: Boolean
        get() = nowPlayingUpdatesRequested

    /**
     * Sends one NowPlayingUpdate. Group bodies are nested TLV8 (DiPlay
     * [Iap2BodyReader.optionalGroup] shape); empty groups are omitted, an omitted field
     * keeps its previous value, and a present empty string clears it. Safe from any thread.
     */
    fun sendNowPlaying(mediaItem: Map<Int, ByteArray>, playback: Map<Int, ByteArray>) {
        val params = linkedMapOf<Int, ByteArray>()
        if (mediaItem.isNotEmpty()) params[0] = Iap2Csm.encodeGroup(mediaItem)
        if (playback.isNotEmpty()) params[1] = Iap2Csm.encodeGroup(playback)
        if (params.isEmpty()) return
        synchronized(lock) {
            if (state == State.Dead || state == State.Idle) return
            sendControlLocked(Iap2Framing.encodeCsm(Iap2Csm.NOW_PLAYING_UPDATE, params))
        }
    }

    /**
     * Queues one file-transfer datagram (session 12) and sends as many as the peer window
     * allows; the remainder flushes on the next peer ack. The caller owns the 2-byte
     * [id, flags|opcode] header + payload layout. Safe from any thread.
     */
    fun sendFileTransfer(datagram: ByteArray) {
        synchronized(lock) {
            if (state == State.Dead || state == State.Idle || !linkReady) return
            if (fileTransferQueue.isNotEmpty()) {
                if (fileTransferQueue.size < FILE_TRANSFER_QUEUE_CAP) {
                    fileTransferQueue += datagram
                } else {
                    Log.w(
                        CarPlayConsts.TAG,
                        "iAP file-transfer queue full (${fileTransferQueue.size}) — dropping ${datagram.size}B",
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

    private fun sendFileTransferLocked(datagram: ByteArray) {
        ourSeq = (ourSeq + 1) and 0xFF
        unackedCount++
        sendLinkLocked(CONTROL_ACK, sessionId = fileTransferSessionId, payload = datagram)
    }

    private fun flushFileTransferQueueLocked() {
        if (fileTransferQueue.isEmpty() || !linkReady) return
        while (unackedCount < peerMaxOutgoing && fileTransferQueue.isNotEmpty()) {
            sendFileTransferLocked(fileTransferQueue.removeFirst())
        }
    }

    private companion object {
        const val FILE_TRANSFER_QUEUE_CAP = 4_096
    }
}
