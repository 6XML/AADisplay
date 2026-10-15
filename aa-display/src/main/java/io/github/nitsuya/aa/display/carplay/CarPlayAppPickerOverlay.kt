package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitAppEntry
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.PmIconCache
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CarPlay Host canvas app picker — parity with AA [io.github.nitsuya.aa.display.ui.aa.split.SplitAppPickerController]
 * without depending on FragmentAaMainBinding. Drawn into the encode stream by
 * [CarPlayGlCompositor]; opened from Recents「添加应用」or an empty-pane tap.
 */
object CarPlayAppPickerOverlay {
    private val TAG = CarPlayConsts.TAG

    private const val COLS = 6
    private const val MAX_VISIBLE = 36
    private const val HEADER_H = 0.08f
    private const val GRID_TOP = 0.12f
    private const val CELL_H = 0.14f
    private const val CLOSE_W = 0.08f
    private const val CLOSE_H = 0.06f

    private val visible = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger(0)
    private val loadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carplay-app-picker").apply { isDaemon = true }
    }

    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    private var targetPane: Int = SplitPane.PRIMARY
    @Volatile
    private var entries: List<PickerCell> = emptyList()
    @Volatile
    private var launchableCache: List<SplitAppEntry>? = null

    /** Bind once from [CarPlayHostActivity] so PackageManager / LastSplitStore work. */
    fun bindContext(context: Context) {
        appContextRef = WeakReference(context.applicationContext)
    }

    private val bgPaint = Paint().apply { color = 0xD0000000.toInt() }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF212121.toInt() }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB71C1C.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private data class PickerCell(
        val entry: SplitAppEntry,
        val iconBmp: Bitmap?,
    )

    fun isVisible(): Boolean = visible.get()

    fun show(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        targetPane = pane
        CarPlayRecentsOverlay.close()
        visible.set(true)
        val gen = loadGeneration.incrementAndGet()
        loadExecutor.execute {
            val rows = try {
                buildCells()
            } catch (e: Throwable) {
                Log.w(TAG, "picker load failed", e)
                emptyList()
            }
            if (gen != loadGeneration.get()) return@execute
            entries = rows
            Log.i(TAG, "CarPlay app picker shown pane=$pane entries=${rows.size}")
        }
    }

    fun close() {
        if (visible.compareAndSet(true, false)) {
            loadGeneration.incrementAndGet()
            Log.i(TAG, "CarPlay app picker closed")
        }
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!visible.get()) return
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        textPaint.textSize = h * 0.04f
        val paneLabel = if (targetPane == SplitPane.PRIMARY) "LEFT" else "RIGHT"
        canvas.drawText("添加应用 · $paneLabel", w / 2f, h * HEADER_H * 0.7f, textPaint)

        // Close button (top-right).
        val closeL = w * (1f - CLOSE_W - 0.02f)
        val closeT = h * 0.02f
        canvas.drawRect(closeL, closeT, closeL + w * CLOSE_W, closeT + h * CLOSE_H, closePaint)
        textPaint.textSize = h * 0.03f
        canvas.drawText("×", closeL + w * CLOSE_W / 2f, closeT + h * CLOSE_H * 0.7f, textPaint)

        val cells = entries
        val cellW = w.toFloat() / COLS
        val cellH = h * CELL_H
        val iconSize = cellH * 0.55f
        for ((i, cell) in cells.withIndex()) {
            if (i >= MAX_VISIBLE) break
            val col = i % COLS
            val row = i / COLS
            val cx = col * cellW + cellW / 2f
            val top = h * GRID_TOP + row * cellH
            canvas.drawRect(
                col * cellW + cellW * 0.08f,
                top + cellH * 0.05f,
                (col + 1) * cellW - cellW * 0.08f,
                top + cellH * 0.95f,
                cellPaint,
            )
            cell.iconBmp?.takeIf { !it.isRecycled }?.let { bmp ->
                val left = cx - iconSize / 2f
                val itop = top + cellH * 0.12f
                canvas.drawBitmap(
                    bmp,
                    null,
                    android.graphics.RectF(left, itop, left + iconSize, itop + iconSize),
                    null,
                )
            }
            textPaint.textSize = h * 0.022f
            canvas.drawText(
                cell.entry.label.take(10),
                cx,
                top + cellH * 0.88f,
                textPaint,
            )
        }
    }

    fun onTouch(xNorm: Float, yNorm: Float, action: Int): Boolean {
        if (!visible.get()) return false
        if (action != android.view.MotionEvent.ACTION_UP) return true

        // Close button.
        if (xNorm >= 1f - CLOSE_W - 0.02f && yNorm <= CLOSE_H + 0.02f) {
            close()
            return true
        }

        if (yNorm < GRID_TOP) {
            close()
            return true
        }

        val col = (xNorm * COLS).toInt().coerceIn(0, COLS - 1)
        val row = ((yNorm - GRID_TOP) / CELL_H).toInt()
        if (row < 0) {
            close()
            return true
        }
        val idx = row * COLS + col
        val cell = entries.getOrNull(idx)
        if (cell == null) {
            close()
            return true
        }
        val pane = targetPane
        val pkg = cell.entry.packageName
        close()
        loadExecutor.execute {
            try {
                CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
                Log.i(TAG, "picker launch $pkg pane=$pane")
            } catch (e: Throwable) {
                Log.w(TAG, "picker launch failed pkg=$pkg", e)
            }
        }
        return true
    }

    private fun buildCells(): List<PickerCell> {
        val all = ensureLaunchableCache()
        val byPkg = all.associateBy { it.packageName }
        val recentPkgs = loadRecentPackages()
        val recentEntries = recentPkgs.mapNotNull { byPkg[it] }.distinctBy { it.packageName }
        val recentSet = recentEntries.map { it.packageName }.toSet()
        val rest = all.filter { it.packageName !in recentSet }
        val ordered = recentEntries + rest
        val ctx = appContextRef?.get()
        val pm = ctx?.packageManager
        return ordered.take(MAX_VISIBLE).map { entry ->
            val icon = pm?.let { PmIconCache.getOrLoadDrawable(it, entry.packageName) }
            PickerCell(entry, drawableToBitmap(icon, 96))
        }
    }

    private fun ensureLaunchableCache(): List<SplitAppEntry> {
        launchableCache?.let { return it }
        val loaded = loadLaunchableApps()
        launchableCache = loaded
        return loaded
    }

    private fun loadLaunchableApps(): List<SplitAppEntry> {
        val ctx = appContextRef?.get() ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        return resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName
            if (pkg == BuildConfig.APPLICATION_ID) return@mapNotNull null
            if (pkg == "android" || pkg == "com.android.systemui") return@mapNotNull null
            SplitAppEntry(
                packageName = pkg,
                label = ai.loadLabel(pm)?.toString() ?: pkg,
                icon = null,
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadRecentPackages(): List<String> {
        val ordered = linkedSetOf<String>()
        fun addPkg(raw: String?) {
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered.add(it) }
        }
        try {
            val ctx = appContextRef?.get() ?: return emptyList()
            LastSplitStore.load(ctx.contentResolver)?.let { snap ->
                snap.primaryPackagesBottomToTop().asReversed().forEach(::addPkg)
                snap.secondaryPackagesBottomToTop().asReversed().forEach(::addPkg)
            }
        } catch (_: Throwable) {
        }
        return ordered.toList()
    }

    private fun drawableToBitmap(drawable: Drawable?, size: Int): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        return try {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(c)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
