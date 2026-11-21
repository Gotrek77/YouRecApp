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
/// A grade ready for the shader: the look as a flat picture of its cube, the
/// input range it covers, its strength and the balance gains.
class FrameGrade(val texture: Int, val dimension: Int, val columns: Int, val rows: Int, val amount: Float, val gain: FloatArray, val low: FloatArray, val span: FloatArray)

class FrameProgram {
    /// One compiled shader. The graded one is only built when a clip needs it.
    private class Shader(graded: Boolean, peaking: Boolean = false, flat: Boolean = false) {
        val program = link("""
            attribute vec4 position; attribute vec4 coordinate; uniform mat4 transform; varying vec2 uv;
            void main() { gl_Position = position; uv = (transform * coordinate).xy; }""",
            (if (peaking) PEAKING else if (!graded) """#extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES frame; uniform float opacity; varying vec2 uv;
            void main() { gl_FragColor = vec4(texture2D(frame, uv).rgb, opacity); }"""
            // Balance first, then the look, mixed in by its strength. The cube is a
            // grid of tiles, one per blue step: two neighbouring tiles are read with
            // the texture's own filtering and blended, which is a trilinear lookup.
            else """#extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES frame; uniform sampler2D lut; varying vec2 uv;
            uniform vec3 gain; uniform vec3 low; uniform vec3 span; uniform vec4 grid; uniform float opacity;
            vec3 tile(vec3 c, float slice) {
                float n = grid.x; float row = floor((slice + 0.5) / grid.y); float column = slice - row * grid.y;
                return texture2D(lut, vec2((column * n + 0.5 + c.r * (n - 1.0)) / (n * grid.y), (row * n + 0.5 + c.g * (n - 1.0)) / (n * grid.z))).rgb;
            }
            void main() {
                vec4 picture = texture2D(frame, uv);
                vec3 c = clamp(picture.rgb * gain, 0.0, 1.0);
                if (grid.w > 0.0) {
                    vec3 p = clamp((c - low) / span, 0.0, 1.0);
                    float z = p.b * (grid.x - 1.0); float z0 = floor(z); float z1 = min(z0 + 1.0, grid.x - 1.0);
                    c = mix(c, mix(tile(p, z0), tile(p, z1), z - z0), grid.w);
                }
                gl_FragColor = vec4(c, opacity);
            }""").let { source ->
                // A picture already in an ordinary texture (an editor effect's restored source frame).
                if (flat) source.replace("#extension GL_OES_EGL_image_external : require", "").replace("samplerExternalOES", "sampler2D") else source
            })
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        val transform = GLES20.glGetUniformLocation(program, "transform")
        val texture = GLES20.glGetUniformLocation(program, "frame")
        val lut = GLES20.glGetUniformLocation(program, "lut")
        val gain = GLES20.glGetUniformLocation(program, "gain")
        val low = GLES20.glGetUniformLocation(program, "low")
        val span = GLES20.glGetUniformLocation(program, "span")
        val grid = GLES20.glGetUniformLocation(program, "grid")
        val opacity = GLES20.glGetUniformLocation(program, "opacity")
        val texel = GLES20.glGetUniformLocation(program, "texel")
    }
    private val plain = Shader(false)
    private var peaking: Shader? = null
    private var graded: Shader? = null
    private var flatPlain: Shader? = null
    private var flatGraded: Shader? = null
    private var flat = false
    private val vertices: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val quad = FloatArray(8)
    private val quadBuffer: FloatBuffer = floats(*quad)

