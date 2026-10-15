package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.view.View
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Session
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates Bonjour + BT iAP2 + RTSP + AVC + RTP for one wireless CarPlay Source session.
 */
class CarPlaySourceSession(
    private val appContext: Context,
) {
    private val running = AtomicBoolean(false)
    val tee = FrameEgressTee()
    private val keyframesLogged = AtomicLong(0)
    private val encoder = CarPlayVideoEncoder { bytes, ptsUs, key, config ->
        val written = rtsp?.sendVideoAccessUnit(bytes, ptsUs, key, config) ?: 0
        if (key || config) {
            val n = keyframesLogged.incrementAndGet()
            if (n <= 5 || n % 30L == 0L) {
                Log.d(
                    CarPlayConsts.TAG,
                    "encoded ${if (config) "config" else "keyframe"} bytes=${bytes.size} " +
                        "ptsUs=$ptsUs packets=$written streaming=${rtsp?.isStreaming() == true}",
                )
            }
        }
    }
    private var bonjour: CarPlayBonjour? = null
    private var bluetooth: CarPlayBluetoothLink? = null
    private var rtsp: CarPlayRtspSession? = null
    private var iap2: Iap2Session? = null
    private var wifiHandoff: CarPlayWifiHandoff? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession start")
        ProjectionSessionGate.setCarPlayLive(true)
        wifiHandoff = CarPlayWifiHandoff(appContext)

        val rtspSession = CarPlayRtspSession(
            onVideoSetup = { w, h -> ensureEncoder(w, h) },
            onSessionReady = {
                ensureEncoder(CarPlayConsts.DEFAULT_VIDEO_WIDTH, CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
            },
            onTouchEvent = { x, y, action -> CarPlayInputBridge.injectNormalized(x, y, action) },
            onForceKeyFrame = { encoder.requestKeyFrame() },
        )
        rtsp = rtspSession
        rtspSession.startServer()

        val bj = CarPlayBonjour(appContext) { info -> onBonjourResolved(info) }
        bonjour = bj
        Thread {
            var waits = 0
            while (running.get() && rtspSession.localPort <= 0 && waits < 50) {
                Thread.sleep(20)
                waits++
            }
            val port = rtspSession.localPort
            if (port > 0) {
                bj.registerControl(port)
            }
            bj.startDiscovery()
            if (BuildConfig.DEBUG && CarPlayConsts.ENABLE_LOCAL_RTSP_LOOPBACK) {
                CarPlayLocalLoopback.armAfterServerReady(
                    portProvider = { rtspSession.localPort },
                    running = { running.get() },
                )
            }
        }.start()

        val link = CarPlayBluetoothLink(
            appContext,
            onConnected = { iap2?.onTransportConnected() },
            onDisconnected = { iap2?.onTransportClosed() },
            onBytes = { bytes -> iap2?.onBytes(bytes) },
        )
        bluetooth = link
        iap2 = Iap2Session(
            sendRaw = { bytes -> link.sendRaw(bytes) },
            bluetoothTransportId = { link.localAddress() },
            onLinkReady = {
                Log.i(CarPlayConsts.TAG, "iAP link ready → Identification")
            },
            onIdentificationComplete = {
                Log.i(CarPlayConsts.TAG, "iAP identification done → Auth")
            },
            onAuthComplete = {
                Log.i(CarPlayConsts.TAG, "iAP auth done → WiFi / CarPlayStartSession")
            },
            onWifiConfig = { wifi ->
                // Join only; dial waits for CarPlayStartSession + network ready.
                wifiHandoff?.ensureAccessoryWifi(wifi.ssid, wifi.passphrase, wifi.channel) {}
            },
            onCarPlayStart = { start ->
                val handoff = wifiHandoff
                if (handoff == null || start.ssid.isNullOrBlank()) {
                    dialCarPlayControl(start)
                } else {
                    handoff.ensureAccessoryWifi(start.ssid, start.passphrase, start.channel) {
                        dialCarPlayControl(start)
                    }
                }
            },
        )
        link.start()

        ensureEncoder(CarPlayConsts.DEFAULT_VIDEO_WIDTH, CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
    }

    private fun dialCarPlayControl(start: Iap2Csm.CarPlayStart) {
        val port = start.port ?: return
        if (port <= 0 || port > 65535) return
        // Prefer explicit IP from StartSession; else wait for Bonjour discovery.
        val host = start.ipAddresses.firstOrNull { it.isNotBlank() && !it.startsWith("fe80:", ignoreCase = true) }
            ?: start.ipAddresses.firstOrNull()
        if (host.isNullOrBlank()) {
            Log.i(
                CarPlayConsts.TAG,
                "CarPlayStartSession port=$port without IPv4 — rely on Bonjour / inbound TCP",
            )
            return
        }
        Thread({
            val addr = try {
                InetAddress.getByName(host)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "StartSession resolve failed $host", e)
                return@Thread
            }
            // Handoff / DHCP may still be settling after bindProcessToNetwork.
            var lastErr: Throwable? = null
            for (attempt in 1..12) {
                if (!running.get()) return@Thread
                try {
                    Log.i(
                        CarPlayConsts.TAG,
                        "AirPlay source dial from StartSession $host:$port " +
                            "deviceId=${start.deviceId} attempt=$attempt",
                    )
                    // Probe reachability before committing the AirPlay handshake thread.
                    java.net.Socket().use { probe ->
                        probe.connect(java.net.InetSocketAddress(addr, port), 2_500)
                    }
                    rtsp?.connectAsAirPlaySource(addr, port, start.deviceId)
                    return@Thread
                } catch (e: Throwable) {
                    lastErr = e
                    Log.w(
                        CarPlayConsts.TAG,
                        "AirPlay dial attempt=$attempt failed: ${e.message}",
                    )
                    try {
                        Thread.sleep(750L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            Log.e(CarPlayConsts.TAG, "StartSession dial failed $host:$port after retries", lastErr)
        }, "carplay-start-dial").apply { isDaemon = true }.start()
    }

    fun attachShellView(view: View?) {
        tee.attachShell(view)
        if (view != null) {
            CarPlayInputBridge.shellWidthPx = view.width.coerceAtLeast(1)
            CarPlayInputBridge.shellHeightPx = view.height.coerceAtLeast(1)
        }
    }

    fun attachFrameProvider(provider: FrameEgressTee.FrameProvider?) {
        tee.attachFrameProvider(provider)
    }

    fun onShellSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            CarPlayInputBridge.shellWidthPx = width
            CarPlayInputBridge.shellHeightPx = height
        }
    }

    private fun onBonjourResolved(info: NsdServiceInfo) {
        val host = info.host ?: return
        val port = info.port
        // Ignore our own advertisement.
        if (port == rtsp?.localPort) return
        Log.i(CarPlayConsts.TAG, "dialing resolved CarPlay control $host:$port")
        rtsp?.connect(host, port)
    }

    /**
     * Arms encoder + tee at [CarPlayConsts.ENCODE_WIDTH]x[ENCODE_HEIGHT] regardless of the
     * size in the SETUP record: the HU scales the decoded frame to its own display, while the
     * RGB→NV12 conversion cost scales with pixels, so a fixed lower resolution wins.
     */
    private fun ensureEncoder(width: Int, height: Int) {
        if (!running.get()) return
        val encW = CarPlayConsts.ENCODE_WIDTH
        val encH = CarPlayConsts.ENCODE_HEIGHT
        // onVideoSetup + onSessionReady both fire per SETUP; a second start() in the same
        // tick races the first codec release and crashed the app — skip when already armed.
        if (encoder.isRunningAt(encW, encH)) return
        Log.i(CarPlayConsts.TAG, "ensureEncoder setup=${width}x$height → encode=${encW}x$encH")
        encoder.start(encW, encH)
        tee.start(encoder, encW, encH)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val (sent, bytes) = rtsp?.rtpStats() ?: (0L to 0L)
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession stop rtpSent=$sent rtpBytes=$bytes")
        tee.stop()
        encoder.stop()
        wifiHandoff?.release()
        wifiHandoff = null
        bluetooth?.stop()
        bluetooth = null
        iap2 = null
        bonjour?.stop()
        bonjour = null
        rtsp?.stop()
        rtsp = null
        ProjectionSessionGate.setCarPlayLive(false)
    }
}
