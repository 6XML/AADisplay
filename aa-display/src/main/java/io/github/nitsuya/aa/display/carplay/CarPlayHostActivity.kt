package io.github.nitsuya.aa.display.carplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * CarPlay-only host: dual ImageReader surfaces as split-VD sinks (no gearhead CarActivity).
 *
 * The panes' composited frames feed [FrameEgressTee] through [frameProvider] (left half =
 * PRIMARY pane, right half = SECONDARY pane), so the encoded CarPlay image carries the real
 * pane content instead of a test pattern. Touches arrive split at the same ratio through
 * [CarPlayInputBridge].
 */
class CarPlayHostActivity : Activity() {
    companion object {
        @Volatile
        var current: CarPlayHostActivity? = null

        /** Demo content for empty panes in CarPlay-only mode (first match wins). */
        private val DEFAULT_PANE_APPS = arrayOf(
            "com.google.android.apps.maps",
            "com.android.chrome",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var primaryReader: ImageReader? = null
    private var secondaryReader: ImageReader? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null
    private var displayId: Int = Display.INVALID_DISPLAY
    private var paneBitmaps = arrayOf<Bitmap?>(null, null)
    private var paneBitmapSources = arrayOf<ByteBuffer?>(null, null)

    private val listener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@CarPlayHostActivity.displayId = displayId
            Log.i(CarPlayConsts.TAG, "CarPlayHost onAvailableDisplay id=$displayId create=$create")
            primarySurface?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
            secondarySurface?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
            if (create) maybeLaunchDefaultApps()
        }
    }

    /** Composites the two pane Images into the full CarPlay frame (left + right halves). */
    private val frameProvider = FrameEgressTee.FrameProvider { canvas, w, h ->
        for (pane in 0..1) refreshPaneBitmap(pane)
        var any = false
        for (pane in 0..1) {
            val bmp = paneBitmaps[pane] ?: continue
            val left = if (pane == 0) 0f else w / 2f
            val halfW = w / 2f
            val scale = min(halfW / bmp.width, h / bmp.height.toFloat())
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val dst = RectF(
                left + (halfW - drawW) / 2f,
                (h - drawH) / 2f,
                left + (halfW - drawW) / 2f + drawW,
                (h - drawH) / 2f + drawH,
            )
            canvas.drawBitmap(bmp, null, dst, null)
            any = true
        }
        any
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val w = CarPlayConsts.DEFAULT_VIDEO_WIDTH
        val h = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
        val dpi = resources.displayMetrics.densityDpi.coerceAtLeast(160)
        primaryReader = ImageReader.newInstance(w / 2, h, PixelFormat.RGBA_8888, 3)
        secondaryReader = ImageReader.newInstance(w / 2, h, PixelFormat.RGBA_8888, 3)
        primarySurface = primaryReader!!.surface
        secondarySurface = secondaryReader!!.surface
        paneBitmaps[0] = Bitmap.createBitmap(w / 2, h, Bitmap.Config.ARGB_8888)
        paneBitmaps[1] = Bitmap.createBitmap(w / 2, h, Bitmap.Config.ARGB_8888)
        ProjectionSessionGate.setAaUiAttached(false)
        CarPlayInputBridge.shellWidthPx = w
        CarPlayInputBridge.shellHeightPx = h
        CarPlayInputBridge.primaryRatio = SplitPane.DEFAULT_RATIO
        CarPlayProjectionService.attachHostProvider(frameProvider)
        CarPlayProjectionService.onShellSize(w, h)
        CarPlayProjectionService.start(this)
        CoreApi.onCreateSplitDisplay(
            w,
            h,
            dpi,
            SplitPane.DEFAULT_RATIO,
            primarySurface,
            secondarySurface,
            listener,
        )
        Log.i(CarPlayConsts.TAG, "CarPlayHostActivity created ${w}x$h dpi=$dpi provider=${frameProvider != null}")
    }

