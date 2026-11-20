package yourec

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/// GL thread. Mixes two finished pictures, each first drawn into a texture of
/// the canvas size, as the iPhone app's transition styles do. The arithmetic
/// is TransitionMix's, which the core checks; this is its shader.
class TransitionCompositor {
    private val targets = IntArray(2); private val buffers = IntArray(2)
    private var width = 0; private var height = 0
    private val program: Int
    private val position: Int
    private val uniforms = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    init {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Transition shader: " + GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val v = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; void main() { gl_Position = position; }")
        val f = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
        val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Transition program: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        position = GLES20.glGetAttribLocation(program, "position")
    }
    private fun u(name: String) = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    /// Make the two targets the canvas size. Returns the framebuffer to draw picture `index` into.
    fun target(index: Int, w: Int, h: Int): Int {
        if (w != width || h != height) {
            release(); width = w; height = h
            GLES20.glGenTextures(2, targets, 0); GLES20.glGenFramebuffers(2, buffers, 0)
            for (i in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets[i])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                for ((name, value) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                    GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targets[i], 0)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        return buffers[index]
    }

    /// Draw the mix into the framebuffer bound now, over the whole canvas.
    fun composite(frame: RenderPlan.TransitionFrame, w: Int, h: Int, framesPerSecond: Double) {
        val s = frame.settings; val t = frame.progress.coerceIn(0.0, 1.0)
        val dw = w.toDouble(); val dh = h.toDouble()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        for (i in 0..1) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets[i]) }
        GLES20.glUniform1i(u("first"), 0); GLES20.glUniform1i(u("second"), 1)
        GLES20.glUniform2f(u("size"), w.toFloat(), h.toFloat())
        GLES20.glUniform1i(u("style"), s.style.ordinal)
        GLES20.glUniform1f(u("t"), t.toFloat()); GLES20.glUniform1f(u("eased"), TransitionMix.eased(t).toFloat())
        val fill = frame.fill; GLES20.glUniform3f(u("fill"), fill.red.toFloat(), fill.green.toFloat(), fill.blue.toFloat())
        when (s.style) {
            TransitionStyle.Wipe -> { val w0 = TransitionMix.wipe(s, t, dw, dh); GLES20.glUniform4f(u("wipe"), w0.dx.toFloat(), w0.dy.toFloat(), w0.p0x.toFloat(), w0.p0y.toFloat()); GLES20.glUniform1f(u("feather"), w0.feather.toFloat()) }
            TransitionStyle.Hitchcock -> { val k = TransitionMix.hitchcock(s, t); GLES20.glUniform3f(u("key"), k.sign.toFloat(), k.feather.toFloat(), k.bias.toFloat()) }
            TransitionStyle.FlashCut -> GLES20.glUniform1f(u("flash"), if (TransitionMix.flashFrame(t, frame.videoDuration, framesPerSecond) in TransitionMix.flashFrames(s, frame.videoDuration, framesPerSecond)) 1f else 0f)
            TransitionStyle.Whip -> GLES20.glUniform4f(u("whip"), TransitionMix.whipDistance(s, dw, dh).toFloat(), TransitionMix.whipRadius(t, dw, dh).toFloat(), if (s.axis == TransitionAxis.Vertical) 1f else 0f, 0f)
            TransitionStyle.Defocus -> GLES20.glUniform2f(u("defocus"), TransitionMix.defocusRadius(s, t, dw, dh).toFloat(), TransitionMix.defocusBlend(t).toFloat())
            TransitionStyle.Split -> {
                val (a, b) = TransitionMix.splitRects(s, dw, dh)
                val ca = TransitionMix.splitCrop(s.first, a, dw, dh); val cb = TransitionMix.splitCrop(s.second, b, dw, dh); val line = TransitionMix.splitLine(s, dw, dh)
                fun rect(name: String, r: Rectangle) = GLES20.glUniform4f(u(name), r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat())
                rect("viewA", a); rect("viewB", b); rect("cropA", ca); rect("cropB", cb); rect("line", line)
            }
            else -> { }
        }
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        for (i in 1 downTo 0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) }
    }

    fun release() {
        if (width != 0) { GLES20.glDeleteFramebuffers(2, buffers, 0); GLES20.glDeleteTextures(2, targets, 0) }
        width = 0; height = 0
    }
    fun releaseProgram() { release(); GLES20.glDeleteProgram(program) }

    private companion object {
        // Style numbers follow TransitionStyle's order: Dissolve, FadeColor, Wipe,
        // Split, Hitchcock, Whip, FlashCut, Still, Defocus.
        const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D first; uniform sampler2D second; uniform vec2 size;
            uniform int style; uniform float t; uniform float eased; uniform vec3 fill;
            uniform vec4 wipe; uniform float feather; uniform vec3 key; uniform float flash;
            uniform vec4 whip; uniform vec2 defocus;
            uniform vec4 viewA; uniform vec4 viewB; uniform vec4 cropA; uniform vec4 cropB; uniform vec4 line;
            vec3 A(vec2 p) { return texture2D(first, clamp(p, vec2(0.5), size - 0.5) / size).rgb; }
            vec3 B(vec2 p) { return texture2D(second, clamp(p, vec2(0.5), size - 0.5) / size).rgb; }
            bool inside(vec4 r, vec2 p) { return p.x >= r.x && p.x < r.x + r.z && p.y >= r.y && p.y < r.y + r.w; }
            // Motion blur along one axis, and a disc for the optical defocus.
            vec3 smearA(vec2 p, vec2 axis, float r) { vec3 c = vec3(0.0); for (int i = 0; i < 15; i++) c += A(p + axis * r * (float(i) / 7.0 - 1.0)); return c / 15.0; }
            vec3 smearB(vec2 p, vec2 axis, float r) { vec3 c = vec3(0.0); for (int i = 0; i < 15; i++) c += B(p + axis * r * (float(i) / 7.0 - 1.0)); return c / 15.0; }
            vec3 discA(vec2 p, float r) { vec3 c = A(p); for (int i = 1; i < 24; i++) { float a = float(i) * 2.39996; float d = sqrt(float(i) / 24.0) * r; c += A(p + vec2(cos(a), sin(a)) * d); } return c / 24.0; }
            vec3 discB(vec2 p, float r) { vec3 c = B(p); for (int i = 1; i < 24; i++) { float a = float(i) * 2.39996; float d = sqrt(float(i) / 24.0) * r; c += B(p + vec2(cos(a), sin(a)) * d); } return c / 24.0; }
            void main() {
                vec2 p = gl_FragCoord.xy;
                vec3 c;
                if (t <= 0.0) c = A(p);
                else if (t >= 1.0) c = B(p);
                else if (style == 0 || style == 7) c = mix(A(p), B(p), eased);
                else if (style == 1) c = t < 0.5 ? mix(A(p), fill, min(1.0, t * 2.0)) : mix(fill, B(p), max(0.0, t * 2.0 - 1.0));
                else if (style == 2) c = mix(A(p), B(p), clamp(1.0 - dot(p - wipe.zw, wipe.xy) / (2.0 * feather), 0.0, 1.0));
                else if (style == 4) {
                    vec3 a = A(p);
                    float luma = dot(clamp(a, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
                    c = mix(a, B(p), clamp(key.x * luma / key.y + key.z, 0.0, 1.0));
                } else if (style == 5) {
                    vec2 axis = whip.z > 0.5 ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
                    vec2 sa = whip.z > 0.5 ? vec2(0.0, -eased * whip.x) : vec2(eased * whip.x, 0.0);
                    vec2 sb = whip.z > 0.5 ? vec2(0.0, (1.0 - eased) * whip.x) : vec2(-(1.0 - eased) * whip.x, 0.0);
                    c = mix(smearA(p + sa, axis, whip.y), smearB(p + sb, axis, whip.y), eased);
                } else if (style == 6) c = flash > 0.5 ? B(p) : A(p);
                else if (style == 8) {
                    if (defocus.y <= 0.0) c = discA(p, defocus.x); else if (defocus.y >= 1.0) c = discB(p, defocus.x);
                    else c = mix(discA(p, defocus.x), discB(p, defocus.x), defocus.y);
                } else {
                    vec3 pair = vec3(0.0);
                    if (inside(viewA, p)) pair = A(cropA.xy + (p - viewA.xy) * cropA.zw / viewA.zw);
                    else if (inside(viewB, p)) pair = B(cropB.xy + (p - viewB.xy) * cropB.zw / viewB.zw);
                    if (inside(line, p)) pair = vec3(0.25);
                    c = t < 0.15 ? mix(A(p), pair, t / 0.15) : t > 0.85 ? mix(pair, B(p), (t - 0.85) / 0.15) : pair;
                }
                gl_FragColor = vec4(c, 1.0);
            }"""
    }
}
