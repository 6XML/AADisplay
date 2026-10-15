package io.github.nitsuya.aa.display.carplay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * AA-parity split chrome drawn into the CarPlay encode canvas:
 * vertical seam + three grip dots, or fullscreen left-edge peel tab + dots.
 *
 * Kept separate from [CarPlayGlCompositor] so Canvas geometry stays in top-down
 * pixel space (same as Recents / picker overlays).
 */
internal object CarPlayChromePainter {
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val peelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6282828.toInt()
        style = Paint.Style.FILL
    }
    private val peelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8E8.toInt()
        style = Paint.Style.FILL
    }
    private val peelDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        style = Paint.Style.FILL
    }
    private val peelRect = RectF()
    private val peelPath = Path()
    private val cornerRadii = FloatArray(8)

    /** Density relative to 160dpi, derived from encode height (720 → ~1.0× AA @160 on phone). */
    private fun density(h: Int): Float = (h / 720f).coerceIn(0.75f, 1.5f)

    fun drawSplitDivider(canvas: Canvas, w: Int, h: Int, seamX: Int) {
        val d = density(h)
        seamPaint.strokeWidth = 1.5f * d
        val x = seamX.toFloat()
        canvas.drawLine(x, 0f, x, h.toFloat(), seamPaint)
        drawDots(canvas, x, h / 2f, vertical = true, peel = false, d)
    }

    fun drawPeelTab(canvas: Canvas, w: Int, h: Int) {
        val d = density(h)
        val tabLen = SplitPane.PEEL_TAB_LENGTH_DP * d * 1.15f
        val thick = (SplitPane.PEEL_TAB_THICKNESS_DP * d * 1.35f).coerceAtLeast(8f)
        val half = tabLen / 2f
        val cy = h / 2f
        peelRect.set(0f, cy - half, thick, cy + half)
        val r = thick / 2f
        for (i in cornerRadii.indices) cornerRadii[i] = 0f
        // Round inward (right) corners only — AA drawer-tab look.
        cornerRadii[2] = r
        cornerRadii[3] = r
        cornerRadii[4] = r
        cornerRadii[5] = r
        peelPath.reset()
        peelPath.addRoundRect(peelRect, cornerRadii, Path.Direction.CW)
        peelStroke.strokeWidth = 1.25f * d
        canvas.drawPath(peelPath, peelFill)
        canvas.drawPath(peelPath, peelStroke)
        drawDots(canvas, thick / 2f, cy, vertical = true, peel = true, d)
    }

    private fun drawDots(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        vertical: Boolean,
        peel: Boolean,
        d: Float,
    ) {
        val paint = if (peel) peelDotPaint else dotPaint
        val radius = (if (peel) SplitPane.DIVIDER_DOT_RADIUS_DP * 0.9f else SplitPane.DIVIDER_DOT_RADIUS_DP) * d
        val gap = SplitPane.DIVIDER_DOT_GAP_DP * d
        for (offset in floatArrayOf(-gap, 0f, gap)) {
            if (vertical) {
                canvas.drawCircle(cx, cy + offset, radius, paint)
            } else {
                canvas.drawCircle(cx + offset, cy, radius, paint)
            }
        }
    }
}