    /// `matrix` is SurfaceTexture's transform. The picture covers scaleX by scaleY
    /// of the viewport, centred; `rotation` turns it clockwise in quarter turns and
    /// `mirror` flips it left to right, both as seen on the output.
    fun draw(textureID: Int, matrix: FloatArray, scaleX: Float = 1f, scaleY: Float = 1f, rotation: Int = 0, mirror: Boolean = false) {
        geometry(scaleX, scaleY, rotation, mirror)
        present(textureID, matrix)
    }
    private fun geometry(scaleX: Float, scaleY: Float, rotation: Int, mirror: Boolean) {
        // Corner order: bottom-left, bottom-right, top-left, top-right. Rotating the
        // quad's corners keeps the texture coordinates, and sampling, untouched.
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        val order = when (((rotation % 360) + 360) % 360) { 90 -> intArrayOf(2, 0, 3, 1); 180 -> intArrayOf(3, 2, 1, 0); 270 -> intArrayOf(1, 3, 0, 2); else -> intArrayOf(0, 1, 2, 3) }
        for (i in 0 until 4) {
            val corner = corners[order[i]]
            quad[i * 2] = corner[0] * scaleX * (if (mirror) -1f else 1f); quad[i * 2 + 1] = corner[1] * scaleY
        }
    }
    private fun present(textureID: Int, matrix: FloatArray, grade: FrameGrade? = null, alpha: Float = 1f, texel: FloatArray? = null) {
        quadBuffer.position(0); quadBuffer.put(quad); quadBuffer.position(0)
        val shader = if (flat) (if (grade == null) flatPlain ?: Shader(false, flat = true).also { flatPlain = it } else flatGraded ?: Shader(true, flat = true).also { flatGraded = it })
            else if (texel != null) peaking ?: Shader(false, true).also { peaking = it } else if (grade == null) plain else graded ?: Shader(true).also { graded = it }
        val target = if (flat) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES

        GLES20.glUseProgram(shader.program)
        if (texel != null) {
            GLES20.glUniform2f(shader.texel, texel[0], texel[1])
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        if (grade != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, grade.texture)
            GLES20.glUniform1i(shader.lut, 1)
            GLES20.glUniform3fv(shader.gain, 1, grade.gain, 0); GLES20.glUniform3fv(shader.low, 1, grade.low, 0); GLES20.glUniform3fv(shader.span, 1, grade.span, 0)
            GLES20.glUniform4f(shader.grid, grade.dimension.toFloat(), grade.columns.toFloat(), grade.rows.toFloat(), if (grade.texture == 0) 0f else grade.amount)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureID)
        GLES20.glUniform1i(shader.texture, 0)
        GLES20.glUniform1f(shader.opacity, alpha)
        // A picture partly through a dissolve is blended over what is already drawn.
        if (alpha < 1f) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA) }
        GLES20.glUniformMatrix4fv(shader.transform, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(shader.position)
        GLES20.glVertexAttribPointer(shader.position, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(shader.coordinate)
        GLES20.glVertexAttribPointer(shader.coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(shader.position); GLES20.glDisableVertexAttribArray(shader.coordinate)
        GLES20.glBindTexture(target, 0)
        if (alpha < 1f || texel != null) GLES20.glDisable(GLES20.GL_BLEND)
    }
    /// Draw a source picture placed by an affine map from its own pixels to canvas
    /// pixels (both with y down). Whatever falls outside the canvas is clipped by
    /// the viewport, which is how a crop, a mirror or a rotation is drawn.
    fun draw(textureID: Int, matrix: FloatArray, placement: Affine, source: CanvasSize, canvas: CanvasSize, grade: FrameGrade? = null, alpha: Float = 1f) {
        // Texture corners in strip order (bottom-left, bottom-right, top-left, top-right) as source pixels.
        val px = doubleArrayOf(0.0, source.width, 0.0, source.width); val py = doubleArrayOf(source.height, source.height, 0.0, 0.0)
        for (i in 0 until 4) {
            quad[i * 2] = (placement.x(px[i], py[i]) * 2 / canvas.width - 1).toFloat()
            quad[i * 2 + 1] = (1 - placement.y(px[i], py[i]) * 2 / canvas.height).toFloat()
        }
        present(textureID, matrix, grade, alpha)
    }
    /// The same as `draw`, for a picture in an ordinary 2D texture (rows bottom-up), with an identity matrix.
    fun drawFlat(textureID: Int, placement: Affine, source: CanvasSize, canvas: CanvasSize, grade: FrameGrade? = null) {
        flat = true
        try { draw(textureID, identity, placement, source, canvas, grade) } finally { flat = false }
    }
    fun release() { flatPlain?.let { GLES20.glDeleteProgram(it.program) }; flatPlain = null; flatGraded?.let { GLES20.glDeleteProgram(it.program) }; flatGraded = null
        GLES20.glDeleteProgram(plain.program); graded?.let { GLES20.glDeleteProgram(it.program) }; graded = null; peaking?.let { GLES20.glDeleteProgram(it.program) }; peaking = null }
    /// Focus peaking over a picture already drawn with the same geometry: sharp
    /// edges turn red. `texel` is one camera pixel in texture coordinates.
    /// For the recorder's preview only; it must never reach a recording.
    fun drawPeaking(textureID: Int, matrix: FloatArray, scaleX: Float, scaleY: Float, rotation: Int, mirror: Boolean, texel: FloatArray) {
        geometry(scaleX, scaleY, rotation, mirror)
        present(textureID, matrix, texel = texel)
    }

    companion object {
        // The iPhone recorder's sharp-edge test: a strong gradient that is also
        // high-frequency detail, extended by one pixel, drawn in red.
        private const val PEAKING = """#extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES frame; uniform vec2 texel; uniform float opacity; varying vec2 uv;
            float luma(vec2 p) { return dot(texture2D(frame, p).rgb, vec3(0.2126, 0.7152, 0.0722)); }
            float edge(vec2 p) {
                float l = luma(p - vec2(texel.x, 0.0)), r = luma(p + vec2(texel.x, 0.0));
                float t = luma(p - vec2(0.0, texel.y)), b = luma(p + vec2(0.0, texel.y)), c = luma(p);
                float gradient = length(vec2(r - l, b - t));
                float detail = max(abs(l + r - 2.0 * c), abs(t + b - 2.0 * c));
                return smoothstep(0.065, 0.16, gradient) * smoothstep(0.017, 0.06, detail);
            }
            void main() {
                float s = max(edge(uv), 0.9 * max(edge(uv + vec2(texel.x, 0.0)), edge(uv + vec2(0.0, texel.y))));
                gl_FragColor = vec4(1.0, 0.045, 0.025, s);
            }"""
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

/// Draws an ordinary texture with transparency over what is already there:
/// titles, drawn once into a bitmap and placed like any other picture.
class OverlayProgram {
    private val opacity: Int
    private val program: Int
    private val position: Int
    private val coordinate: Int
    private val image: Int
    private val quad = FloatArray(8)
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    // A bitmap's first row is its top: these coordinates put it back on top.
    private val coordinates: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0) }

    init {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(id) }
            return id
        }
        program = GLES20.glCreateProgram()
        val v = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec2 coordinate; varying vec2 uv; void main() { gl_Position = position; uv = coordinate; }")
        val f = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; uniform sampler2D image; uniform float opacity; varying vec2 uv; void main() { gl_FragColor = texture2D(image, uv) * opacity; }")
        GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
        val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        position = GLES20.glGetAttribLocation(program, "position")
        coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        image = GLES20.glGetUniformLocation(program, "image")
        opacity = GLES20.glGetUniformLocation(program, "opacity")
    }

    /// `placement` maps the image's own pixels to canvas pixels, both with y down.
    fun draw(textureID: Int, placement: Affine, size: CanvasSize, canvas: CanvasSize, alpha: Float = 1f) {
        val px = doubleArrayOf(0.0, size.width, 0.0, size.width); val py = doubleArrayOf(size.height, size.height, 0.0, 0.0)
        for (i in 0 until 4) {
            quad[i * 2] = (placement.x(px[i], py[i]) * 2 / canvas.width - 1).toFloat()
            quad[i * 2 + 1] = (1 - placement.y(px[i], py[i]) * 2 / canvas.height).toFloat()
        }
        quadBuffer.position(0); quadBuffer.put(quad); quadBuffer.position(0)
        GLES20.glUseProgram(program)
        // Android bitmaps carry colour already multiplied by alpha.
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureID)
        GLES20.glUniform1i(image, 0)
        GLES20.glUniform1f(opacity, alpha)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(coordinate)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    fun release() { GLES20.glDeleteProgram(program) }
}
