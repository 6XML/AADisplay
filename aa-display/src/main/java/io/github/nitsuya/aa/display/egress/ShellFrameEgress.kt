package io.github.nitsuya.aa.display.egress

import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Neutral bridge so AA UI can offer its composited shell frames to a secondary egress
 * (today: wireless CarPlay Source) without importing the carplay package.
 *
 * CarPlay installs a [Sink] when its projection service class loads; until then attach
 * calls are remembered and replayed on install / session start.
 */
object ShellFrameEgress {
    interface Sink {
        fun attachShell(view: View?)
        fun onShellSize(width: Int, height: Int)
    }

    private val aaUiAttached = AtomicBoolean(false)
    @Volatile
    private var sink: Sink? = null
    @Volatile
    private var lastShell: WeakReference<View>? = null
    @Volatile
    private var lastWidth: Int = 0
    @Volatile
    private var lastHeight: Int = 0

    fun install(sink: Sink?) {
        this.sink = sink
        if (sink == null) return
        val shell = lastShell?.get()
        sink.attachShell(shell)
        if (lastWidth > 0 && lastHeight > 0) {
            sink.onShellSize(lastWidth, lastHeight)
        }
    }

    fun setAaUiAttached(attached: Boolean) {
        aaUiAttached.set(attached)
        ProjectionSessionGate.setAaUiAttached(attached)
        if (!attached) {
            attachShell(null)
        }
    }

    fun isAaUiAttached(): Boolean = aaUiAttached.get()

    fun attachShell(view: View?) {
        lastShell = if (view != null) WeakReference(view) else null
        if (view != null && view.width > 0 && view.height > 0) {
            lastWidth = view.width
            lastHeight = view.height
        }
        sink?.attachShell(view)
    }

    fun onShellSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            lastWidth = width
            lastHeight = height
        }
        sink?.onShellSize(width, height)
    }

    /** Re-push the cached shell into the current sink (e.g. after a new CarPlay session). */
    fun rebindSink() {
        val s = sink ?: return
        s.attachShell(lastShell?.get())
        if (lastWidth > 0 && lastHeight > 0) {
            s.onShellSize(lastWidth, lastHeight)
        }
    }
}
