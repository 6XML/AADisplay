package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process flag for whether the wireless CarPlay Source session is live.
 *
 * Video is exclusive: CarPlay Host paints its own GL path. system_server mirrors
 * the same live bit via [io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive].
 */
object ProjectionSessionGate {
    private val secondaryEgressLive = AtomicBoolean(false)

    /** CarPlay Source session live in the app process. */
    fun setCarPlayLive(live: Boolean) {
        secondaryEgressLive.set(live)
    }

    fun isCarPlayLive(): Boolean = secondaryEgressLive.get()

    /** True when CarPlay Source is live — Host GL owns the encoder. */
    fun shouldUseCarPlayHost(): Boolean = secondaryEgressLive.get()
}
