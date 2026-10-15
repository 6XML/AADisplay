package io.github.nitsuya.aa.display.carplay

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process egress arbitration for AA TextureView vs CarPlay encoder.
 * system_server also mirrors CarPlay-live via [ICoreManager.reportCarPlaySessionLive].
 */
object ProjectionSessionGate {
    private val aaUiAttached = AtomicBoolean(false)
    private val carPlayLive = AtomicBoolean(false)
    private val teeWanted = AtomicBoolean(false)

    fun setAaUiAttached(attached: Boolean) {
        aaUiAttached.set(attached)
        refreshTeeWanted()
    }

    fun setCarPlayLive(live: Boolean) {
        carPlayLive.set(live)
        refreshTeeWanted()
    }

    fun isAaUiAttached(): Boolean = aaUiAttached.get()

    fun isCarPlayLive(): Boolean = carPlayLive.get()

    /** True when CarPlay should consume composited frames (AA attached + CP live). */
    fun shouldTeeFromAaShell(): Boolean = teeWanted.get()

    /** True when CarPlay alone should drive VD sinks via ImageReader Host. */
    fun shouldUseCarPlayHost(): Boolean = carPlayLive.get() && !aaUiAttached.get()

    private fun refreshTeeWanted() {
        teeWanted.set(carPlayLive.get() && aaUiAttached.get())
    }
}
