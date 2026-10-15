package io.github.nitsuya.aa.display.carplay

import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay steering wheel (HID reports on the AirPlay event channel) → AADisplay actions.
 *
 * Same semantics the Android Auto path uses for `STEERING_WHEEL_CONTROL` broadcasts
 * ([io.github.nitsuya.aa.display.ui.aa.fragment.AaMainFragment]):
 *  - media button SHORT: play / pause / play-pause / next / previous → [CoreApi.pressKey]
 *  - media NEXT long-press: swap the split panes ([CoreApi.swapSplitPanes])
 *  - media PREVIOUS long-press: recents toggle (CarPlay-only; AA shows its own recents)
 *  - knob wheel tick: scroll the focused pane (map zoom etc.)
 *  - telephony: logged only in v1 (calls ring on the phone by design)
 *
 * Works in both modes: in dual-track mode [CoreApi.pressKey] / [CoreApi.swapSplitPanes]
 * inject into the same system_server panes the AA shell renders; in CarPlay-only mode
 * they drive the split display directly.
 */
object CarPlayWheelController {
    private val TAG = CarPlayConsts.TAG

    /** Event-channel HID device ids (DiPlay AirPlayHid uuid tails). */
    const val DEV_KNOB = 0
    const val DEV_MEDIA = 1
    const val DEV_TELEPHONY = 2

    // DiPlay AirPlayHid media usages.
    private const val MEDIA_PLAY = 1
    private const val MEDIA_PAUSE = 2
    private const val MEDIA_PLAY_PAUSE = 3
    private const val MEDIA_NEXT = 4
    private const val MEDIA_PREV = 5

    // Knob button bits (DiPlay knob report byte 0); shared with [CarPlayEventChannel].
    const val KNOB_SELECT = 1
    const val KNOB_HOME = 2
    const val KNOB_BACK = 4
    /** Momentary wheel-tick code; [extra] carries the signed wheel delta. */
    const val KNOB_WHEEL = 16

    private const val LONG_PRESS_MS = 400L

    private val enabled = AtomicBoolean(false)

    /** CarPlay Host recents overlay hook (set by [CarPlayHostActivity]). */
    @Volatile
    var onRecentsToggle: (() -> Unit)? = null

    private val pressStartMs = ConcurrentHashMap<Int, Long>()

    /** Last media usage seen on press; release reports arrive with [code]/[extra] = 0. */
    @Volatile
    private var lastMediaUsage = 0

    fun setEnabled(live: Boolean) {
        enabled.set(live)
        if (!live) {
            pressStartMs.clear()
            lastMediaUsage = 0
        }
    }

    /**
     * Event-channel HID report: [code] is the key (media 1..5, telephony usages, knob
     * bits / wheel), [pressed] its state, [extra] the raw value (wheel delta for
     * [KNOB_WHEEL]). Release reports arrive with [code] = 0 and [extra] = 0 — recover
     * the usage from [lastMediaUsage].
     */
    fun onHidKey(device: Int, code: Int, pressed: Boolean, extra: Int) {
        if (!enabled.get()) return
        when (device) {
            DEV_MEDIA -> handleMedia(code, pressed, extra)
            DEV_KNOB -> when (code) {
                KNOB_SELECT -> Log.i(TAG, "wheel knob select")
                KNOB_HOME -> Log.i(TAG, "wheel knob home")
                KNOB_BACK -> Log.i(TAG, "wheel knob back")
                KNOB_WHEEL -> if (extra != 0) scrollFocusedPane(extra)
                else -> Unit // button release
            }
            DEV_TELEPHONY -> Log.i(TAG, "wheel telephony code=$code pressed=$pressed (v1: log only)")
        }
    }

    private fun mediaKeyCode(code: Int): Int = when (code) {
        MEDIA_PLAY -> 126 // KEYCODE_MEDIA_PLAY
        MEDIA_PAUSE -> 127 // KEYCODE_MEDIA_PAUSE
        MEDIA_PLAY_PAUSE -> 165 // KEYCODE_MEDIA_PLAY_PAUSE
        MEDIA_NEXT -> 130 // KEYCODE_MEDIA_NEXT
        MEDIA_PREV -> 129 // KEYCODE_MEDIA_PREVIOUS
        else -> 0
    }

    private fun handleMedia(code: Int, pressed: Boolean, extra: Int) {
        if (pressed) {
            val key = if (extra != 0) extra else code
            if (key == 0) return
            lastMediaUsage = key
            pressStartMs[key] = System.currentTimeMillis()
            return
        }
        // Release: DiPlay sends usage=0; use the last pressed usage.
        val key = when {
            extra != 0 -> extra
            code != 0 -> code
            lastMediaUsage != 0 -> lastMediaUsage
            else -> return
        }
        val start = pressStartMs.remove(key) ?: return
        if (lastMediaUsage == key) lastMediaUsage = 0
        val heldMs = System.currentTimeMillis() - start
        if (heldMs < LONG_PRESS_MS) {
            val keyCode = mediaKeyCode(key)
            if (keyCode != 0) {
                runCatching { CoreApi.pressKey(keyCode) }
                Log.i(TAG, "wheel media short code=$key → pressKey($keyCode) held=${heldMs}ms")
            }
        } else {
            when (key) {
                MEDIA_NEXT -> {
                    runCatching { CoreApi.swapSplitPanes() }
                    // Keep Host ratio in sync with server invert.
                    CarPlayInputBridge.primaryRatio =
                        SplitPane.clampRatio(1f - CarPlayInputBridge.primaryRatio)
                    Log.i(TAG, "wheel media next long → swap panes (held=${heldMs}ms)")
                }
                MEDIA_PREV -> {
                    val cb = onRecentsToggle
                    if (cb != null) {
                        runCatching { cb() }
                    } else {
                        CarPlayRecentsOverlay.toggle()
                    }
                    Log.i(TAG, "wheel media prev long → recents toggle (held=${heldMs}ms)")
                }
                else -> {
                    val keyCode = mediaKeyCode(key)
                    if (keyCode != 0) runCatching { CoreApi.pressKey(keyCode) }
                }
            }
        }
    }

    /** Knob wheel tick → synthesized scroll on the focused pane center. */
    private fun scrollFocusedPane(wheelDelta: Int) {
        val pane = runCatching { CoreApi.getFocusedPane() }
            .getOrNull()
            .takeIf { it == SplitPane.PRIMARY || it == SplitPane.SECONDARY } ?: SplitPane.PRIMARY
        val w = CarPlayInputBridge.shellWidthPx.coerceAtLeast(1)
        val h = CarPlayInputBridge.shellHeightPx.coerceAtLeast(1)
        val ratio = CarPlayInputBridge.primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        // Pane center in shell coordinates: primary starts at 0, secondary at [ratio].
        val centerShare = if (pane == SplitPane.PRIMARY) ratio / 2f else (1f + ratio) / 2f
        val x = (centerShare * w).toInt().coerceIn(0, w - 1)
        val y = (h / 2).coerceIn(0, h - 1)
        runCatching {
            CoreApi.scrollPane(pane, x, y, wheelDelta, 0)
        }
    }
}
