package io.github.nitsuya.aa.display.carplay.gles

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import io.github.nitsuya.aa.display.carplay.CarPlayConsts

/**
 * Minimal EGL14 display / context for recordable MediaCodec input surfaces.
 * Patterns follow Google Grafika (Apache-2.0); rewritten in-tree, not vendored.
 */
class EglCore(
    sharedContext: EGLContext? = null,
    flags: Int = FLAG_RECORDABLE,
) {
    companion object {
        const val FLAG_RECORDABLE = 0x01
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("unable to initialize EGL14")
        }
        val share = sharedContext ?: EGL14.EGL_NO_CONTEXT
        val config = chooseConfig(flags) ?: throw RuntimeException("unable to find RGB8888 EGLConfig")
        val attribList = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(eglDisplay, config, share, attribList, 0)
        checkEglError("eglCreateContext")
        eglConfig = config
        eglContext = ctx
    }

    private fun chooseConfig(flags: Int): EGLConfig? {
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE, 0,
            EGL14.EGL_NONE,
        )
        if (flags and FLAG_RECORDABLE != 0) {
            attribList[attribList.size - 3] = EGL_RECORDABLE_ANDROID
            attribList[attribList.size - 2] = 1
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            Log.w(CarPlayConsts.TAG, "eglChooseConfig RGB8888/recordable failed")
            return null
        }
        return configs[0]
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is SurfaceTexture) {
            throw RuntimeException("invalid surface: $surface")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == null) throw RuntimeException("surface was null")
        return eglSurface
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE,
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface")
        if (eglSurface == null) throw RuntimeException("surface was null")
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        ) {
            throw RuntimeException("eglMakeCurrent(nothing) failed")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean =
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error 0x${Integer.toHexString(error)}")
        }
    }
}
