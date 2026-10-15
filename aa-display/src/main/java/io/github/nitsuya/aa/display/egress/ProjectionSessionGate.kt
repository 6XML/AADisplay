package io.github.nitsuya.aa.display.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process arbitration between Android Auto TextureView sinks and CarPlay Host egress.
 *
 * Video is **exclusive**: either Android Auto or CarPlay Host paints — never both, and never
 * an AA-shell tee into CarPlay. system_server also mirrors secondary-egress live via
 * [io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive].
 */
object ProjectionSessionGate {
    private val aaUiAttached = AtomicBoolean(false)
    private val secondaryEgressLive = AtomicBoolean(false)

    fun setAaUiAttached(attached: Boolean) {
        aaUiAttached.set(attached)
    }

    /** CarPlay Source session live in the app process. */
    fun setCarPlayLive(live: Boolean) {
        secondaryEgressLive.set(live)
    }

    fun isAaUiAttached(): Boolean = aaUiAttached.get()

    fun isCarPlayLive(): Boolean = secondaryEgressLive.get()

    /**
     * Always false: CarPlay must not composite the AA shell. Kept so call sites and logs
     * stay stable after removing dual-channel tee.
     */
    fun shouldTeeFromAaShell(): Boolean = false

    /** True when CarPlay Source is live — Host GL owns the encoder (not AA). */
    fun shouldUseCarPlayHost(): Boolean = secondaryEgressLive.get()
}
