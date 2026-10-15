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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Composites content into [CarPlayVideoEncoder] as bitmaps.
 *
 * Sources, in priority order:
 * 1. The AA shell view (dual-channel tee; [ProjectionSessionGate.shouldTeeFromAaShell]).
 * 2. A [FrameProvider] (CarPlay-only host; composites the split-VD ImageReader panes).
 * 3. A test pattern (pipeline verification before any real content exists).
 */
class FrameEgressTee {
    /** Draws current pane content into [canvas]; false when nothing is available. */
    fun interface FrameProvider {
        fun draw(canvas: Canvas, width: Int, height: Int): Boolean
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val shellView = AtomicReference<View?>(null)
    private val frameProvider = AtomicReference<FrameProvider?>(null)
    private val outBitmap = AtomicReference<Bitmap?>(null)
    private var encoder: CarPlayVideoEncoder? = null
    private var width = CarPlayConsts.DEFAULT_VIDEO_WIDTH
    private var height = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
    private var frameIndex = 0
    private var lastRateLog = SystemClock.uptimeMillis()

    private val pump = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                pumpOnce()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "FrameEgressTee pump failed", e)
            }
            if (running.get()) {
                mainHandler.postDelayed(this, (1000L / CarPlayConsts.DEFAULT_VIDEO_FPS))
            }
        }
    }

    fun attachShell(view: View?) {
        shellView.set(view)
    }

    fun attachFrameProvider(provider: FrameProvider?) {
        frameProvider.set(provider)
    }

    /**
     * Start/stop mutate state the main-thread pump reads; DiPlay re-SETUP drives them from
     * worker threads, so all mutation is serialized onto the main looper.
     */
    fun start(encoder: CarPlayVideoEncoder, width: Int, height: Int) {
        val run = {
            startInternal(encoder, width, height)
        }
        if (isMainLooper()) run() else mainHandler.post(run)
    }

    private fun startInternal(encoder: CarPlayVideoEncoder, width: Int, height: Int): Unit {
        stopInternal()
        this.encoder = encoder
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        frameIndex = 0
        lastRateLog = SystemClock.uptimeMillis()
        running.set(true)
        mainHandler.post(pump)
        Log.i(
            CarPlayConsts.TAG,
            "FrameEgressTee start ${this.width}x${this.height} " +
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
        mainHandler.removeCallbacks(pump)
        encoder = null
        outBitmap.get()?.recycle()
        outBitmap.set(null)
    }

    private fun pumpOnce() {
        val enc = encoder ?: return
        if (enc.hasSurfaceInput()) {
            // Zero-copy: the pump only issues a GPU frame; C2 converts in hardware.
            try {
                enc.encodeCanvas { drawContent(it) }
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "FrameEgressTee pump failed", e)
            }
        } else {
            // Buffer-input fallback.
            val view = shellView.get()
            val bitmap = when {
                view != null && view.width > 0 && view.height > 0 &&
                    ProjectionSessionGate.shouldTeeFromAaShell() -> {
                    captureView(view)
                }

                else -> {
                    // Reuse the canvas bitmap: allocating 5.5 MB per frame churns the main
                    // thread GC and collapses the pump to a couple of fps.
                    val b = outBitmap.get()?.takeIf { it.width == width && it.height == height && !it.isRecycled }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { outBitmap.set(it) }
                    drawContent(Canvas(b))
                    b
                }
            }
            val reusable = bitmap === outBitmap.get()
            try {
                enc.encodeBitmap(bitmap)
            } finally {
                if (!reusable) bitmap.recycle()
            }
        }
        frameIndex++
        if (frameIndex % 300 == 0) {
            val now = SystemClock.uptimeMillis()
            val dt = now - lastRateLog
            if (dt > 0) {
                Log.i(
                    CarPlayConsts.TAG,
                    "FrameEgressTee pump rate ~${(300_000.0 / dt).toInt()}fps " +
                        "encDrops=${enc.dropCount()}",
                )
                lastRateLog = now
            }
        }
    }

    /** Draws the current best source (shell view > pane provider > test pattern) into [canvas]. */
    private fun drawContent(canvas: Canvas) {
        val view = shellView.get()
        if (view != null && view.width > 0 && view.height > 0 &&
            ProjectionSessionGate.shouldTeeFromAaShell()
        ) {
            canvas.save()
            canvas.scale(width / view.width.toFloat(), height / view.height.toFloat())
            view.draw(canvas)
            canvas.restore()
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
        val canvas = Canvas(bitmap)
        view.draw(canvas)
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
}