    /** CarPlay-only panes have no AA Recent UI — seed them so the car shows real content. */
    private fun maybeLaunchDefaultApps() {
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val candidate = DEFAULT_PANE_APPS[pane]
                try {
                    val currentPkg = CoreApi.getPanePackage(pane)
                    if (currentPkg.isNullOrEmpty()) {
                        CoreApi.startActivityOnPane(candidate, 0, pane)
                        Log.i(CarPlayConsts.TAG, "CarPlayHost launched $candidate into pane=$pane")
                    } else {
                        Log.i(CarPlayConsts.TAG, "CarPlayHost pane=$pane keeps $currentPkg")
                    }
                } catch (e: Throwable) {
                    Log.w(CarPlayConsts.TAG, "CarPlayHost pane=$pane launch failed", e)
                }
            }
        }, 2_000)
    }

    /** Refresh one pane bitmap from the latest Image; false when nothing new arrived. */
    private fun refreshPaneBitmap(pane: Int): Boolean {
        val reader = (if (pane == 0) primaryReader else secondaryReader) ?: return false
        val image: Image = reader.acquireLatestImage() ?: return false
        try {
            val bmp = paneBitmaps[pane]
            if (bmp == null || bmp.width != image.width || bmp.height != image.height) {
                bmp?.recycle()
                val fresh = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                paneBitmaps[pane] = fresh
                paneBitmapSources[pane] = null
                if (!copyImage(image, fresh, pane)) {
                    fresh.recycle()
                    paneBitmaps[pane] = null
                    return false
                }
                return true
            }
            return copyImage(image, bmp, pane)
        } finally {
            image.close()
        }
    }

    /**
     * RGBA_8888 plane → ARGB_8888 [Bitmap]. Fast path copies the plane buffer straight
     * in (identical little-endian layout); falls back to per-pixel when strides differ.
     */
    private fun copyImage(image: Image, out: Bitmap, pane: Int): Boolean {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val size = w * h * 4
        if (plane.pixelStride == 4 && plane.rowStride == w * 4 && buffer.remaining() >= size) {
            var dst = paneBitmapSources[pane]
            if (dst == null || dst.capacity() < size) {
                dst = ByteBuffer.allocate(size)
                paneBitmapSources[pane] = dst
            }
            val src = buffer.duplicate()
            src.rewind()
            src.limit(size)
            dst.clear()
            dst.put(src)
            dst.rewind()
            return try {
                out.copyPixelsFromBuffer(dst)
                true
            } catch (e: Throwable) {
                false
            }
        }
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            var pos = y * plane.rowStride
            for (x in 0 until w) {
                val b = buffer.get(pos).toInt() and 0xff
                val g = buffer.get(pos + 1).toInt() and 0xff
                val r = buffer.get(pos + 2).toInt() and 0xff
                val a = buffer.get(pos + 3).toInt() and 0xff
                argb[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                pos += plane.pixelStride
            }
        }
        out.setPixels(argb, 0, w, 0, 0, w, h)
        return true
    }

    override fun onDestroy() {
        Log.i(CarPlayConsts.TAG, "CarPlayHostActivity destroy displayId=$displayId")
        current = null
        CarPlayProjectionService.attachHostProvider(null)
        CoreApi.setPaneSurface(SplitPane.PRIMARY, null)
        CoreApi.setPaneSurface(SplitPane.SECONDARY, null)
        CoreApi.onDestroyDisplay()
        try {
            primarySurface?.release()
        } catch (_: Throwable) {
        }
        try {
            secondarySurface?.release()
        } catch (_: Throwable) {
        }
        primaryReader?.close()
        secondaryReader?.close()
        primaryReader = null
        secondaryReader = null
        paneBitmaps[0]?.recycle()
        paneBitmaps[1]?.recycle()
        paneBitmaps = arrayOf(null, null)
        super.onDestroy()
    }
}
