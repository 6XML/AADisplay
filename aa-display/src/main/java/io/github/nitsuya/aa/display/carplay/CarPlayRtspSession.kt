package io.github.nitsuya.aa.display.carplay

import android.util.Log
import io.github.nitsuya.aa.display.carplay.protocol.AirPlayPairVerify
import io.github.nitsuya.aa.display.carplay.protocol.AirPlayPairing
import io.github.nitsuya.aa.display.carplay.protocol.BinaryPlist
import io.github.nitsuya.aa.display.carplay.protocol.CarPlayEventChannel
import io.github.nitsuya.aa.display.carplay.protocol.ControlChannelCrypto
import io.github.nitsuya.aa.display.carplay.protocol.RtpH264Packetizer
import io.github.nitsuya.aa.display.carplay.protocol.RtspMessage
import io.github.nitsuya.aa.display.carplay.protocol.ScreenStreamEgress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Accepts / dials the CarPlay control RTSP channel, answers SETUP/RECORD,
 * and can emit H.264 as RTP/AVP/TCP interleaved frames.
 */
class CarPlayRtspSession(
    private val onVideoSetup: (width: Int, height: Int) -> Unit,
    private val onSessionReady: () -> Unit,
    private val onTouchEvent: (x: Float, y: Float, action: Int) -> Unit,
    private val onForceKeyFrame: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var peer: Socket? = null
    private var output: BufferedOutputStream? = null
    private val writeLock = Any()
    private val packetizer = RtpH264Packetizer()
    private val rtpChannel = AtomicInteger(0)
    private val rtpSent = AtomicLong(0)
    private val rtpBytes = AtomicLong(0)
    private val controlWriteKey = AtomicReference<ByteArray?>(null)
    private val controlReadKey = AtomicReference<ByteArray?>(null)
    private val screenEgress = AtomicReference<ScreenStreamEgress?>(null)
    private val audioEgress = AtomicReference<CarPlayAudioEgress?>(null)
    private val eventChannel = AtomicReference<CarPlayEventChannel?>(null)
    private val lastCodecConfig = AtomicReference<ByteArray?>(null)
    private val pairSessionKey = AtomicReference<ByteArray?>(null)
    /** Accessory main display from GET /info — HID touch report pixel space (DiPlay). */
    @Volatile private var accessoryTouchMaxX = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    @Volatile private var accessoryTouchMaxY = CarPlayConsts.DEFAULT_VIDEO_HEIGHT

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    fun isStreaming(): Boolean = streaming.get()

    fun rtpStats(): Pair<Long, Long> {
        val screen = screenEgress.get()
        return if (screen != null) screen.stats() else rtpSent.get() to rtpBytes.get()
    }

    fun hasControlKeys(): Boolean = controlWriteKey.get() != null

    fun hasScreenEgress(): Boolean = screenEgress.get() != null

    fun startServer() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "carplay-rtsp-server", isDaemon = true) {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                Log.i(CarPlayConsts.TAG, "RTSP control listening port=${server.localPort}")
                while (running.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    // One thread per peer — loopback must not block HU / diplay probes.
                    thread(name = "carplay-rtsp-peer", isDaemon = true) {
                        attachPeer(socket)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) Log.e(CarPlayConsts.TAG, "RTSP server failed", e)
            }
        }
    }

    fun connect(host: InetAddress, port: Int) {
        thread(name = "carplay-rtsp-client", isDaemon = true) {
            if (!running.get()) running.set(true)
            try {
                val socket = Socket(host, port)
                Log.i(CarPlayConsts.TAG, "RTSP connected to $host:$port")
                attachPeer(socket)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "RTSP connect failed", e)
            }
        }
    }

    /**
     * Source→receiver: after iAP2 CarPlayStartSession, dial HU AirPlay port,
     * GET /info, then HomeKit Pair-Setup (transient preferred).
     */
    fun connectAsAirPlaySource(host: InetAddress, port: Int, deviceId: String?) {
        thread(name = "carplay-airplay-source", isDaemon = true) {
            if (!running.get()) running.set(true)
            try {
                val socket = Socket(host, port)
                socket.soTimeout = 15_000
                Log.i(CarPlayConsts.TAG, "AirPlay source dial $host:$port deviceId=$deviceId")
                peer = socket
                val input = BufferedInputStream(socket.getInputStream())
                val out = BufferedOutputStream(socket.getOutputStream())
                output = out
                var cseq = 1
                fun exchange(req: RtspMessage): RtspMessage? {
                    synchronized(writeLock) {
                        out.write(req.toBytes())
                        out.flush()
                    }
                    Log.i(CarPlayConsts.TAG, "AirPlay → ${req.firstLine} body=${req.body.size}B")
                    val headerBuf = ByteArrayOutputLimited(64 * 1024)
                    while (true) {
                        val b = input.read()
                        if (b < 0) return null
                        headerBuf.write(b)
                        if (!headerBuf.endsWithCrlfCrlf()) continue
                        val provisional = RtspMessage.parse(headerBuf.toByteArray()) ?: return null
                        headerBuf.reset()
                        val contentLen = provisional.headers.entries
                            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                            ?.value?.trim()?.toIntOrNull() ?: 0
                        val body = if (contentLen > 0) {
                            val buf = ByteArray(contentLen)
                            var got = 0
                            while (got < contentLen) {
                                val n = input.read(buf, got, contentLen - got)
                                if (n < 0) break
                                got += n
                            }
                            buf.copyOf(got)
                        } else {
                            ByteArray(0)
                        }
                        return provisional.copy(body = body)
                    }
                }
                fun post(path: String, body: ByteArray, contentType: String): ByteArray? {
                    val req = RtspMessage(
                        "POST $path RTSP/1.0",
                        linkedMapOf(
                            "CSeq" to (cseq++).toString(),
                            "User-Agent" to "AirPlay/950.7.1",
                            "X-Apple-HKP" to "3",
                            "Content-Type" to contentType,
                            "Content-Length" to body.size.toString(),
                        ),
                        body,
                    )
                    val resp = exchange(req) ?: return null
                    logAirPlayResponse(path, resp)
                    if (!resp.firstLine.contains("200")) {
                        Log.w(CarPlayConsts.TAG, "AirPlay $path non-200: ${resp.firstLine}")
                    }
                    return resp.body
                }

                val info = exchange(
                    RtspMessage(
                        "GET /info RTSP/1.0",
                        linkedMapOf(
                            "CSeq" to (cseq++).toString(),
                            "User-Agent" to "AirPlay/950.7.1",
                            "DACP-ID" to (deviceId ?: "AADISPLAY"),
                            "Active-Remote" to "AADisplay",
                            "X-Apple-HKP" to "3",
                        ),
                    ),
                )
                logAirPlayResponse("info", info)
                info?.body?.let { parseAccessoryInfo(it) }
                val options = exchange(
                    RtspMessage(
                        "OPTIONS * RTSP/1.0",
                        linkedMapOf(
                            "CSeq" to (cseq++).toString(),
                            "User-Agent" to "AirPlay/950.7.1",
                            "X-Apple-HKP" to "3",
                        ),
                    ),
                )
                logAirPlayResponse("options", options)

                // DiPlay: persistent pair-setup M1–M6 (saves our LTPK), then pair-verify
                // (X25519 → Control-Salt keys); ControlCipher only after verify M4.
                val pairing = AirPlayPairing(
                    controllerId = deviceId ?: "AADisplay",
                    exchange = { path, body, ct -> post(path, body, ct) },
                )
                val setup = pairing.runPairSetup(preferTransient = false)
                if (setup == null) {
                    Log.e(CarPlayConsts.TAG, "AirPlay pair-setup failed")
                    return@thread
                }
                val identity = setup.identity
                if (identity == null) {
                    Log.e(CarPlayConsts.TAG, "AirPlay pair-setup missing identity (need M6)")
                    return@thread
                }
                Log.i(
                    CarPlayConsts.TAG,
                    "AirPlay pair-setup success transient=${setup.transient} " +
                        "accessoryId=${identity.accessoryId} sessionKey=${setup.sessionKey.size}B",
                )
                val verify = AirPlayPairVerify { path, body, ct -> post(path, body, ct) }.run(identity)
                if (verify == null) {
                    Log.e(CarPlayConsts.TAG, "AirPlay pair-verify failed")
                    return@thread
                }
                controlWriteKey.set(verify.writeKey)
                controlReadKey.set(verify.readKey)
                pairSessionKey.set(verify.sharedSecret)
                Log.i(
                    CarPlayConsts.TAG,
                    "AirPlay pair-verify success writeKey=${verify.writeKey.size}B " +
                        "readKey=${verify.readKey.size}B shared=${verify.sharedSecret.size}B",
                )
                socket.soTimeout = 30_000
                val crypto = ControlChannelCrypto(verify.writeKey, verify.readKey)
                val ok = runEncryptedSetupRecord(
                    peerHost = host,
                    deviceId = deviceId,
                    cseqStart = cseq,
                    sharedSecret = verify.sharedSecret,
                    crypto = crypto,
                    input = input,
                    out = out,
                )
                if (!ok) {
                    Log.e(CarPlayConsts.TAG, "encrypted SETUP/RECORD / screen open failed")
                    return@thread
                }
                // Keep encrypted control alive (feedback); video is on dataPort.
                socket.soTimeout = 0
                pumpEncryptedControl(crypto, input)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "AirPlay source dial failed", e)
            }
        }
    }

    /**
     * After pair-verify: HAP control framing → SETUP(session) → RECORD →
     * SETUP(screen type=110) → TCP dataPort with DataStream ChaCha video.
     * [sharedSecret] is the pair-verify X25519 secret (DiPlay session.sharedSecret).
     */
    private fun runEncryptedSetupRecord(
        peerHost: InetAddress,
        deviceId: String?,
        cseqStart: Int,
        sharedSecret: ByteArray,
        crypto: ControlChannelCrypto,
        input: BufferedInputStream,
        out: BufferedOutputStream,
    ): Boolean {
        var cseq = cseqStart
        val host = peerHost.hostAddress ?: peerHost.hostName
        val sessionId = (Random.nextLong() and Long.MAX_VALUE).toString()
        val sessionUrl = "rtsp://$host/$sessionId"
        val dacp = (deviceId ?: "AADISPLAY").replace(":", "").uppercase()
        fun headers(extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
            val h = linkedMapOf(
                "CSeq" to (cseq++).toString(),
                "User-Agent" to "AirPlay/950.7.1",
                "DACP-ID" to dacp,
                "Active-Remote" to "AADisplay",
                "X-Apple-HKP" to "3",
            )
            h.putAll(extra)
            return h
        }
        fun exchangeEncrypted(req: RtspMessage): RtspMessage? {
            val plain = req.toBytes()
            synchronized(writeLock) {
                crypto.writeMessage(out, plain)
            }
            Log.i(
                CarPlayConsts.TAG,
                "AirPlay enc → ${req.firstLine} body=${req.body.size}B plain=${plain.size}B",
            )
            val raw = crypto.readMessage(input) ?: return null
            val msg = RtspMessage.parse(raw) ?: return null
            logAirPlayResponse("enc", msg)
            return msg
        }

        val sessionUuid = UUID.randomUUID().toString().uppercase()
        val setupSessionBody = BinaryPlist.encode(
            linkedMapOf(
                "deviceID" to (deviceId ?: "AA:DI:SP:LA:Y0:01"),
                "macAddress" to (deviceId ?: "AA:DI:SP:LA:Y0:01"),
                "model" to "iPhone14,2",
                "name" to "AADisplay",
                "osBuildVersion" to "20A392",
                "osName" to "iPhone OS",
                "osVersion" to "16.0",
                "sessionUUID" to sessionUuid,
                "sourceVersion" to "950.7.1",
                "timingProtocol" to "NTP",
            ),
        )
        val setup1 = exchangeEncrypted(
            RtspMessage(
                "SETUP $sessionUrl RTSP/1.0",
                headers(
                    mapOf(
                        "Content-Type" to "application/x-apple-binary-plist",
                        "Content-Length" to setupSessionBody.size.toString(),
                    ),
                ),
                setupSessionBody,
            ),
        ) ?: return false
        if (!setup1.firstLine.contains("200")) {
            Log.w(CarPlayConsts.TAG, "SETUP session non-200: ${setup1.firstLine}")
            return false
        }
        val setup1Dict = BinaryPlist.asDict(BinaryPlist.decode(setup1.body))
        val eventPort = BinaryPlist.intOf(setup1Dict?.get("eventPort"))?.toInt()?.and(0xffff)
        Log.i(CarPlayConsts.TAG, "SETUP session ok eventPort=$eventPort")
        if (eventPort != null && eventPort > 0) {
            // DiPlay acceptEvent arms Events-* cipher on connect — open before RECORD/screen.
            val ev = CarPlayEventChannel.open(
                host = peerHost,
                eventPort = eventPort,
                sharedSecret = sharedSecret,
                touchMaxX = accessoryTouchMaxX,
                touchMaxY = accessoryTouchMaxY,
                onForceKeyFrame = onForceKeyFrame,
                onTouch = onTouchEvent,
            )
            eventChannel.getAndSet(ev)?.close()
        } else {
            Log.w(CarPlayConsts.TAG, "SETUP session missing eventPort — no touch/forceKeyFrame")
        }

        val record = exchangeEncrypted(
            RtspMessage("RECORD $sessionUrl RTSP/1.0", headers()),
        ) ?: return false
        if (!record.firstLine.contains("200")) {
            Log.w(CarPlayConsts.TAG, "RECORD non-200: ${record.firstLine}")
            return false
        }
        Log.i(CarPlayConsts.TAG, "RECORD ok — screen SETUP next")

        val streamConnectionId = Random.nextLong() and Long.MAX_VALUE
        val screenStream = linkedMapOf<String, Any?>(
            "type" to 110L,
            "streamConnectionID" to streamConnectionId,
            "latencyMs" to 90L,
            "fps" to CarPlayConsts.DEFAULT_VIDEO_FPS.toLong(),
            "usingScreen" to true,
            "supportsHighAccuracyTimestamps" to true,
            "timestampInfo" to listOf(
                mapOf("name" to "SubSu"),
                mapOf("name" to "BePxT"),
                mapOf("name" to "AfPxT"),
                mapOf("name" to "BefEn"),
                mapOf("name" to "EmEnc"),
            ),
        )
        val setupScreenBody = BinaryPlist.encode(mapOf("streams" to listOf(screenStream)))
        val setup2 = exchangeEncrypted(
            RtspMessage(
                "SETUP $sessionUrl RTSP/1.0",
                headers(
                    mapOf(
                        "Content-Type" to "application/x-apple-binary-plist",
                        "Content-Length" to setupScreenBody.size.toString(),
                    ),
                ),
                setupScreenBody,
            ),
        ) ?: return false
        if (!setup2.firstLine.contains("200")) {
            Log.w(CarPlayConsts.TAG, "SETUP screen non-200: ${setup2.firstLine}")
            return false
        }
        val setup2Dict = BinaryPlist.asDict(BinaryPlist.decode(setup2.body))
        val streams = setup2Dict?.get("streams") as? List<*>
        val first = BinaryPlist.asDict(streams?.firstOrNull())
        val dataPortRaw = BinaryPlist.intOf(first?.get("dataPort"))
        val dataPort = dataPortRaw?.toInt()?.and(0xffff)
        if (dataPort == null || dataPort <= 0) {
            Log.e(
                CarPlayConsts.TAG,
                "SETUP screen missing dataPort body=${setup2.body.size}B " +
                    "dictKeys=${setup2Dict?.keys} streamKeys=${first?.keys} raw=$dataPortRaw",
            )
            return false
        }
        Log.i(
            CarPlayConsts.TAG,
            "SETUP screen ok dataPort=$dataPort streamConnectionID=$streamConnectionId",
        )

        val w = CarPlayConsts.ENCODE_WIDTH
        val h = CarPlayConsts.ENCODE_HEIGHT
        val egress = ScreenStreamEgress.open(
            host = peerHost,
            dataPort = dataPort,
            sessionKey = sharedSecret,
            streamConnectionId = streamConnectionId,
            width = w,
            height = h,
        ) ?: return false
        screenEgress.getAndSet(egress)?.close()
        lastCodecConfig.get()?.let { cfg ->
            egress.sendCodecConfig(cfg, System.nanoTime())
        }
        streaming.set(true)
        onVideoSetup(w, h)

        // Audio: DiPlay [CarPlayMediaEngine.onAudio] answers dataPort/controlPort; the
        // 16 kHz mono LPCM tone then plays on the host without any codec dependency.
        if (CarPlayConsts.SEND_TEST_AUDIO) {
            setupTestAudio(
                peerHost,
                sharedSecret,
                { extra -> headers(extra) },
                sessionUrl,
                { req -> exchangeEncrypted(req) },
            )
        }

        onSessionReady()
        Log.i(CarPlayConsts.TAG, "screen egress armed — AVC frames → dataPort=$dataPort")
        return true
    }

    /**
     * SETUP(streams=[{type:100, audioType:"media", audioFormat:0x10(16k mono LPCM)}]) on the
     * encrypted control channel, then starts the UDP tone against the answered ports.
     * Non-fatal: a declined stream only costs the tone, not the screen.
     */
    private fun setupTestAudio(
        peerHost: InetAddress,
        sharedSecret: ByteArray,
        headers: (Map<String, String>) -> LinkedHashMap<String, String>,
        sessionUrl: String,
        exchangeEncrypted: (RtspMessage) -> RtspMessage?,
    ) {
        try {
            val streamConnectionId = Random.nextLong() and Long.MAX_VALUE
            val audioStream = linkedMapOf<String, Any?>(
                "type" to 100L,
                "streamConnectionID" to streamConnectionId,
                "audioType" to "media",
                "audioFormat" to CarPlayAudioEgress.AUDIO_FORMAT_PCM_16K_MONO,
                "audioLatencyMs" to 100L,
            )
            val body = BinaryPlist.encode(mapOf("streams" to listOf(audioStream)))
            val resp = exchangeEncrypted(
                RtspMessage(
                    "SETUP $sessionUrl RTSP/1.0",
                    headers(
                        mapOf(
                            "Content-Type" to "application/x-apple-binary-plist",
                            "Content-Length" to body.size.toString(),
                        ),
                    ),
                    body,
                ),
            )
            if (resp == null || !resp.firstLine.contains("200")) {
                Log.w(CarPlayConsts.TAG, "CarPlay audio SETUP rejected: ${resp?.firstLine}")
                return
            }
            val egress = CarPlayAudioEgress.open(peerHost, resp, sharedSecret, streamConnectionId)
            audioEgress.getAndSet(egress)?.stop()
            egress?.start()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "CarPlay audio SETUP failed (screen continues)", e)
        }
    }

    private fun pumpEncryptedControl(crypto: ControlChannelCrypto, input: BufferedInputStream) {
        Log.i(CarPlayConsts.TAG, "encrypted control pump (video on dataPort)")
        while (running.get()) {
            val raw = try {
                crypto.readMessage(input)
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "encrypted control read ended", e)
                break
            } ?: break
            val msg = RtspMessage.parse(raw)
            Log.i(
                CarPlayConsts.TAG,
                "AirPlay enc ← ${msg?.firstLine ?: "?"} body=${msg?.body?.size ?: 0}B",
            )
        }
    }

    private fun logAirPlayResponse(label: String, msg: RtspMessage?) {
        if (msg == null) {
            Log.w(CarPlayConsts.TAG, "AirPlay ← $label: no response")
            return
        }
        val preview = msg.body.take(64).joinToString(" ") { "%02x".format(it) }
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay ← $label ${msg.firstLine} body=${msg.body.size}B head=$preview " +
                "public=${msg.headers["Public"] ?: msg.headers["public"]}",
        )
    }

    private fun attachPeer(socket: Socket) {
        val addr = socket.inetAddress
        val isLoopback = addr?.isLoopbackAddress == true
        Log.i(
            CarPlayConsts.TAG,
            "RTSP peer ${addr?.hostAddress} loopback=$isLoopback",
        )
        if (!isLoopback) {
            // Prefer real HU / simulator: drop loopback session if any.
            val old = peer
            if (old != null && old.inetAddress?.isLoopbackAddress == true) {
                old.closeQuietly()
            }
            peer = socket
            streaming.set(false)
        } else if (peer == null) {
            peer = socket
        }
        handlePeer(socket)
    }

    private fun handlePeer(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())
        output = out
        val headerBuf = ByteArrayOutputLimited(64 * 1024)
        while (running.get() && !socket.isClosed) {
            val b = try {
                input.read()
            } catch (_: Throwable) {
                -1
            }
            if (b < 0) break
            // RTSP interleaved binary: '$' + channel + len(2) + payload
            if (b == '$'.code && headerBuf.size() == 0) {
                skipInterleaved(input)
                continue
            }
            headerBuf.write(b)
            if (!headerBuf.endsWithCrlfCrlf()) continue
            val headBytes = headerBuf.toByteArray()
            headerBuf.reset()
            val provisional = RtspMessage.parse(headBytes) ?: continue
            val contentLen = provisional.headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.trim()?.toIntOrNull() ?: 0
            val body = if (contentLen > 0) {
                val buf = ByteArray(contentLen)
                var got = 0
                while (got < contentLen) {
                    val n = input.read(buf, got, contentLen - got)
                    if (n < 0) break
                    got += n
                }
                buf.copyOf(got)
            } else {
                provisional.body
            }
            val msg = provisional.copy(body = body)
            Log.i(CarPlayConsts.TAG, "RTSP ${msg.firstLine} cSeq=${msg.cSeq} body=${body.size}")
            if (msg.firstLine.startsWith("GET ", ignoreCase = true) ||
                msg.firstLine.startsWith("POST ", ignoreCase = true)
            ) {
                dispatchHttp(msg, out)
            } else {
                dispatch(msg, out)
            }
        }
        streaming.set(false)
        output = null
        Log.i(CarPlayConsts.TAG, "RTSP peer closed rtpSent=${rtpSent.get()} rtpBytes=${rtpBytes.get()}")
    }

    private fun skipInterleaved(input: BufferedInputStream) {
        val ch = input.read()
        val lenHi = input.read()
        val lenLo = input.read()
        if (ch < 0 || lenHi < 0 || lenLo < 0) return
        val len = (lenHi shl 8) or lenLo
        var left = len
        val tmp = ByteArray(minOf(len, 4096))
        while (left > 0) {
            val n = input.read(tmp, 0, minOf(tmp.size, left))
            if (n <= 0) break
            left -= n
        }
        Log.d(CarPlayConsts.TAG, "RTSP interleaved inbound ch=$ch len=$len")
    }

    /**
     * diplay / DiPlay probe `_carplay-ctrl` with AirPlay-style HTTP, e.g.
     * `GET /ctrl-int/1/connect` — answer HTTP 2xx. Keep-alive so follow-up
     * pair-setup / RTSP on the same socket is not forced to reconnect.
     */
    private fun dispatchHttp(msg: RtspMessage, out: BufferedOutputStream) {
        val path = msg.firstLine.substringAfter(' ').substringBefore(' ')
        Log.i(CarPlayConsts.TAG, "HTTP control path=$path")
        val isConnect = path.contains("/ctrl-int/", ignoreCase = true) &&
            path.contains("connect", ignoreCase = true)
        val isInfo = path.contains("info", ignoreCase = true)
        val isPair = path.contains("pair-", ignoreCase = true) ||
            path.contains("auth-setup", ignoreCase = true)
        if (isPair) {
            Log.w(
                CarPlayConsts.TAG,
                "HTTP $path — pair-setup/auth not implemented yet; returning empty 200",
            )
        }
        val body = when {
            isInfo -> minimalInfoPlist()
            else -> ByteArray(0)
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Server: AirTunes/220.68\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ${body.size}\r\n")
            // DiPlay often sends Connection: close on connect; still prefer keep-alive
            // so a follow-up request on the same TCP is not rejected mid-handshake.
            append(if (isConnect) "Connection: close\r\n" else "Connection: keep-alive\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        try {
            synchronized(writeLock) {
                out.write(headers)
                if (body.isNotEmpty()) out.write(body)
                out.flush()
            }
            Log.i(CarPlayConsts.TAG, "HTTP 200 for $path body=${body.size}B")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "HTTP reply failed", e)
        }
    }

    /** Tiny binary plist dict so /info probes are not empty MIME mismatches. */
    private fun minimalInfoPlist(): ByteArray {
        // bplist00 + empty dict (0xD0 0x00) is enough for "has a body" probes.
        return byteArrayOf(
            'b'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(),
            's'.code.toByte(), 't'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            0xD0.toByte(), 0x00,
            0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09,
        )
    }

    private fun dispatch(msg: RtspMessage, out: BufferedOutputStream) {
        val method = msg.method ?: return
        val cSeq = msg.cSeq.coerceAtLeast(0)
        when (method) {
            "OPTIONS" -> {
                reply(
                    out,
                    RtspMessage.ok(
                        cSeq,
                        mapOf("Public" to "OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN, GET_PARAMETER, SET_PARAMETER"),
                    ),
                )
            }
            "SETUP" -> {
                val transport = msg.headers.entries
                    .firstOrNull { it.key.equals("Transport", ignoreCase = true) }
                    ?.value
                    .orEmpty()
                val channel = parseInterleavedChannel(transport)
                rtpChannel.set(channel)
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlay audio/video SETUP transport=$transport interleaved=$channel",
                )
                onVideoSetup(CarPlayConsts.DEFAULT_VIDEO_WIDTH, CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
                val replyTransport = if (transport.contains("interleaved", ignoreCase = true)) {
                    transport
                } else {
                    "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"
                }
                reply(
                    out,
                    RtspMessage.ok(
                        cSeq,
                        mapOf(
                            "Session" to "AADisplay1",
                            "Transport" to replyTransport,
                        ),
                    ),
                )
            }
            "RECORD", "ANNOUNCE" -> {
                reply(out, RtspMessage.ok(cSeq))
                streaming.set(true)
                Log.i(CarPlayConsts.TAG, "RTSP streaming armed (RECORD/ANNOUNCE)")
                onSessionReady()
            }
            "TEARDOWN" -> {
                streaming.set(false)
                reply(out, RtspMessage.ok(cSeq))
            }
            "SET_PARAMETER" -> {
                reply(out, RtspMessage.ok(cSeq))
                if (msg.body.isNotEmpty()) {
                    onTouchEvent(0.5f, 0.5f, android.view.MotionEvent.ACTION_DOWN)
                    onTouchEvent(0.5f, 0.5f, android.view.MotionEvent.ACTION_UP)
                }
            }
            else -> reply(out, RtspMessage.ok(cSeq))
        }
    }

    /**
     * Prefer screen dataPort (type 110) when armed; else RTSP interleaved RTP (loopback).
     * @return packets / frames written (0 if not streaming).
     */
    fun sendVideoAccessUnit(
        au: ByteArray,
        ptsUs: Long,
        isKey: Boolean,
        isCodecConfig: Boolean = false,
    ): Int {
        if (isCodecConfig) {
            lastCodecConfig.set(au)
            val screen = screenEgress.get()
            if (screen != null) {
                val ok = screen.sendCodecConfig(au, ptsUs * 1_000L)
                return if (ok) 1 else 0
            }
            return 0
        }
        if (!streaming.get()) return 0
        val screen = screenEgress.get()
        if (screen != null) {
            if (!screen.hasSentConfig()) {
                lastCodecConfig.get()?.let { screen.sendCodecConfig(it, ptsUs * 1_000L) }
            }
            val ok = screen.sendVideo(au, ptsUs * 1_000L, isKey)
            return if (ok) 1 else 0
        }
        val packets = packetizer.packetize(au, ptsUs, markerOnLast = true)
        if (packets.isEmpty()) return 0
        val ch = rtpChannel.get()
        var written = 0
        synchronized(writeLock) {
            val out = output ?: return 0
            try {
                for (pkt in packets) {
                    out.write('$'.code)
                    out.write(ch and 0xFF)
                    out.write((pkt.size shr 8) and 0xFF)
                    out.write(pkt.size and 0xFF)
                    out.write(pkt)
                    written++
                    rtpSent.incrementAndGet()
                    rtpBytes.addAndGet(pkt.size.toLong())
                }
                out.flush()
                if (isKey) {
                    Log.d(
                        CarPlayConsts.TAG,
                        "RTP keyframe packets=$written totalSent=${rtpSent.get()}",
                    )
                }
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "RTP interleaved write failed", e)
                streaming.set(false)
                return 0
            }
        }
        return written
    }

    /** Test hook: arm streaming without HU RECORD (local loop verification). */
    fun armStreamingForTest() {
        streaming.set(true)
        Log.i(CarPlayConsts.TAG, "RTSP streaming armed (test)")
    }

    private fun parseInterleavedChannel(transport: String): Int {
        val m = Regex("interleaved=(\\d+)", RegexOption.IGNORE_CASE).find(transport)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun reply(out: BufferedOutputStream, msg: RtspMessage) {
        try {
            synchronized(writeLock) {
                out.write(msg.toBytes())
                out.flush()
            }
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "RTSP reply failed", e)
        }
    }

    private fun parseAccessoryInfo(body: ByteArray) {
        val root = BinaryPlist.asDict(BinaryPlist.decode(body)) ?: return
        val displays = root["displays"] as? List<*>
        val main = BinaryPlist.asDict(displays?.firstOrNull()) ?: return
        val w = BinaryPlist.intOf(main["widthPixels"])?.toInt()?.and(0xffff)
        val h = BinaryPlist.intOf(main["heightPixels"])?.toInt()?.and(0xffff)
        if (w != null && w > 0 && h != null && h > 0) {
            accessoryTouchMaxX = w
            accessoryTouchMaxY = h
            Log.i(CarPlayConsts.TAG, "accessory /info display ${w}x$h (HID touch space)")
        }
    }

    fun stop() {
        running.set(false)
        streaming.set(false)
        audioEgress.getAndSet(null)?.stop()
        eventChannel.getAndSet(null)?.close()
        screenEgress.getAndSet(null)?.close()
        peer?.closeQuietly()
        peer = null
        output = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }

    private class ByteArrayOutputLimited(private val max: Int) {
        private val data = ArrayList<Byte>(4096)
        fun write(b: Int) {
            if (data.size >= max) data.clear()
            data.add(b.toByte())
        }

        fun size(): Int = data.size

        fun reset() = data.clear()

        fun toByteArray(): ByteArray = ByteArray(data.size) { data[it] }

        fun endsWithCrlfCrlf(): Boolean {
            val n = data.size
            if (n < 4) return false
            return data[n - 4] == '\r'.code.toByte() &&
                data[n - 3] == '\n'.code.toByte() &&
                data[n - 2] == '\r'.code.toByte() &&
                data[n - 1] == '\n'.code.toByte()
        }
    }
}
