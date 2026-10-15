package io.github.nitsuya.aa.display.carplay

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.egress.ShellFrameEgress
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener

/**
 * CarPlay-only host: dual GLES [SurfaceTexture] surfaces as split-VD sinks (no gearhead
 * CarActivity). Pane frames stay on the GPU — [CarPlayGlCompositor] composites them into the
 * MediaCodec input Surface at the supply fps (no CPU ImageReader sticky copies).
 *
 * Touches arrive split at [CarPlayInputBridge.primaryRatio] through [CarPlayInputBridge].
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
    private var compositor: CarPlayGlCompositor? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null
    private var displayId: Int = Display.INVALID_DISPLAY

    private val listener = object : IVirtualDisplayCreatedListener.Stub() {
        override fun onAvailableDisplay(displayId: Int, create: Boolean) {
            this@CarPlayHostActivity.displayId = displayId
            Log.i(CarPlayConsts.TAG, "CarPlayHost onAvailableDisplay id=$displayId create=$create")
            primarySurface?.let { CoreApi.setPaneSurface(SplitPane.PRIMARY, it) }
            secondarySurface?.let { CoreApi.setPaneSurface(SplitPane.SECONDARY, it) }
            if (create) maybeLaunchDefaultApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val w = CarPlayConsts.DEFAULT_VIDEO_WIDTH
        val h = CarPlayConsts.DEFAULT_VIDEO_HEIGHT
        val dpi = resources.displayMetrics.densityDpi.coerceAtLeast(160)
        val paneW = w / 2
        val gl = CarPlayGlCompositor()
        try {
            gl.start(paneW, h)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "CarPlayHost GL compositor boot failed", e)
            finish()
            return
        }
        compositor = gl
        primarySurface = gl.primarySurface()
        secondarySurface = gl.secondarySurface()
        if (primarySurface == null || secondarySurface == null) {
            Log.e(CarPlayConsts.TAG, "CarPlayHost GL pane surfaces missing")
            gl.stop()
            compositor = null
            finish()
            return
        }
        ShellFrameEgress.setAaUiAttached(false)
        CarPlayInputBridge.shellWidthPx = w
        CarPlayInputBridge.shellHeightPx = h
        CarPlayInputBridge.primaryRatio = SplitPane.DEFAULT_RATIO
        CarPlayProjectionService.attachGlCompositor(gl)
        ShellFrameEgress.onShellSize(w, h)
        CarPlayAppPickerOverlay.bindContext(this)
        CarPlayWheelController.onRecentsToggle = { CarPlayRecentsOverlay.toggle() }
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
        Log.i(CarPlayConsts.TAG, "CarPlayHostActivity created ${w}x$h dpi=$dpi gl=true")
    }

    private fun maybeLaunchDefaultApps() {
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            runCatching { LastSplitStore.invalidateCache() }
            val snap = runCatching { LastSplitStore.load(contentResolver) }.getOrNull()
            snap?.primaryRatio?.let { r ->
                val clamped = r.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
                if (Math.abs(clamped - CarPlayInputBridge.primaryRatio) > 0.02f) {
                    CarPlayInputBridge.primaryRatio = clamped
                    runCatching { CoreApi.setSplitRatio(clamped) }
                }
            }
            snap?.fullscreenPane?.takeIf { SplitPane.isFullscreenPane(it) }?.let { fs ->
                CarPlayInputBridge.fullscreenPane = fs
                runCatching { CoreApi.setSplitFullscreen(fs) }
            }
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val stored = if (pane == SplitPane.PRIMARY) snap?.primaryPackage else snap?.secondaryPackage
                val candidate = stored?.takeIf { it.isNotBlank() } ?: DEFAULT_PANE_APPS[pane]
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

    override fun onDestroy() {
        Log.i(CarPlayConsts.TAG, "CarPlayHostActivity destroy displayId=$displayId")
        current = null
        CarPlayProjectionService.attachGlCompositor(null)
        CoreApi.setPaneSurface(SplitPane.PRIMARY, null)
        CoreApi.setPaneSurface(SplitPane.SECONDARY, null)
        CoreApi.onDestroyDisplay()
        try {
            compositor?.stop()
        } catch (_: Throwable) {
        }
        compositor = null
        primarySurface = null
        secondarySurface = null
        super.onDestroy()
    }
}
