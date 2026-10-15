package io.github.nitsuya.aa.display.carplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Composites content into [CarPlayVideoEncoder] at a fixed supply cadence.
 *
 * Temporary Canvas fallback only. CarPlay video is exclusive to [CarPlayGlCompositor]
 * (Host); AA shell is never teed. Until Host attaches, sources are:
 * 1. A [FrameProvider] (legacy Canvas Host, unused on the GL path).
 * 2. A test pattern (pipeline verification — should be brief).
 *
 * Host GPU path owns the encoder Surface when [setGlExclusive] is true — this tee must
 * not [CarPlayVideoEncoder.encodeCanvas] then.
 *
 * Scheduling: a dedicated pump thread with its own [Looper] posts one frame every
 * `1000/fps` ms via [Handler] — **not** [android.view.Choreographer]. Host-only /
 * FGS-background sessions have no visible window; OEM Choreographer vsync then collapses
 * to ~10–15 Hz and DiPlay sees stutter / flash despite a live RTSP link. The AA shell
 * view contains TextureViews and must be drawn on the main looper, so shell frames are
 * posted to the main handler; provider / test-pattern sources composite on the pump.
 *
 * The delivered frame rate is reported every 10 s through [onRateReport] so the session
 * can drop the supply to [CarPlayConsts.DEGRADED_VIDEO_FPS] when the link or the main
 * thread cannot hold [CarPlayConsts.DEFAULT_VIDEO_FPS].
 */
class FrameEgressTee {
    /** Draws current pane content into [canvas]; false when nothing is available. */
    fun interface FrameProvider {
        fun draw(canvas: Canvas, width: Int, height: Int): Boolean
    }

