package io.github.nitsuya.aa.display.ui.aa.split

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import io.github.nitsuya.aa.display.util.AvMediaArbiter
import io.github.nitsuya.aa.display.util.MusicAppClassifier
import io.github.nitsuya.aa.display.xposed.util.logDebug

/**
 * Same-pane MediaSession pause/resume when stack promote outruns global
 * [AvMediaArbiter.pauseLosers] (e.g. Douyin FGS under another AvMedia).
 *
 * Sticky AvMedia: pause buried only when this pane's front AvMedia is PLAYING.
 * Non-Av / idle Av fronts never steal focus — but [enforceSingleSounder] still
 * keeps only one AvMedia sounding across both panes (restore / cross-pane).
 *
 * User-driven Av→Av (QQ → 抖音): [claimSpeakerForFront] overrides sticky so a
 * front we previously paused (or that stayed idle) can take the speaker; otherwise
 * resumeFront skips while the buried sounder is still PLAYING → CarPlay silence.
 */
internal class SplitBuriedPlayback(private val c: SplitDisplayController) {

    private val pausedByAADisplay = linkedSetOf<String>()
    private val lastPauseAtMs = HashMap<String, Long>(4)

    fun pauseBuriedStackPlayback(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        val frontPkg = c.stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val buried = c.stacks.packagesBottomToTop(pane)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != frontPkg }
        if (buried.isEmpty()) return
        if (!MusicAppClassifier.isAvMediaPackage(c.context, frontPkg)) return

        val sessions = activeSessions() ?: return
        if (!isPackagePlaying(sessions, frontPkg)) return

