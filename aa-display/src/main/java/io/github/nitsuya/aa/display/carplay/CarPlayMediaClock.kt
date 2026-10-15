package io.github.nitsuya.aa.display.carplay

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared stream timeline for CarPlay screen + audio.
 *
 * DiPlay paces video from the screen-header NTP field and audio from RTP timestamps.
 * Independent counters starting at 0 (and audio SETUP after the encoder is already
 * running) make audio lead/lag the picture. Both sides read the same
 * [elapsedRealtimeNanos] epoch instead.
 */
object CarPlayMediaClock {
    private val epochElapsedNs = AtomicLong(0L)

    /** Mark t=0 for a new RECORD / screen arm. Safe to call again on re-SETUP. */
    fun reset() {
        epochElapsedNs.set(SystemClock.elapsedRealtimeNanos())
    }

    fun clear() {
        epochElapsedNs.set(0L)
    }

    fun isRunning(): Boolean = epochElapsedNs.get() != 0L

    fun ensureRunning() {
        epochElapsedNs.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
    }

    /** Nanoseconds since [reset], for MediaCodec / screen NTP 32.32. */
    fun videoTimestampNs(): Long {
        val epoch = epochElapsedNs.get()
        if (epoch == 0L) return 0L
        return (SystemClock.elapsedRealtimeNanos() - epoch).coerceAtLeast(0L)
    }

    /** RTP media timestamp (sample units) for [sampleRate] Hz PCM. */
    fun rtpTimestamp(sampleRate: Int): Int {
        val rate = sampleRate.coerceAtLeast(1)
        val elapsed = videoTimestampNs()
        return ((elapsed * rate) / 1_000_000_000L).toInt() and 0x7fffffff
    }
}
