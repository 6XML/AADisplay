package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process arbitration between AA TextureView sinks and a secondary egress encoder
 * (today: CarPlay Source). Lives outside the carplay package so AA UI never imports
 * CarPlay types. system_server also mirrors secondary-egress live via
 * [io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive].
 */
object ProjectionSessionGate {
    private val aaUiAttached = AtomicBoolean(false)
    private val secondaryEgressLive = AtomicBoolean(false)
    private val teeWanted = AtomicBoolean(false)

    fun setAaUiAttached(attached: Boolean) {
        aaUiAttached.set(attached)
        refreshTeeWanted()
    }

    /** CarPlay Source session live in the app process. */
    fun setCarPlayLive(live: Boolean) {
        secondaryEgressLive.set(live)
        refreshTeeWanted()
    }

    fun isAaUiAttached(): Boolean = aaUiAttached.get()

    fun isCarPlayLive(): Boolean = secondaryEgressLive.get()

    /** True when secondary egress should consume composited AA shell frames. */
    fun shouldTeeFromAaShell(): Boolean = teeWanted.get()

    /** True when secondary egress alone should drive VD sinks (CarPlay Host). */
    fun shouldUseCarPlayHost(): Boolean = secondaryEgressLive.get() && !aaUiAttached.get()

    private fun refreshTeeWanted() {
        teeWanted.set(secondaryEgressLive.get() && aaUiAttached.get())
    }
}
