package io.github.nitsuya.aa.display.carplay

import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay-side 3-column Recents, drawn into the CarPlay image (the host activity is
 * headless — the tee canvas is the only "screen" the driver sees).
 *
 * Column order matches AA [io.github.nitsuya.aa.display.ui.aa.recent.RecentTaskColumns]:
 * left = PRIMARY, center = SECONDARY, right = phone (main display).
 *
 * Opened by divider / peel long-press or wheel PREVIOUS long-press. While open, all
 * CarPlay touches are consumed by [onTouch].
 */
object CarPlayRecentsOverlay {
    private val TAG = CarPlayConsts.TAG

    private const val MAX_ITEMS_PER_COLUMN = 6

    /** Shared geometry (normalized / fraction of height or width). */
    private const val CARD_H = 0.16f
    private const val GAP = 0.045f
    private const val START_Y = 0.11f
    private const val CARD_W_FRAC = 0.24f
    private const val ADD_BTN_H = 0.06f
    private const val ADD_BTN_GAP = 0.02f

    private val visible = AtomicBoolean(false)

    @Volatile
    private var columnPrimary = listOf<RecentTaskInfo>()
    @Volatile
    private var columnSecondary = listOf<RecentTaskInfo>()
    @Volatile
    private var columnPhone = listOf<RecentTaskInfo>()

    private val bgPaint = Paint().apply { color = 0xC8000000.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E1E.toInt() }
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    fun isVisible(): Boolean = visible.get()

    fun open() {
        if (visible.compareAndSet(false, true)) {
            refresh()
            Log.i(TAG, "CarPlay recents overlay opened")
        } else {
            refresh()
        }
    }

    fun close() {
        if (visible.compareAndSet(true, false)) {
            Log.i(TAG, "CarPlay recents overlay closed")
        }
    }

    fun toggle() {
        if (visible.get()) close() else open()
    }

    /** Pulls fresh task lists from system_server. */
    fun refresh() {
        val t = try {
            CoreApi.getRecentTask()
        } catch (e: Throwable) {
            Log.w(TAG, "recents fetch failed", e)
            null
        }
        columnPrimary = (t?.primaryDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(MAX_ITEMS_PER_COLUMN)
        columnSecondary = (t?.secondaryDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(MAX_ITEMS_PER_COLUMN)
        columnPhone = (t?.mainDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(MAX_ITEMS_PER_COLUMN)
    }

    /** Tee composite hook: runs on the pump / GL thread. */
    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!visible.get()) return
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        // AA order: LEFT=PRIMARY, CENTER=SECONDARY, RIGHT=PHONE
        drawColumn(canvas, w / 6f, columnPrimary, "LEFT", w, h, showAdd = true)
        drawColumn(canvas, w / 2f, columnSecondary, "RIGHT", w, h, showAdd = true)
        drawColumn(canvas, w * 5f / 6f, columnPhone, "PHONE", w, h, showAdd = false)
    }

    private fun drawColumn(
        canvas: Canvas,
        centerX: Float,
        items: List<RecentTaskInfo>,
        title: String,
        w: Int,
        h: Int,
        showAdd: Boolean,
    ) {
        textPaint.textSize = h * 0.035f
        canvas.drawText(title, centerX, h * 0.07f, textPaint)
        val cardW = w * CARD_W_FRAC
        val thumbH = h * CARD_H
        val gap = h * GAP
        val startY = h * START_Y
        for ((i, item) in items.withIndex()) {
            val y = startY + i * (thumbH + gap)
            val x = centerX - cardW / 2f
            canvas.drawRect(x, y, x + cardW, y + thumbH, cardPaint)
            val logo = item.logo
            if (logo != null && !logo.isRecycled) {
                val scale = (thumbH / logo.height.coerceAtLeast(1))
                    .coerceAtMost(cardW / logo.width.coerceAtLeast(1))
                val dw = logo.width * scale
                val dh = logo.height * scale
                canvas.drawBitmap(
                    logo,
                    null,
                    android.graphics.RectF(
                        centerX - dw / 2f,
                        y + thumbH / 2f - dh / 2f,
                        centerX + dw / 2f,
                        y + thumbH / 2f + dh / 2f,
                    ),
                    null,
                )
            }
            textPaint.textSize = h * 0.03f
            canvas.drawText(item.label ?: item.packageName ?: "…", centerX, y + thumbH + h * 0.03f, textPaint)
        }
        if (showAdd) {
            val addY = startY + items.size * (thumbH + gap) + h * ADD_BTN_GAP
            val addH = h * ADD_BTN_H
            val x = centerX - cardW / 2f
            canvas.drawRect(x, addY, x + cardW, addY + addH, addPaint)
            textPaint.textSize = h * 0.028f
            canvas.drawText("添加应用", centerX, addY + addH * 0.68f, textPaint)
        }
    }

    /**
     * Touch while the overlay is open: DOWN/MOVE are consumed; UP hit-tests a card,
     * the add-app button, or dismisses on empty background.
     */
    fun onTouch(xNorm: Float, yNorm: Float, action: Int): Boolean {
        if (!visible.get()) return false
        if (action != android.view.MotionEvent.ACTION_UP) return true

        val col = when {
            xNorm < 1f / 3f -> 0 // PRIMARY
            xNorm < 2f / 3f -> 1 // SECONDARY
            else -> 2 // PHONE
        }
        val list = when (col) {
            0 -> columnPrimary
            1 -> columnSecondary
            else -> columnPhone
        }
        val pane = when (col) {
            0 -> SplitPane.PRIMARY
            1 -> SplitPane.SECONDARY
            else -> -1 // phone
        }

        val idx = ((yNorm - START_Y) / (CARD_H + GAP)).toInt()
        val inCard = idx >= 0 &&
            idx < list.size &&
            (yNorm - START_Y - idx * (CARD_H + GAP)) in 0f..CARD_H
        if (inCard) {
            val item = list[idx]
            launchItem(item, pane)
            return true
        }

        // Add-app button under the last card (pane columns only).
        if (pane >= 0) {
            val addY = START_Y + list.size * (CARD_H + GAP) + ADD_BTN_GAP
            if (yNorm in addY..(addY + ADD_BTN_H)) {
                close()
                CarPlayAppPickerOverlay.show(pane)
                return true
            }
        }

        // Empty background → dismiss (AA overlay parity).
        close()
        return true
    }

    private fun launchItem(item: RecentTaskInfo, pane: Int) {
        val pkg = item.packageName
        if (pkg.isNullOrBlank()) return
        close()
        try {
            if (pane < 0) {
                if (item.taskId > 0) {
                    CoreApi.moveTaskToFront(item.taskId)
                } else {
                    CoreApi.startActivity(pkg, 0)
                }
            } else {
                if (item.taskId > 0) {
                    CoreApi.moveTaskToFront(item.taskId)
                } else {
                    CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
                }
            }
            Log.i(TAG, "recents launch $pkg pane=${if (pane < 0) "phone" else pane} taskId=${item.taskId}")
        } catch (e: Throwable) {
            Log.w(TAG, "recents launch failed pkg=$pkg", e)
        }
    }
}