    /** (targetFps, actualFps) after each 10 s evaluation window. */
    var onRateReport: ((Int, Float) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val glExclusive = AtomicBoolean(false)
    private val shellView = AtomicReference<View?>(null)
    private val frameProvider = AtomicReference<FrameProvider?>(null)
    private val outBitmap = AtomicReference<Bitmap?>(null)
    private var encoder: CarPlayVideoEncoder? = null
    private var width = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    private var height = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
    private var fps = CarPlayConsts.DEFAULT_VIDEO_FPS
    private var frameIntervalMs = 1_000L / CarPlayConsts.DEFAULT_VIDEO_FPS
    private var frameIndex = 0

    // Rate evaluation (pump thread). [deliveredFrames] is incremented where the frame is
    // actually handed to the encoder (main thread for shell frames, pump thread otherwise).
    private val deliveredFrames = AtomicInteger(0)
    private var windowStart = 0L

    private var pumpThread: Thread? = null
    private var pumpHandler: Handler? = null
    private val pumpTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val handler = pumpHandler ?: return
            val tickStart = SystemClock.uptimeMillis()
            try {
                scheduleFrame()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "FrameEgressTee pump failed", e)
            }
            evaluateRate()
            if (!running.get()) return
            val nextAt = tickStart + frameIntervalMs
            val delay = (nextAt - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            handler.postDelayed(this, delay)
        }
    }

    fun attachShell(view: View?) {
        shellView.set(view)
    }

    fun attachFrameProvider(provider: FrameEgressTee.FrameProvider?) {
        frameProvider.set(provider)
    }

    /**
     * When true, Host [CarPlayGlCompositor] owns the encoder Surface — [start] is a no-op
     * and any in-flight pump stops so Canvas and GLES never contend.
     */
    fun setGlExclusive(exclusive: Boolean) {
        glExclusive.set(exclusive)
        if (exclusive) stop()
    }

    /**
     * Start/stop mutate state the pump thread reads; DiPlay re-SETUP drives them from
     * worker threads, so all mutation is serialized onto the main looper.
     */
    fun start(encoder: CarPlayVideoEncoder, width: Int, height: Int, fps: Int = CarPlayConsts.DEFAULT_VIDEO_FPS) {
        val run = {
            startInternal(encoder, width, height, fps)
        }
        if (isMainLooper()) run() else mainHandler.post(run)
    }

    private fun startInternal(encoder: CarPlayVideoEncoder, width: Int, height: Int, fps: Int): Unit {
        if (glExclusive.get()) {
            Log.i(CarPlayConsts.TAG, "FrameEgressTee start skipped (glExclusive)")
            return
        }
        stopInternal()
        this.encoder = encoder
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        this.fps = fps.coerceIn(1, 120)
        this.frameIntervalMs = (1_000L / this.fps).coerceAtLeast(1L)
        frameIndex = 0
        deliveredFrames.set(0)
        windowStart = SystemClock.uptimeMillis()
        running.set(true)
        startPumpThread()
        Log.i(
            CarPlayConsts.TAG,
            "FrameEgressTee start ${this.width}x${this.height}@${this.fps}fps " +
                "interval=${this.frameIntervalMs}ms " +
                "shell=${shellView.get() != null} provider=${frameProvider.get() != null}",
        )
    }

    fun stop() {
        if (isMainLooper()) stopInternal() else mainHandler.post { stopInternal() }
    }

    private fun isMainLooper(): Boolean =
        Looper.myLooper() == Looper.getMainLooper()

    private fun stopInternal(): Unit {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        // Remove the cadence tick on the pump looper, then quit it. running=false already
        // stops any in-flight re-post.
        val pump = pumpHandler
        pump?.let { handler ->
            handler.post {
                handler.removeCallbacks(pumpTick)
                handler.looper.quitSafely()
            }
        }
        pumpThread = null
        pumpHandler = null
        encoder = null
        outBitmap.get()?.recycle()
        outBitmap.set(null)
    }

    /**
     * One supply tick: hand the current best source to the encoder. Shell frames hop to the
     * main looper (TextureView rule); provider / test-pattern frames composite on the pump.
     */
    private fun scheduleFrame() {
        if (glExclusive.get()) return
        val enc = encoder ?: return
        val view = shellView.get()
        val useShell = view != null && view.width > 0 && view.height > 0 &&
            ProjectionSessionGate.shouldTeeFromAaShell()
        if (useShell) {
            mainHandler.post {
                if (!running.get() || enc !== encoder) return@post
                if (enc.hasSurfaceInput()) {
                    try {
                        enc.encodeCanvas { drawShell(it) }
                        deliveredFrames.incrementAndGet()
                    } catch (e: Throwable) {
                        Log.e(CarPlayConsts.TAG, "FrameEgressTee shell pump failed", e)
                    }
                } else {
                    // Buffer-input fallback. The view may have detached between the pump
                    // tick and this main-thread hop — re-check before drawing.
                    if (view.width > 0 && view.height > 0) {
                        val b = captureView(view)
                        try {
                            enc.encodeBitmap(b)
                            deliveredFrames.incrementAndGet()
                        } finally {
                            b.recycle()
                        }
                    }
                }
            }
            return
        }
        if (enc.hasSurfaceInput()) {
            try {
                enc.encodeCanvas { drawContent(it) }
                deliveredFrames.incrementAndGet()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "FrameEgressTee pump failed", e)
            }
        } else {
            // Buffer-input fallback.
            val b = outBitmap.get()?.takeIf { it.width == width && it.height == height && !it.isRecycled }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { outBitmap.set(it) }
            drawContent(Canvas(b))
            val reusable = b === outBitmap.get()
            try {
                enc.encodeBitmap(b)
                deliveredFrames.incrementAndGet()
            } finally {
                if (!reusable) b.recycle()
            }
        }
    }

    private fun drawShell(canvas: Canvas) {
        val view = shellView.get() ?: return
        canvas.save()
        canvas.scale(width / view.width.toFloat(), height / view.height.toFloat())
        view.draw(canvas)
        canvas.restore()
    }

    /** Draws the current best source (shell view > pane provider > test pattern) into [canvas]. */
    private fun drawContent(canvas: Canvas) {
        // Hardware encoder canvases are double/triple-buffered; uncleared letterbox /
        // missing-pane regions flash prior buffer contents on DiPlay.
        canvas.drawColor(Color.BLACK)
        val view = shellView.get()
        if (view != null && view.width > 0 && view.height > 0 &&
            ProjectionSessionGate.shouldTeeFromAaShell()
        ) {
            drawShell(canvas)
            return
        }
        val provider = frameProvider.get()
        if (provider != null && provider.draw(canvas, width, height)) return
        drawTestPattern(canvas)
    }

    private fun captureView(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            save()
            scale(width / view.width.toFloat(), height / view.height.toFloat())
            view.draw(this)
            restore()
        }
        return bitmap
    }

    private fun drawTestPattern(canvas: Canvas) {
        canvas.drawColor(Color.rgb(20, 24, 40))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(40, 120, 220)
        canvas.drawRect(0f, 0f, width.toFloat(), height / 3f, paint)
        paint.color = Color.WHITE
        paint.textSize = (height / 18f).coerceAtLeast(24f)
        canvas.drawText(
            "AADisplay CarPlay egress  frame=$frameIndex",
            width / 20f,
            height / 2f,
            paint,
        )
        paint.textSize = (height / 24f).coerceAtLeast(18f)
        canvas.drawText(
            if (ProjectionSessionGate.shouldTeeFromAaShell()) "mode=tee-shell"
            else if (frameProvider.get() != null) "mode=host-panes"
            else "mode=test-pattern",
            width / 20f,
            height / 2f + paint.textSize * 1.6f,
            paint,
        )
    }

    private fun startPumpThread() {
        val t = Thread(
            {
                try {
                    Looper.prepare()
                    val handler = Handler(Looper.myLooper()!!)
                    pumpHandler = handler
                    handler.post(pumpTick)
                    Looper.loop()
                } catch (e: Throwable) {
                    if (running.get()) Log.e(CarPlayConsts.TAG, "FrameEgressTee pump thread died", e)
                } finally {
                    pumpHandler = null
                }
            },
            "carplay-tee-pump",
        ).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                if (running.get()) Log.e(CarPlayConsts.TAG, "FrameEgressTee pump uncaught", e)
            }
        }
        pumpThread = t
        t.start()
    }

    /** Every 10 s: report the delivered frame rate vs target so the session can degrade. */
    private fun evaluateRate() {
        val now = SystemClock.uptimeMillis()
        val dt = now - windowStart
        if (dt < RATE_WINDOW_MS) return
        val actual = deliveredFrames.get() * 1000f / dt
        deliveredFrames.set(0)
        windowStart = now
        frameIndex++
        if (frameIndex % 3 == 0) {
            Log.i(
                CarPlayConsts.TAG,
                "FrameEgressTee rate ~${"%.1f".format(actual)}fps target=${encoder?.currentFps() ?: fps} " +
                    "encDrops=${encoder?.dropCount() ?: 0L}",
            )
        }
        onRateReport?.invoke(fps, actual)
    }

    companion object {
        private const val RATE_WINDOW_MS = 10_000L
    }
}
