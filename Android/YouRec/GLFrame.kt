package yourec

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/// One EGL context that can draw to a screen surface and to a video encoder.
/// Camera and decoder frames arrive as external textures; the same quad
/// program presents them to the preview and writes them to the movie, so what
/// is seen is what is saved. All calls belong to the thread that owns the context.
class EGLCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var scratch: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL is unavailable" }
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL configuration" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create the EGL context" }
        // A tiny off-screen surface keeps the context usable while no window exists.
        scratch = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 2, EGL14.EGL_HEIGHT, 2, EGL14.EGL_NONE), 0)
        check(scratch != EGL14.EGL_NO_SURFACE) { "Could not create the off-screen surface" }
        makeCurrent(null)
    }

    fun windowSurface(surface: Any): EGLSurface {
        require(surface is Surface || surface is SurfaceTexture)
        val result = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "Could not create a window surface (0x" + Integer.toHexString(EGL14.eglGetError()) + ")" }
        return result
    }
    fun makeCurrent(surface: EGLSurface?) {
        val target = surface ?: scratch
        check(EGL14.eglMakeCurrent(display, target, target, context)) { "eglMakeCurrent failed" }
    }
    fun width(surface: EGLSurface) = query(surface, EGL14.EGL_WIDTH)
    fun height(surface: EGLSurface) = query(surface, EGL14.EGL_HEIGHT)
    private fun query(surface: EGLSurface, what: Int): Int { val value = IntArray(1); EGL14.eglQuerySurface(display, surface, what, value, 0); return value[0] }
    /// The time written into the movie for the frame about to be swapped.
    fun setPresentationTime(surface: EGLSurface, nanoseconds: Long) { EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds) }
    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)
    fun release(surface: EGLSurface) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == surface) makeCurrent(null)
        EGL14.eglDestroySurface(display, surface)
    }
    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, scratch)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }
}

/// Draws one external (camera or decoder) texture as a quad.
class FrameProgram {
    private val program: Int
    private val position: Int
    private val coordinate: Int
    private val transform: Int
    private val texture: Int
    private val vertices: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val quad = FloatArray(8)
    private val quadBuffer: FloatBuffer = floats(*quad)

    init {
        program = link("""
            attribute vec4 position; attribute vec4 coordinate; uniform mat4 transform; varying vec2 uv;
            void main() { gl_Position = position; uv = (transform * coordinate).xy; }""",
            """#extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES frame; varying vec2 uv;
            void main() { gl_FragColor = texture2D(frame, uv); }""")
        position = GLES20.glGetAttribLocation(program, "position")
        coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        transform = GLES20.glGetUniformLocation(program, "transform")
        texture = GLES20.glGetUniformLocation(program, "frame")
    }

    /// `matrix` is SurfaceTexture's transform. The picture covers scaleX by scaleY
    /// of the viewport, centred; `rotation` turns it clockwise in quarter turns and
    /// `mirror` flips it left to right, both as seen on the output.
    fun draw(textureID: Int, matrix: FloatArray, scaleX: Float = 1f, scaleY: Float = 1f, rotation: Int = 0, mirror: Boolean = false) {
        // Corner order: bottom-left, bottom-right, top-left, top-right. Rotating the
        // quad's corners keeps the texture coordinates, and sampling, untouched.
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        val order = when (((rotation % 360) + 360) % 360) { 90 -> intArrayOf(2, 0, 3, 1); 180 -> intArrayOf(3, 2, 1, 0); 270 -> intArrayOf(1, 3, 0, 2); else -> intArrayOf(0, 1, 2, 3) }
        for (i in 0 until 4) {
            val corner = corners[order[i]]
            quad[i * 2] = corner[0] * scaleX * (if (mirror) -1f else 1f); quad[i * 2 + 1] = corner[1] * scaleY
        }
        quadBuffer.position(0); quadBuffer.put(quad); quadBuffer.position(0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glUniform1i(texture, 0)
        GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(coordinate)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }
    fun release() { GLES20.glDeleteProgram(program) }

    companion object {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        fun externalTexture(): Int {
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }
        fun deleteTexture(id: Int) { GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
        fun clear(width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(id) }
                return id
            }
            val program = GLES20.glCreateProgram()
            val v = shader(GLES20.GL_VERTEX_SHADER, vertex); val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Program: " + GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return program
        }
    }
}
