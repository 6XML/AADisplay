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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AirPlay event channel (TCP [eventPort] from SETUP session).
 *
 * DiPlay [AirPlaySession.acceptEvent] derives the event keys from its own
 * perspective: its write key = `Events-Write-Encryption-Key`, its read key =
 * `Events-Read-Encryption-Key` (unlike the control channel, where DiPlay swaps).
 * The label pins the wire direction, so we decrypt with DiPlay's write key and
 * encrypt with DiPlay's read key. Then HAP ControlCipher framing.
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
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
    @Volatile private var fingerDown = false
    @Volatile private var knobBtns = 0

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
        if (msg.method == "POST" && path.contains("command", ignoreCase = true)) {
            dispatchCommand(msg.body)
        }
        reply200(msg)
    }

    private fun dispatchCommand(body: ByteArray) {
        if (body.isEmpty()) return
        val dict = BinaryPlist.asDict(BinaryPlist.decode(body)) ?: return
        when (dict["type"]?.toString()) {
            "forceKeyFrame" -> {
                Log.i(CarPlayConsts.TAG, "event forceKeyFrame")
                onForceKeyFrame()
            }
            "hidSendReport" -> {
                val uuid = dict["uuid"]?.toString()?.lowercase() ?: return
                val report = dict["hidReport"] as? ByteArray ?: return
                when (uuid) {
                    TOUCH_HID_UUID, TOUCH_HID_UUID_ALT -> parseTouchReport(report)
                    KNOB_HID_UUID -> parseKnobReport(report)
                    MEDIA_HID_UUID -> parseOneByteReport(CarPlayWheelController.DEV_MEDIA, report)
                    TELEPHONY_HID_UUID -> parseOneByteReport(CarPlayWheelController.DEV_TELEPHONY, report)
                    else -> Log.d(CarPlayConsts.TAG, "event hidSendReport uuid=$uuid len=${report.size} (ignored)")
                }
            }
            else -> Log.d(CarPlayConsts.TAG, "event command type=${dict["type"]}")
        }
    }

    private fun parseTouchReport(report: ByteArray) {
        // DiPlay AirPlayHid.touchReport: 2 slots × 6 bytes (id, down, x_le16, y_le16) in pixel space.
        if (report.size < 6) return
        val maxX = touchMaxX.coerceAtLeast(1).toFloat()
        val maxY = touchMaxY.coerceAtLeast(1).toFloat()
        var primaryDown: Boolean? = null
        var xNorm = 0f
        var yNorm = 0f
        val slots = minOf(2, report.size / 6)
        for (slot in 0 until slots) {
            val o = slot * 6
            val down = report[o + 1].toInt() and 0xff != 0
            val x = (report[o + 2].toInt() and 0xff) or ((report[o + 3].toInt() and 0xff) shl 8)
            val y = (report[o + 4].toInt() and 0xff) or ((report[o + 5].toInt() and 0xff) shl 8)
            if (slot == 0 || (down && primaryDown != true)) {
                primaryDown = down
                xNorm = (x.toFloat() / maxX).coerceIn(0f, 1f)
                yNorm = (y.toFloat() / maxY).coerceIn(0f, 1f)
            }
        }
        val down = primaryDown ?: return
        val action = when {
            down && !fingerDown -> MotionEvent.ACTION_DOWN
            down && fingerDown -> MotionEvent.ACTION_MOVE
            !down && fingerDown -> MotionEvent.ACTION_UP
            else -> return
        }
        fingerDown = down
        onTouch(xNorm, yNorm, action)
        if (action != MotionEvent.ACTION_MOVE) {
            Log.i(
                CarPlayConsts.TAG,
                "event touch action=$action x=$xNorm y=$yNorm rawMax=${touchMaxX}x$touchMaxY",
            )
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

    private fun reply200(request: RtspMessage) {
        val cseq = request.headers.entries
            .firstOrNull { it.key.equals("CSeq", ignoreCase = true) }
            ?.value?.trim() ?: "0"
        val resp = RtspMessage(
            "RTSP/1.0 200 OK",
            linkedMapOf("CSeq" to cseq),
        )
        try {
            synchronized(writeLock) {
                crypto.writeMessage(out, resp.toBytes())
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
        ): CarPlayEventChannel? {
            if (eventPort <= 0 || eventPort > 65535) {
                Log.w(CarPlayConsts.TAG, "eventPort invalid=$eventPort")
                return null
            }
            return try {
                // DiPlay [AirPlaySession.acceptEvent] derives the event keys from its own
                // perspective with no swap (unlike its control channel, which swaps):
                // its writeKey = HKDF(shared, Events-Salt, "Events-Write-Encryption-Key"),
                // its readKey  = HKDF(shared, Events-Salt, "Events-Read-Encryption-Key").
                // To complement: we decrypt with the "Write"-labeled key and encrypt with
                // the "Read"-labeled key.
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
                val socket = Socket()
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
                )
                Log.i(
                    CarPlayConsts.TAG,
                    "event channel connected $host:$eventPort touchMax=${touchMaxX}x$touchMaxY",
                )
                channel.start()
                channel
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "event channel connect failed $host:$eventPort", e)
                null
            }
        }
    }
}
