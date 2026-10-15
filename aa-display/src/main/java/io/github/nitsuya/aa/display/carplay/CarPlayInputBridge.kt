package io.github.nitsuya.aa.display.carplay

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * Maps normalized CarPlay screen coordinates (0..1) onto AADisplay injection paths.
 *
 * Dual-channel (AA attached): the whole CarPlay image is the AA shell, so touches go to
 * [CoreApi.touchAaDisplay]. CarPlay-only (no AA UI): the image is the split display, so
 * touches land on the pane VirtualDisplay that owns the touched half
 * ([CoreApi.touchPane]), re-normalized into that pane's pixel space.
 */
object CarPlayInputBridge {
    @Volatile
    var shellWidthPx: Int = CarPlayConsts.DEFAULT_VIDEO_WIDTH

    @Volatile
    var shellHeightPx: Int = CarPlayConsts.DEFAULT_VIDEO_HEIGHT

    /** Primary pane share of the split display width (matches CoreApi.onCreateSplitDisplay). */
    @Volatile
    var primaryRatio: Float = SplitPane.DEFAULT_RATIO

    fun injectNormalized(xNorm: Float, yNorm: Float, action: Int) {
        val w = shellWidthPx.coerceAtLeast(1)
        val h = shellHeightPx.coerceAtLeast(1)
        val x = (xNorm.coerceIn(0f, 1f) * (w - 1)).toFloat()
        val y = (yNorm.coerceIn(0f, 1f) * (h - 1)).toFloat()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            val ok = try {
                if (ProjectionSessionGate.shouldUseCarPlayHost()) {
                    routeToPane(xNorm, yNorm, action, w, h)
                } else {
                    CoreApi.touchAaDisplay(event)
                    true
                }
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "CarPlay touch inject failed", e)
                false
            }
            if (!ok) {
                Log.w(CarPlayConsts.TAG, "CarPlay touch dropped action=$action x=$x y=$y")
            }
        } finally {
            event.recycle()
        }
    }

    /**
     * Splits the full CarPlay image at [primaryRatio]: left half → PRIMARY pane VD,
     * right half → SECONDARY pane VD, each in its own display pixel space.
     */
    private fun routeToPane(xNorm: Float, yNorm: Float, action: Int, w: Int, h: Int): Boolean {
        val ratio = primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        val x = xNorm.coerceIn(0f, 1f)
        val y = yNorm.coerceIn(0f, 1f)
        val (pane, xPane, paneWidth) = if (x < ratio) {
            Triple(SplitPane.PRIMARY, x / ratio, (w * ratio).toInt().coerceAtLeast(1))
        } else {
            val share = (1f - ratio).coerceAtLeast(0.01f)
            Triple(SplitPane.SECONDARY, (x - ratio) / share, (w - w * ratio).toInt().coerceAtLeast(1))
        }
        val px = (xPane * (paneWidth - 1)).toFloat()
        val py = (y * (h - 1)).toFloat()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, px, py, 0)
        try {
            val ok = try {
                CoreApi.touchPane(pane, event)
                true
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "touchPane($pane) from CarPlay failed", e)
                false
            }
            if (action != MotionEvent.ACTION_MOVE && !ok) {
                Log.w(CarPlayConsts.TAG, "CarPlay pane touch dropped pane=$pane action=$action x=$px y=$py")
            }
            return ok
        } finally {
            event.recycle()
        }
    }
}
