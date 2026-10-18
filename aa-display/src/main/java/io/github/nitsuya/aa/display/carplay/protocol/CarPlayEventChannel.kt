package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayWheelController
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AirPlay event channel (TCP [eventPort] from SETUP session).
 *
 * Event keys are the complement of the accessory: we decrypt `Events-Write`
 * and encrypt `Events-Read`. Reversing that fails `ChaCha20-Poly1305 open`
 * on the first HU frame and the socket closes.
 * Accessory → Source: `POST /command` (forceKeyFrame, hidSendReport, …);
 * Source replies encrypted `RTSP/1.0 200 OK`.
 */
class CarPlayEventChannel private constructor(
    private val socket: Socket,
    private val crypto: ControlChannelCrypto,
    private val onForceKeyFrame: () -> Unit,
    private val onTouch: (xNorm: Float, yNorm: Float, action: Int) -> Unit,
    private val touchMaxX: Int,
    private val touchMaxY: Int,
    private val onHidKey: (device: Int, code: Int, pressed: Boolean, extra: Int) -> Unit,
    private val onIapMessage: (ByteArray) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val commandLock = Object()
    private var commandsReplied = 0
    private var lastCommandAt = 0L
    private val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
    @Volatile private var fingerDown = false
    @Volatile private var knobBtns = 0

    /**
     * iPhone answers accessory `POST /command` (modesChanged, iAPSendMessage)
     * and lets that settle before the screen SETUP. Returns how many commands
     * were replied to.
     */
    fun awaitBeforeScreen(budgetMs: Long, quietMs: Long = 150L): Int {
        val deadline = System.currentTimeMillis() + budgetMs
        synchronized(commandLock) {
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (commandsReplied > 0 && now - lastCommandAt >= quietMs) {
                    return commandsReplied
                }
                val remain = deadline - now
                if (remain <= 0) break
                try {
                    commandLock.wait(minOf(quietMs, remain))
                } catch (_: InterruptedException) {
                    break
                }
            }
            return commandsReplied
        }
    }

    fun start() {
        thread(name = "carplay-event", isDaemon = true) {
            val input = BufferedInputStream(socket.getInputStream())
            Log.i(
                CarPlayConsts.TAG,
                "event channel pump touchMax=${touchMaxX}x$touchMaxY",
            )
            while (!closed.get() && !socket.isClosed) {
                val raw = try {
                    crypto.readMessage(input)
                } catch (e: Throwable) {
                    if (!closed.get()) Log.w(CarPlayConsts.TAG, "event read ended", e)
                    break
                } ?: break
                val msg = RtspMessage.parse(raw) ?: continue
                handle(msg)
            }
            close()
        }
    }

    private fun handle(msg: RtspMessage) {
        val path = msg.firstLine.substringAfter(' ', "").substringBefore(' ')
        Log.i(
            CarPlayConsts.TAG,
            "event ← ${msg.firstLine} body=${msg.body.size}B",
        )
        // iPhone: changeModes gets a plist 200 (status+params ~220B), not empty.
        // Empty ACK left Audi black then tear-down ~5s after BORROW_CHANGE.
        var replyBody = ByteArray(0)
        if (msg.method == "POST" && path.contains("command", ignoreCase = true)) {
            replyBody = dispatchCommand(msg.body) ?: ByteArray(0)
        }
        reply200(msg, replyBody)
        synchronized(commandLock) {
            commandsReplied++
            lastCommandAt = System.currentTimeMillis()
            commandLock.notifyAll()
        }
    }

    /** @return optional RTSP 200 body (changeModes status plist); null → empty. */
    private fun dispatchCommand(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        val dict = BinaryPlist.asDict(BinaryPlist.decode(body)) ?: return null
        when (dict["type"]?.toString()) {
            "forceKeyFrame" -> {
                Log.i(CarPlayConsts.TAG, "event forceKeyFrame")
                onForceKeyFrame()
            }
            "hidSendReport" -> {
                val report = dict["hidReport"] as? ByteArray ?: return null
                val uuidRaw = dict["uuid"]
                val uuid = when (uuidRaw) {
                    is String -> uuidRaw.lowercase()
                    is Number -> uuidRaw.toInt().toString()
                    is ByteArray -> uuidRaw.joinToString("") { "%02x".format(it) }
                    else -> uuidRaw?.toString()?.lowercase()
                }
                // DiPlay: hex uuid tails. Audi MMI: numeric uuid + CSM TouchCodec
                // layouts (often 5B LE_FLAG_XY16), not DiPlay's 6B×2 slot format.
                when {
                    uuid == KNOB_HID_UUID || uuid == "2" -> parseKnobReport(report)
                    uuid == MEDIA_HID_UUID || uuid == "3" ->
                        parseOneByteReport(CarPlayWheelController.DEV_MEDIA, report)
                    uuid == TELEPHONY_HID_UUID || uuid == "4" ->
                        parseOneByteReport(CarPlayWheelController.DEV_TELEPHONY, report)
                    uuid == TOUCH_HID_UUID || uuid == TOUCH_HID_UUID_ALT ||
                        uuid == "1" || uuid == "0" || uuid == null ||
                        report.size >= 5 -> parseTouchReport(report)
                    report.size == 1 ->
                        parseOneByteReport(CarPlayWheelController.DEV_MEDIA, report)
                    else -> Log.i(
                        CarPlayConsts.TAG,
                        "event hidSendReport uuid=$uuid len=${report.size} " +
                            "raw=${report.joinToString("") { "%02x".format(it) }} (ignored)",
                    )
                }
            }
            "iAPSendMessage" -> {
                val params = BinaryPlist.asDict(dict["params"])
                val data = params?.get("data") as? ByteArray
                if (data == null || data.isEmpty()) {
                    Log.w(CarPlayConsts.TAG, "event iAPSendMessage missing params.data")
                } else {
                    val head = data.take(12).joinToString(" ") { "%02x".format(it) }
                    Log.i(CarPlayConsts.TAG, "event iAP2 ${data.size}B head=$head")
                    onIapMessage(data)
                }
            }
            "modesChanged" -> {
                Log.i(CarPlayConsts.TAG, "event modesChanged keys=${dict.keys}")
            }
            "changeModes" -> {
                val params = BinaryPlist.asDict(dict["params"])
                val reason = params?.get("reasonStr")?.toString() ?: params?.get("reason")?.toString()
                Log.i(
                    CarPlayConsts.TAG,
                    "event changeModes reason=$reason resources=${params?.get("resources")}",
                )
                // 200 body keeps MainScreen+MainAudio on the phone. An empty ACK left
                // Audi black ~5s after BORROW. Do not also POST modesChanged here:
                // that second claim made Audi BORROW again and then leave RTP running
                // with the speakers silent.
                val modes = phoneOwnedModesParams()
                val encoded = BinaryPlist.encode(
                    mapOf(
                        "status" to 0,
                        "params" to modes,
                    ),
                    groupKeys = true,
                )
                Log.i(CarPlayConsts.TAG, "event changeModes → 200 body=${encoded.size}B")
                return encoded
            }
            "requestUI" -> {
                Log.i(CarPlayConsts.TAG, "event requestUI keys=${dict.keys}")
                onForceKeyFrame()
            }
            else -> Log.i(CarPlayConsts.TAG, "event command type=${dict["type"]}")
        }
        return null
    }

    /** Phone owns MainScreen + MainAudio (entity=1). Needed for Audi volume UI. */
    private fun phoneOwnedModesParams(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(
            linkedMapOf(
                "resourceID" to 1,
                "entity" to 1,
                "permanentEntity" to 1,
            ),
            linkedMapOf(
                "resourceID" to 2,
                "entity" to 1,
                "permanentEntity" to 1,
            ),
        ),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "entity" to 0),
            linkedMapOf("appStateID" to 3, "entity" to 0),
            linkedMapOf("appStateID" to 1, "entity" to 0, "speechMode" to -1),
        ),
    )

    private fun parseTouchReport(report: ByteArray) {
        if (report.size < 4) return
        val decoded = decodeTouchPoint(report) ?: return
        val maxX = touchMaxX.coerceAtLeast(1).toFloat()
        val maxY = touchMaxY.coerceAtLeast(1).toFloat()
        val xNorm = (decoded.x.toFloat() / maxX).coerceIn(0f, 1f)
        val yNorm = (decoded.y.toFloat() / maxY).coerceIn(0f, 1f)
        val down = decoded.tip
        val action = when {
            down && !fingerDown -> MotionEvent.ACTION_DOWN
            down && fingerDown -> MotionEvent.ACTION_MOVE
            !down && fingerDown -> MotionEvent.ACTION_UP
            else -> return
        }
        fingerDown = down
        onTouch(xNorm, yNorm, action)
        if (action != MotionEvent.ACTION_MOVE || touchLogCount < 8) {
            touchLogCount++
            Log.i(
                CarPlayConsts.TAG,
                "event touch action=$action x=$xNorm y=$yNorm " +
                    "px=${decoded.x},${decoded.y} layout=${decoded.layout} " +
                    "rawMax=${touchMaxX}x$touchMaxY",
            )
        }
    }

    private var touchLogCount = 0
    private var chosenTouchLayout: TouchLayout? = null
    private var touchProbeCount = 0
    private val touchFit = IntArray(TouchLayout.entries.size)
    private val touchTotal = IntArray(TouchLayout.entries.size)

    private data class TouchPoint(val x: Int, val y: Int, val tip: Boolean, val layout: TouchLayout)

    /**
     * CSM [TouchCodec] layouts. Audi MMI_8280 uses 5B [flag|x16le|y16le]; DiPlay uses
     * 6B×N [id|down|x16le|y16le]. Probe fits against the /info panel until one wins.
     */
    private enum class TouchLayout {
        /** Audi: `[flag, x_lo, x_hi, y_lo, y_hi]` tip=(flag&1). */
        LE_FLAG_XY16,
        /** DiPlay primary slot: `[id, down, x_lo, x_hi, y_lo, y_hi]`. */
        DIPLAY_SLOT6,
        BE_X16_Y16,
        LE_X16_Y16,
    }

    private fun decodeTouchPoint(report: ByteArray): TouchPoint? {
        val chosen = chosenTouchLayout
        if (chosen != null) {
            return decodeLayout(report, chosen)
        }
        for (layout in TouchLayout.entries) {
            val pt = decodeLayout(report, layout) ?: continue
            val idx = layout.ordinal
            touchTotal[idx]++
            if (pt.x in 0 until touchMaxX && pt.y in 0 until touchMaxY) {
                touchFit[idx]++
            }
        }
        touchProbeCount++
        if (touchProbeCount >= 8) {
            var best: TouchLayout? = null
            var bestFit = 0
            for (layout in TouchLayout.entries) {
                val fit = touchFit[layout.ordinal]
                if (fit > bestFit) {
                    bestFit = fit
                    best = layout
                }
            }
            chosenTouchLayout = best ?: TouchLayout.LE_FLAG_XY16
            Log.i(
                CarPlayConsts.TAG,
                "event touch layout=${chosenTouchLayout} fit=" +
                    TouchLayout.entries.joinToString(",") {
                        "${it.name}:${touchFit[it.ordinal]}/${touchTotal[it.ordinal]}"
                    },
            )
        }
        // Prefer LE_FLAG_XY16 while probing (Audi 5B reports).
        return decodeLayout(report, TouchLayout.LE_FLAG_XY16)
            ?: decodeLayout(report, TouchLayout.DIPLAY_SLOT6)
    }

    private fun decodeLayout(report: ByteArray, layout: TouchLayout): TouchPoint? {
        fun u16le(o: Int) =
            (report[o].toInt() and 0xff) or ((report[o + 1].toInt() and 0xff) shl 8)
        fun u16be(o: Int) =
            ((report[o].toInt() and 0xff) shl 8) or (report[o + 1].toInt() and 0xff)
        return when (layout) {
            TouchLayout.LE_FLAG_XY16 -> {
                if (report.size < 5) return null
                val tip = (report[0].toInt() and 1) != 0
                TouchPoint(u16le(1), u16le(3), tip, layout)
            }
            TouchLayout.DIPLAY_SLOT6 -> {
                if (report.size < 6) return null
                val tip = report[1].toInt() and 0xff != 0
                TouchPoint(u16le(2), u16le(4), tip, layout)
            }
            TouchLayout.BE_X16_Y16 -> {
                if (report.size < 6) return null
                val b = report[1].toInt()
                val tip = (b and 1) != 0 || (b and 0x80) != 0
                TouchPoint(u16be(2), u16be(4), tip, layout)
            }
            TouchLayout.LE_X16_Y16 -> {
                if (report.size < 6) return null
                val b = report[1].toInt()
                val tip = (b and 1) != 0 || (b and 0x80) != 0
                TouchPoint(u16le(2), u16le(4), tip, layout)
            }
        }
    }

    /**
     * DiPlay [AirPlayHid.knobReport]: 4 bytes — [0] buttons (0x01 select / 0x02 home /
     * 0x04 back), [1..2] absolute x/y, [3] relative wheel (signed 8 bit).
     */
    private fun parseKnobReport(report: ByteArray) {
        if (report.size < 4) return
        val btns = report[0].toInt() and 0xff
        val wheel = report[3].toInt() // signed
        val prev = knobBtns
        knobBtns = btns
        if (btns != 0) {
            if (btns and 0x01 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_SELECT, true, 0)
            if (btns and 0x02 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_HOME, true, 0)
            if (btns and 0x04 != 0) onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_BACK, true, 0)
        } else if (prev != 0) {
            onHidKey(CarPlayWheelController.DEV_KNOB, 0, false, 0)
        }
        if (wheel != 0) {
            onHidKey(CarPlayWheelController.DEV_KNOB, CarPlayWheelController.KNOB_WHEEL, true, wheel)
        }
    }

    /** Media / telephony: 1-byte usage index (0 = release). */
    private fun parseOneByteReport(device: Int, report: ByteArray) {
        if (report.isEmpty()) return
        val v = report[0].toInt() and 0xff
        onHidKey(device, if (v == 0) 0 else v, v != 0, v)
    }

    private fun gmtHttpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun reply200(request: RtspMessage, body: ByteArray = ByteArray(0)) {
        val cseq = request.headers.entries
            .firstOrNull { it.key.equals("CSeq", ignoreCase = true) }
            ?.value?.trim() ?: "0"
        Log.i(CarPlayConsts.TAG, "event → 200 cseq=$cseq body=${body.size}B")
        // iPhone event 200 on this MMI: Date + Content-Length, no CSeq.
        // A CSeq-only 200 leaves the HU retransmitting the same detect POST.
        val headers = linkedMapOf(
            "Date" to gmtHttpDate(),
            "Content-Length" to body.size.toString(),
        )
        if (body.isNotEmpty()) {
            headers["Content-Type"] = "application/x-apple-binary-plist"
        }
        val resp = RtspMessage("RTSP/1.0 200 OK", headers, body)
        try {
            synchronized(writeLock) {
                crypto.writeMessage(out, resp.toBytes())
                out.flush()
            }
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "event reply failed", e)
            close()
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
        Log.i(CarPlayConsts.TAG, "event channel closed")
    }

    companion object {
        /** DiPlay AirPlayHid device uuids (lowercase hex, no 0x prefix on the wire). */
        private const val TOUCH_HID_UUID = "2a2a2a2a"
        private const val TOUCH_HID_UUID_ALT = "2A2A2A2A"
        private const val KNOB_HID_UUID = "2a2a2a2b"
        private const val MEDIA_HID_UUID = "2a2a2a2c"
        private const val TELEPHONY_HID_UUID = "2a2a2a2d"

        fun open(
            host: InetAddress,
            eventPort: Int,
            sharedSecret: ByteArray,
            touchMaxX: Int,
            touchMaxY: Int,
            onForceKeyFrame: () -> Unit,
            onTouch: (Float, Float, Int) -> Unit,
            onHidKey: (Int, Int, Boolean, Int) -> Unit,
            onIapMessage: (ByteArray) -> Unit = {},
        ): CarPlayEventChannel? {
            if (eventPort <= 0 || eventPort > 65535) {
                Log.w(CarPlayConsts.TAG, "eventPort invalid=$eventPort")
                return null
            }
            return try {
                val readKey = HkdfSha512.derive(
                    sharedSecret,
                    "Events-Salt",
                    "Events-Write-Encryption-Key",
                    32,
                )
                val writeKey = HkdfSha512.derive(
                    sharedSecret,
                    "Events-Salt",
                    "Events-Read-Encryption-Key",
                    32,
                )
                val socket = io.github.nitsuya.aa.display.carplay.CarPlayAccessoryNet.openTcp()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, eventPort), 10_000)
                socket.soTimeout = 0
                val channel = CarPlayEventChannel(
                    socket = socket,
                    crypto = ControlChannelCrypto(writeKey, readKey),
                    onForceKeyFrame = onForceKeyFrame,
                    onTouch = onTouch,
                    touchMaxX = touchMaxX,
                    touchMaxY = touchMaxY,
                    onHidKey = onHidKey,
                    onIapMessage = onIapMessage,
                )
                Log.i(
                    CarPlayConsts.TAG,
                    "event channel connected $host:$eventPort touchMax=${touchMaxX}x$touchMaxY",
                )
                channel
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "event channel connect failed $host:$eventPort", e)
                null
            }
        }
    }
}
