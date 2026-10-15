package io.github.nitsuya.aa.display.carplay.gles

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts

/** GLES2 helpers for OES external textures and simple textured-quad shaders. */
object GlUtil {
    /** Column-major 4x4 identity. */
    val IDENTITY_MATRIX: FloatArray =
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """

    private const val FRAGMENT_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
          gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    private const val FRAGMENT_2D = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        void main() {
          gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    fun createProgramOes(): Int = createProgram(VERTEX_SHADER, FRAGMENT_OES)

    fun createProgram2d(): Int = createProgram(VERTEX_SHADER, FRAGMENT_2D)

    fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGlError("glBindTexture OES")
        return texId
    }

    fun createTexture2d(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError("glGenTextures 2d")
        val texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texId
    }

    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(CarPlayConsts.TAG, "$op: glError 0x${Integer.toHexString(error)}")
            throw RuntimeException("$op: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertex == 0) return 0
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            Log.e(CarPlayConsts.TAG, "Could not create program")
            return 0
        }
        GLES20.glAttachShader(program, vertex)
        checkGlError("glAttachShader vertex")
        GLES20.glAttachShader(program, fragment)
        checkGlError("glAttachShader fragment")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(CarPlayConsts.TAG, "Could not link program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(CarPlayConsts.TAG, "Could not compile shader $shaderType: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
