package io.github.nitsuya.aa.display.carplay.gles

import android.opengl.EGLSurface
import android.view.Surface

/** EGL window surface bound to a [Surface] (typically MediaCodec input). */
class EglWindowSurface(
    private val eglCore: EglCore,
    private val surface: Surface,
) {
    private var eglSurface: EGLSurface = eglCore.createWindowSurface(surface)

    fun makeCurrent() = eglCore.makeCurrent(eglSurface)

    fun swapBuffers(): Boolean = eglCore.swapBuffers(eglSurface)

    fun setPresentationTime(nsecs: Long) = eglCore.setPresentationTime(eglSurface, nsecs)

    fun release() {
        eglCore.releaseSurface(eglSurface)
        eglSurface = android.opengl.EGL14.EGL_NO_SURFACE
    }
}
