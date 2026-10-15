package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Session
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
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
    private var glCompositor: CarPlayGlCompositor? = null
    private val keyframesLogged = AtomicLong(0)

    /**
     * Current supply frame rate. Starts at [CarPlayConsts.DEFAULT_VIDEO_FPS]; sustained
     * under-delivery drops to [CarPlayConsts.DEGRADED_VIDEO_FPS], and a sustained healthy
     * 30 fps supply climbs back. The DiPlay host decodes at its own user fps setting, so
     * this only throttles our production + bandwidth, never the wire format.
     */
    @Volatile
    private var supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
    private var badRateWindows = 0
    private var goodRateWindows = 0
    private var lastDropCount = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
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

        supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
        badRateWindows = 0
        goodRateWindows = 0
        lastDropCount = encoder.dropCount()
        val rateCb: (Int, Float) -> Unit = { target, actual -> onRateReport(target, actual) }
        tee.onRateReport = rateCb
        glCompositor?.onRateReport = rateCb

        val rtspSession = CarPlayRtspSession(
            onVideoSetup = { w, h -> ensureEncoder(w, h) },
            onSessionReady = {
                ensureEncoder(CarPlayConsts.DEFAULT_VIDEO_WIDTH, CarPlayConsts.DEFAULT_VIDEO_HEIGHT)
                // CarPlay session live on the car → drive NowPlaying (cluster card) from the
                // shared lyric store; the host re-triggers its caption per title change.
                iap2?.let { CarPlayNowPlaying.start(appContext, it) }
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
            onNowPlayingUpdates = { CarPlayNowPlaying.onUpdatesRequested() },
            onFileTransferDatagram = { dg -> CarPlayNowPlaying.onFileTransferDatagram(dg) },
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
        // AA shell tee vs Host GL are mutually exclusive producers of the encoder Surface.
        if (running.get() && encoder.currentFps() > 0) {
            mainHandler.post {
                if (running.get()) bindGlOrTee(supplyFps)
            }
        }
    }

    fun attachFrameProvider(provider: FrameEgressTee.FrameProvider?) {
        tee.attachFrameProvider(provider)
    }

    /**
     * Host GPU path: pane Surfaces feed [CarPlayGlCompositor], which owns the encoder
     * input Surface. Canvas [FrameEgressTee] is stopped while GL is attached so the two
     * producers never contend.
     */
    fun attachGlCompositor(compositor: CarPlayGlCompositor?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachGlCompositorMain(compositor)
        } else {
            mainHandler.post { attachGlCompositorMain(compositor) }
        }
    }

    private fun attachGlCompositorMain(compositor: CarPlayGlCompositor?) {
        glCompositor?.let { prev ->
            if (prev !== compositor) {
                prev.onRateReport = null
                prev.detachEncoder()
            }
        }
        glCompositor = compositor
        compositor?.onRateReport = { target, actual -> onRateReport(target, actual) }
        if (!running.get()) return
        // Re-bind if the encoder is already live (Host attached after SETUP).
        val encW = CarPlayConsts.ENCODE_WIDTH
        val encH = CarPlayConsts.ENCODE_HEIGHT
        val fps = supplyFps
        if (compositor != null && encoder.hasSurfaceInput() && encoder.isRunningAt(encW, encH, fps)) {
            bindGlOrTee(fps)
        } else if (compositor == null && encoder.currentFps() > 0) {
            bindGlOrTee(fps)
        }
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
        // Serialized on the main looper: DiPlay re-SETUP drives this from worker threads and
        // a concurrent codec start/stop crashed the app (see EXECUTION.md §14).
        if (Looper.myLooper() == Looper.getMainLooper()) ensureEncoderMain(width, height)
        else mainHandler.post { ensureEncoderMain(width, height) }
    }

    private fun ensureEncoderMain(width: Int, height: Int) {
        if (!running.get()) return
        val encW = CarPlayConsts.ENCODE_WIDTH
        val encH = CarPlayConsts.ENCODE_HEIGHT
        val fps = supplyFps
        val wantGl = glCompositor != null && !ProjectionSessionGate.shouldTeeFromAaShell()
        // onVideoSetup + onSessionReady both fire per SETUP; a second start() in the same
        // tick races the first codec release and crashed the app — skip when already armed.
        if (encoder.isRunningAt(encW, encH, fps)) {
            // Re-bind GL if Host attached after an early tee-armed SETUP.
            if (wantGl && !glCompositor!!.isEncoderAttached()) {
                bindGlOrTee(fps)
            }
            return
        }
        Log.i(
            CarPlayConsts.TAG,
            "ensureEncoder setup=${width}x$height → encode=${encW}x$encH@${fps}fps",
        )
        bindGlOrTee(fps)
    }

    /**
     * Host GL path owns the encoder Surface exclusively. AA shell / Canvas Host / test
     * pattern still go through [FrameEgressTee].
     *
     * Important: a MediaCodec input [android.view.Surface] that was ever fed via
     * [android.view.Surface.lockCanvas] (tee path) stays connected as
     * `NATIVE_WINDOW_API_CPU` on several OEMs (Samsung). Rebinding that same Surface as
     * an EGL window then fails with `eglCreateWindowSurface` / EGL_BAD_ALLOC
     * ("already connected"). Always mint a fresh input Surface when (re)attaching GL.
     */
    private fun bindGlOrTee(fps: Int) {
        val encW = CarPlayConsts.ENCODE_WIDTH
        val encH = CarPlayConsts.ENCODE_HEIGHT
        val gl = glCompositor
        val wantGl = gl != null && !ProjectionSessionGate.shouldTeeFromAaShell()
        if (wantGl) {
            tee.setGlExclusive(true)
            if (gl!!.isEncoderAttached() &&
                encoder.hasSurfaceInput() &&
                encoder.isRunningAt(encW, encH, fps)
            ) {
                Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=gl-compositor (kept)")
                return
            }
            gl.detachEncoder()
            encoder.start(encW, encH, fps)
            val surface = encoder.inputSurfaceOrNull()
            if (surface == null) {
                Log.w(CarPlayConsts.TAG, "GL bind: no surface input — tee-canvas fallback")
                tee.setGlExclusive(false)
                tee.start(encoder, encW, encH, fps)
                Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=tee-canvas")
                return
            }
            if (!gl.attachEncoder(surface, encW, encH, fps)) {
                // Surface may still be contaminated; give Canvas a virgin one.
                Log.w(CarPlayConsts.TAG, "GL attachEncoder failed — tee-canvas fallback")
                encoder.start(encW, encH, fps)
                tee.setGlExclusive(false)
                tee.start(encoder, encW, encH, fps)
                Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=tee-canvas")
                return
            }
            Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=gl-compositor")
        } else {
            gl?.detachEncoder()
            tee.setGlExclusive(false)
            if (!encoder.isRunningAt(encW, encH, fps) || !encoder.hasSurfaceInput()) {
                encoder.start(encW, encH, fps)
            }
            tee.start(encoder, encW, encH, fps)
            Log.i(CarPlayConsts.TAG, "CarPlaySourceSession feed=tee-canvas")
        }
    }

    /**
     * Pump-thread rate report (10 s window): under-delivery or encoder back-pressure drops
     * the supply 60→30 after [DEGRADE_WINDOWS] bad windows; a healthy 30 fps supply recovers
     * to 60 after [RECOVER_WINDOWS] good ones. The DiPlay host decodes at its own user fps,
     * so this only throttles our production + bandwidth.
     */
    private fun onRateReport(targetFps: Int, actualFps: Float) {
        if (!running.get() || encoder.currentFps() == 0) return
        val drops = encoder.dropCount()
        val dropRatePerSec = (drops - lastDropCount) * 1000f / RATE_REPORT_WINDOW_MS
        lastDropCount = drops
        val poor = actualFps < targetFps * CarPlayConsts.DEGRADE_RATE_FRACTION ||
            dropRatePerSec > targetFps * 0.15f
        val good = !poor && actualFps >= targetFps * 0.8f
        if (targetFps >= CarPlayConsts.DEFAULT_VIDEO_FPS) {
            badRateWindows = if (poor) badRateWindows + 1 else 0
            goodRateWindows = 0
            if (badRateWindows >= DEGRADE_WINDOWS) {
                badRateWindows = 0
                Log.w(
                    CarPlayConsts.TAG,
                    "CarPlay supply degraded 60→30 (rate=${"%.1f".format(actualFps)}fps " +
                        "dropRate=${"%.1f".format(dropRatePerSec)}fps)",
                )
                applySupplyFps(CarPlayConsts.DEGRADED_VIDEO_FPS)
            }
        } else {
            goodRateWindows = if (good) goodRateWindows + 1 else 0
            badRateWindows = 0
            if (goodRateWindows >= RECOVER_WINDOWS) {
                goodRateWindows = 0
                Log.i(
                    CarPlayConsts.TAG,
                    "CarPlay supply recovered 30→60 (rate=${"%.1f".format(actualFps)}fps)",
                )
                applySupplyFps(CarPlayConsts.DEFAULT_VIDEO_FPS)
            }
        }
    }

    private fun applySupplyFps(fps: Int) {
        supplyFps = fps
        mainHandler.post {
            if (!running.get()) return@post
            val encW = CarPlayConsts.ENCODE_WIDTH
            val encH = CarPlayConsts.ENCODE_HEIGHT
            if (encoder.isRunningAt(encW, encH, fps) &&
                (glCompositor == null || glCompositor!!.isEncoderAttached() ||
                    ProjectionSessionGate.shouldTeeFromAaShell())
            ) {
                return@post
            }
            lastDropCount = encoder.dropCount()
            glCompositor?.detachEncoder()
            bindGlOrTee(fps)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val (sent, bytes) = rtsp?.rtpStats() ?: (0L to 0L)
        Log.i(CarPlayConsts.TAG, "CarPlaySourceSession stop rtpSent=$sent rtpBytes=$bytes")
        tee.onRateReport = null
        glCompositor?.onRateReport = null
        glCompositor?.detachEncoder()
        tee.setGlExclusive(false)
        tee.stop()
        encoder.stop()
        supplyFps = CarPlayConsts.DEFAULT_VIDEO_FPS
        badRateWindows = 0
        goodRateWindows = 0
        lastDropCount = 0L
        CarPlayNowPlaying.stop()
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

    companion object {
        /** Matches [FrameEgressTee] evaluation window. */
        private const val RATE_REPORT_WINDOW_MS = 10_000L

        /** 8 x 10 s of poor supply before the 60 -> 30 downgrade (GL path validation). */
        private const val DEGRADE_WINDOWS = 8

        /** 6 x 10 s of a healthy 30 fps supply before climbing back to 60. */
        private const val RECOVER_WINDOWS = 6
    }
}