        pausePlayingPackages(
            sessions = sessions,
            packages = buried.toSet(),
            reason = "pauseBuried",
            detail = "pane=$pane front=$frontPkg",
        )
    }

    /**
     * Global single-sounder: at most one AvMedia PLAYING across both panes / buried.
     * Call after restore / ensure / multi-pane promote — apps often auto-resume together.
     */
    fun enforceSingleSounder(reason: String) {
        if (c.mIsDestroying) return
        val sessions = activeSessions() ?: return
        val layout = c.avStackLayout()
        // force=true: restore / promote often flips several Av to PLAYING together.
        val winner = AvMediaArbiter.enforceSingleSounder(c.context, sessions, layout, force = true)
        val winnerPkg = winner?.packageName
        if (winnerPkg != null) {
            for (controller in sessions) {
                val pkg = controller.packageName?.trim() ?: continue
                if (pkg == winnerPkg) continue
                if (!MusicAppClassifier.isAvMediaSession(c.context, controller)) continue
                if (!AvMediaArbiter.isPlayingState(
                        controller.playbackState?.state ?: PlaybackState.STATE_NONE,
                    )
                ) {
                    continue
                }
                pausedByAADisplay += pkg
            }
        }
        logDebug(
            SplitDisplayController.TAG,
            "enforceSingleSounder[$reason] winner=$winnerPkg",
        )
    }

    /**
     * Restore / reconnect: sessions often flip to PLAYING after launch settles.
     * Kick now plus a few delayed passes so multi-Av stacks stay single-sounder.
     */
    fun scheduleEnforceSingleSounder(reason: String) {
        c.mHandler.removeCallbacksAndMessages(SINGLE_SOUNDER_TOKEN)
        enforceSingleSounder(reason)
        val now = SystemClock.uptimeMillis()
        for (delay in ENFORCE_RETRY_DELAYS_MS) {
            c.mHandler.postAtTime(
                {
                    if (c.mIsDestroying) return@postAtTime
                    enforceSingleSounder("$reason+$delay")
                },
                SINGLE_SOUNDER_TOKEN,
                now + delay,
            )
        }
    }

    fun cancelScheduledEnforceSingleSounder() {
        c.mHandler.removeCallbacksAndMessages(SINGLE_SOUNDER_TOKEN)
    }

    /**
     * Resume a front we previously paused. User re-promote overrides sticky: pause any
     * other PLAYING AvMedia first, then [play] — otherwise QQ stays winner forever while
     * Douyin (paused by us) never becomes PLAYING → CarPlay silence.
     */
    fun resumeFrontPlaybackIfPausedByUs(frontPkg: String) {
        val pkg = frontPkg.trim().takeIf { it.isNotEmpty() } ?: return
        if (pkg !in pausedByAADisplay) return
        claimSpeakerForFront(pkg, onlyIfPausedByUs = true)
    }

    /**
     * User put [frontPkg] on top (Recent / picker / moveToFront). AvMedia fronts claim
     * the speaker over sticky buried sounders, then start playback if idle so demoting
     * QQ Music cannot leave both sides paused.
     */
    fun claimSpeakerForFront(frontPkg: String, onlyIfPausedByUs: Boolean = false) {
        val pkg = frontPkg.trim().takeIf { it.isNotEmpty() } ?: return
        if (!MusicAppClassifier.isAvMediaPackage(c.context, pkg)) return
        if (onlyIfPausedByUs && pkg !in pausedByAADisplay) return
        val sessions = activeSessions() ?: return
        pausePlayingPackages(
            sessions = sessions,
            packages = null,
            except = pkg,
            reason = if (onlyIfPausedByUs) "resumeFront" else "claimFront",
            detail = "front=$pkg",
        )
        pausedByAADisplay.remove(pkg)
        val controller = sessions.firstOrNull { it.packageName == pkg } ?: return
        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        if (AvMediaArbiter.isPlayingState(state)) {
            logDebug(SplitDisplayController.TAG, "claimFront already playing pkg=$pkg")
            return
        }
        try {
            controller.transportControls.play()
            logDebug(SplitDisplayController.TAG, "claimFront play pkg=$pkg")
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "claimFront play failed pkg=$pkg: ${e.message}")
        }
    }

    private fun activeSessions(): List<MediaController>? {
        val msm = c.context.getSystemService(MediaSessionManager::class.java) ?: return null
        return try {
            msm.getActiveSessions(null)
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "getActiveSessions failed: ${e.message}")
            null
        }
    }

    /**
     * @param packages if non-null, only pause pkgs in this set; else all AvMedia except [except].
     */
    private fun pausePlayingPackages(
        sessions: List<MediaController>,
        packages: Set<String>?,
        except: String? = null,
        reason: String,
        detail: String,
    ) {
        val now = SystemClock.uptimeMillis()
        for (controller in sessions) {
            val pkg = controller.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (except != null && pkg == except) continue
            if (packages != null && pkg !in packages) continue
            if (!MusicAppClassifier.isAvMediaSession(c.context, controller)) continue
            val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            if (!AvMediaArbiter.isPlayingState(state)) continue
            val last = lastPauseAtMs[pkg] ?: 0L
            if (now - last < PAUSE_THROTTLE_MS) continue
            try {
                controller.transportControls.pause()
                pausedByAADisplay += pkg
                lastPauseAtMs[pkg] = now
                logDebug(SplitDisplayController.TAG, "$reason pkg=$pkg $detail")
            } catch (e: Throwable) {
                logDebug(SplitDisplayController.TAG, "$reason failed pkg=$pkg: ${e.message}")
            }
        }
    }

    private fun isPackagePlaying(sessions: List<MediaController>, packageName: String): Boolean {
        return sessions.any { c ->
            c.packageName == packageName &&
                AvMediaArbiter.isPlayingState(
                    c.playbackState?.state ?: PlaybackState.STATE_NONE,
                )
        }
    }

    private companion object {
        const val PAUSE_THROTTLE_MS = 500L
        val SINGLE_SOUNDER_TOKEN = Any()
        val ENFORCE_RETRY_DELAYS_MS = longArrayOf(600L, 1600L, 3200L)
    }
}
