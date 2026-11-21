package yourec

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/// GL thread. The iPhone app's per-pixel video effects (VideoEffects.metal,
/// ve_effect) as GLSL: the picture of one layer, graded and framed at the
/// canvas size, goes through one pass per active effect, then is drawn where
/// the layer belongs. The iPhone app works on linear BT.709 in half floats;
/// here pictures between passes stay display-encoded in 8 bits and each pass
/// decodes to linear, applies the effect's arithmetic and encodes again.
class VideoEffectShader {
    /// One render target: a texture of display-encoded RGBA and its framebuffer.
    class Target(val texture: Int, val buffer: Int, val width: Int, val height: Int) { var busy = false; var pinned = false }
    private val pool = ArrayList<Target>()
    private var lattice = 0
    private val programs = HashMap<String, Pass>()
    private val full: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val unit: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val quad = FloatArray(8)
    private val quadBuffer: FloatBuffer = floats(*quad)
    private val present: Int
    private val presentPosition: Int; private val presentCoordinate: Int; private val presentImage: Int

    /// A compiled full-screen pass and its uniform locations.
    private class Pass(fragment: String) {
        val id = link(VERTEX, fragment)
        val position = GLES20.glGetAttribLocation(id, "position")
        private val locations = HashMap<String, Int>()
        fun u(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    }

    init {
        present = link("attribute vec4 position; attribute vec2 coordinate; varying vec2 uv; void main() { gl_Position = position; uv = coordinate; }",
            "precision mediump float; uniform sampler2D image; varying vec2 uv; void main() { gl_FragColor = vec4(texture2D(image, uv).rgb, 1.0); }")
        presentPosition = GLES20.glGetAttribLocation(present, "position"); presentCoordinate = GLES20.glGetAttribLocation(present, "coordinate")
        presentImage = GLES20.glGetUniformLocation(present, "image")
    }

    private fun take(w: Int, h: Int): Target {
        pool.firstOrNull { !it.busy && it.width == w && it.height == h }?.let { it.busy = true; return it }
        val ids = IntArray(1); val fbo = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        parameters(GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return Target(ids[0], fbo[0], w, h).also { it.busy = true; pool.add(it) }
    }

    /// Start a new frame: every target not kept by `pin` is free again.
    fun reset(w: Int, h: Int) {
        for (t in pool) t.busy = t.pinned
        // Targets larger than the canvas (a changed canvas) are given back.
        val stale = pool.filter { !it.pinned && (it.width > w || it.height > h) }
        for (t in stale) { GLES20.glDeleteFramebuffers(1, intArrayOf(t.buffer), 0); GLES20.glDeleteTextures(1, intArrayOf(t.texture), 0) }
        pool.removeAll(stale)
    }
    /// The framebuffer to draw one source picture into, cleared, at the canvas size.
    fun picture(w: Int, h: Int): Target {
        val target = take(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.buffer)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return target
    }
    /// Keep a result across frames (a Step printing block), or let it go.
    fun pin(target: Target, kept: Boolean) { target.pinned = kept; target.busy = kept }
    fun free(target: Target) { if (!target.pinned) target.busy = false }
    /// `sum` mixed toward `next` by `amount`, in linear light. Both inputs are freed.
    fun blend(sum: Target, next: Target, amount: Float): Target {
        val output = take(sum.width, sum.height)
        run(pass("blend") { HEADER + BLEND }, output, listOf("image" to sum.texture, "next" to next.texture)) { p -> GLES20.glUniform1f(p.u("amount"), amount) }
        free(sum); free(next)
        return output
    }

    /// Run `pass` into `output`, reading `inputs` (sampler name to target) on successive units.
    private fun run(pass: Pass, output: Target, inputs: List<Pair<String, Int>>, set: (Pass) -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.buffer)
        GLES20.glViewport(0, 0, output.width, output.height)
        GLES20.glUseProgram(pass.id)
        for ((unit, input) in inputs.withIndex()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.second)
            GLES20.glUniform1i(pass.u(input.first), unit)
        }
        GLES20.glUniform2f(pass.u("size"), output.width.toFloat(), output.height.toFloat())
        set(pass)
        GLES20.glEnableVertexAttribArray(pass.position)
        GLES20.glVertexAttribPointer(pass.position, 2, GLES20.GL_FLOAT, false, 0, full)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pass.position)
        for (unit in inputs.indices.reversed()) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) }
    }
    private fun pass(name: String, source: () -> String) = programs.getOrPut(name) { Pass(source()) }

    /// The iPhone app's ve_reduce: an area average into a smaller target.
    private fun reduce(input: Target, w: Int, h: Int): Target {
        val output = take(w, h)
        run(pass("reduce") { HEADER + REDUCE }, output, listOf("image" to input.texture)) { p ->
            GLES20.glUniform2f(p.u("sourceSize"), input.width.toFloat(), input.height.toFloat())
        }
        return output
    }
    /// ve_highlights: what is brighter than `threshold` (soft by `softness`), averaged into w×h.
    private fun highlights(input: Target, w: Int, h: Int, threshold: Float, softness: Float): Target {
        val output = take(w, h)
        run(pass("highlights") { HEADER + HIGHLIGHTS }, output, listOf("image" to input.texture)) { p ->
            GLES20.glUniform2f(p.u("sourceSize"), input.width.toFloat(), input.height.toFloat())
            GLES20.glUniform2f(p.u("threshold"), threshold, softness)
        }
        return output
    }
    /// MetalEffectRenderer.blur: reduce while the radius would exceed 14
    /// pixels, then a separable Gaussian with paired bilinear taps.
    fun blur(input: Target, sigma: Double, size: Pair<Int, Int>? = null, horizontalOnly: Boolean = false): Target {
        val requestedWidth = size?.first ?: input.width; val requestedHeight = size?.second ?: input.height
        val downscale = minOf(1.0, 14 / maxOf(0.25, sigma * requestedWidth / input.width))
        val w = maxOf(2, (requestedWidth * downscale).toInt()); val h = maxOf(2, (requestedHeight * downscale).toInt())
        val source = if (w < input.width || h < input.height) reduce(input, w, h) else input
        val radius = (sigma * w / input.width).toFloat()
        val a = gaussian(source, radius, true)
        if (source !== input) source.busy = false
        val b = gaussian(a, radius, horizontalOnly)
        a.busy = false
        return b
    }
    private fun gaussian(source: Target, sigma: Float, horizontal: Boolean): Target {
        val output = take(source.width, source.height)
        val weights = VideoEffectRendering.gaussianWeights(sigma)
        run(pass("blur") { HEADER + BLUR }, output, listOf("image" to source.texture)) { p ->
            GLES20.glUniform4f(p.u("header"), weights[0], weights[1], if (horizontal) 1f else 0f, if (horizontal) 0f else 1f)
            GLES20.glUniform2fv(p.u("pairs"), 24, weights, 2)
        }
        return output
    }

    /// Apply `effects` in order to the picture drawn after `begin`. Returns the target holding the result.
    fun apply(effects: List<VideoEffectSettings>, sourceTime: Double, fps: Double, picture: Target, frameTime: Double = sourceTime, holdTime: Double? = null,
              targets: Map<java.util.UUID, Pair<Double, Double>> = emptyMap(), palettes: (VideoEffectSettings, Target) -> EffectPalette? = { _, _ -> null }): Target {
        var current = picture
        GLES20.glDisable(GLES20.GL_BLEND)
        for (effect in effects) {
            val u = VideoEffectRendering.uniforms(effect, sourceTime, fps, frameTime, holdTime, targets[effect.id])
            val inputs = ArrayList<Pair<String, Int>>()
            inputs.add("image" to current.texture)
            val auxiliary = encode(effect, u, current, sourceTime)
            for ((name, target) in auxiliary) inputs.add(name to target.texture)
            val grain = u.kind == 5 || u.kind == 12 || u.kind == 16 || u.kind == 17
            if (grain) inputs.add("lattice" to latticeTexture())
            // Quantization reads its palette from the picture as it reaches it.
            val palette = if (effect.kind == VideoEffectID.quantization) palettes(effect, current) else null
            val program = pass("effect-" + u.kind) { "#define KIND ${u.kind}\n" + FRAGMENT }
            val output = take(current.width, current.height)
            val values = VideoEffectRendering.frameValues(u)
            run(program, output, inputs) { p ->
                GLES20.glUniform4fv(p.u("p"), PARAMETERS, u.p, 0)
                GLES20.glUniform1f(p.u("time"), u.time)
                GLES20.glUniform1f(p.u("intensity"), u.intensity)
                GLES20.glUniform4fv(p.u("frameValues"), 2, values, 0)
                if (grain) { val (x, y) = VideoEffectRendering.latticeOffset(u.frame); GLES20.glUniform2f(p.u("latticeOffset"), x.toFloat(), y.toFloat()) }
                if (effect.kind == VideoEffectID.quantization) {
                    val labs = FloatArray(64); val bands = FloatArray(15)
                    palette?.colors?.take(16)?.forEachIndexed { i, color -> val l = EffectColorMath.lab(EffectColorMath.linear(color)); for (k in 0..2) labs[i * 4 + k] = l[k].toFloat(); labs[i * 4 + 3] = 1f }
                    palette?.thresholds?.take(15)?.forEachIndexed { i, t -> bands[i] = t.toFloat() }
                    GLES20.glUniform4fv(p.u("paletteLab"), 16, labs, 0); GLES20.glUniform1fv(p.u("bands"), 15, bands, 0)
                    GLES20.glUniform1f(p.u("colorCount"), (palette?.colors?.size ?: 0).toFloat())
                }
            }
            for (target in auxiliary.values) free(target)
            free(current)
            current = output
        }
        current.busy = true
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return current
    }

    /// The auxiliary pictures one effect reads, as MetalEffectRenderer.encode makes them.
    private fun encode(settings: VideoEffectSettings, u: VideoEffectRendering.Uniforms, input: Target, time: Double): Map<String, Target> {
        val width = input.width; val height = input.height; val scale = minOf(width, height) / 1080.0
        return when (settings.kind) {
            VideoEffectID.sixteenMM -> if (settings.number("softness", time) > 0) mapOf("aux" to blur(input, 1.5 * scale)) else emptyMap()
            VideoEffectID.superEight -> mapOf("aux" to blur(input, 2 * scale))
            VideoEffectID.quantization -> if (settings.number("smoothing", time) > 0) {
                // Edge-preserving smoothing at about 1080p, horizontal then vertical.
                val factor = 1 / maxOf(1.0, scale); val w = maxOf(2, (width * factor).toInt()); val h = maxOf(2, (height * factor).toInt())
                val sigma = ((1 + 4 * settings.number("smoothing", time)) * scale * factor).toFloat()
                val first = take(w, h)
                run(pass("bilateral") { HEADER + BILATERAL }, first, listOf("image" to input.texture)) { p -> GLES20.glUniform4f(p.u("bilateral"), sigma, 1f, 0f, 0f) }
                val second = take(w, h)
                run(pass("bilateral") { HEADER + BILATERAL }, second, listOf("image" to first.texture)) { p -> GLES20.glUniform4f(p.u("bilateral"), sigma, 0f, 1f, 0f) }
                free(first)
                mapOf("aux" to second)
            } else emptyMap()
            VideoEffectID.halation, VideoEffectID.widescreen -> {
                val wide = settings.kind == VideoEffectID.widescreen
                val small = highlights(input, maxOf(2, width / 4), maxOf(2, height / 4), if (wide) .9f else .75f, if (wide) .05f else .1f)
                val result = HashMap<String, Target>()
                if (wide) {
                    result["aux"] = blur(small, width / 24.0 / 4, horizontalOnly = true)
                    if (settings.choice("bars", time) == "Blur fill") result["background"] = blur(input, 12 * scale, Pair(maxOf(2, width / 4), maxOf(2, height / 4)))
                } else {
                    result["aux"] = blur(small, width * 0.01 / 4); result["background"] = blur(small, width * 0.006 / 4); result["extra"] = blur(small, width * 0.04 / 4)
                }
                small.busy = false
                result
            }
            else -> emptyMap()
        }
    }

    /// The picture reduced to 64×36 (36×64 upright) as linear RGB samples, for palette analysis.
    fun samples(picture: Target): List<DoubleArray> {
        val w = if (picture.width >= picture.height) 64 else 36; val h = if (w == 64) 36 else 64
        val small = reduce(picture, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, small.buffer)
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        free(small)
        val table = DoubleArray(256) { EffectColorMath.linear(it / 255.0) }
        return (0 until w * h).map { i -> DoubleArray(3) { k -> table[pixels.get(i * 4 + k).toInt() and 255] } }
    }

    /// Draw a finished canvas-size picture into the framebuffer bound now,
    /// placed by an affine map of canvas pixels (y down); null fills the canvas.
    fun draw(texture: Int, w: Int, h: Int, placement: Affine?) {
        // Strip order: bottom-left, bottom-right, top-left, top-right, as canvas pixels with y down.
        val px = doubleArrayOf(0.0, w.toDouble(), 0.0, w.toDouble()); val py = doubleArrayOf(h.toDouble(), h.toDouble(), 0.0, 0.0)
        for (i in 0 until 4) {
            val x = placement?.x(px[i], py[i]) ?: px[i]; val y = placement?.y(px[i], py[i]) ?: py[i]
            quad[i * 2] = (x * 2 / w - 1).toFloat(); quad[i * 2 + 1] = (1 - y * 2 / h).toFloat()
        }
        quadBuffer.position(0); quadBuffer.put(quad); quadBuffer.position(0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(present)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(presentImage, 0)
        GLES20.glEnableVertexAttribArray(presentPosition); GLES20.glVertexAttribPointer(presentPosition, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(presentCoordinate); GLES20.glVertexAttribPointer(presentCoordinate, 2, GLES20.GL_FLOAT, false, 0, unit)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(presentPosition); GLES20.glDisableVertexAttribArray(presentCoordinate)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun latticeTexture(): Int {
        if (lattice != 0) return lattice
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); lattice = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lattice)
        val size = VideoEffectRendering.latticeSize
        val data = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder()).put(VideoEffectRendering.lattice()); data.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
        parameters(GLES20.GL_REPEAT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return lattice
    }
    private fun parameters(wrap: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
    }
    fun release() {
        for (t in pool) { GLES20.glDeleteFramebuffers(1, intArrayOf(t.buffer), 0); GLES20.glDeleteTextures(1, intArrayOf(t.texture), 0) }
        pool.clear()
        if (lattice != 0) GLES20.glDeleteTextures(1, intArrayOf(lattice), 0); lattice = 0
        for (program in programs.values) GLES20.glDeleteProgram(program.id)
        programs.clear(); GLES20.glDeleteProgram(present)
    }

    companion object {
        /// Parameters read by the effects drawn here (Color Correction uses twelve).
        const val PARAMETERS = 12
        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "Video effect shader: " + GLES20.glGetShaderInfoLog(id) }
                return id
            }
            val v = shader(GLES20.GL_VERTEX_SHADER, vertex); val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Video effect program: " + GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return program
        }
        private const val VERTEX = "attribute vec4 position; void main() { gl_Position = position; }"

        /// The fragment shader; KIND is the effect's shader index. Coordinates
        /// are normalised and top-down as in the iPhone app; spatial
        /// parameters scale from a 1080 pixel short edge.
        /// What every pass shares: precision, the picture read as linear light
        /// (rows bottom-up in GL, coordinates top-down as in the iPhone app).
        val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D image;
uniform vec2 size;
const float PI = 3.14159265358979;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
float luma(vec3 c) { return dot(c, LUMA); }
vec3 srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 lin(vec3 c) { return mix(c / 12.92, pow(max((c + 0.055) / 1.055, 0.0), vec3(2.4)), step(vec3(0.04045), c)); }
vec3 read(sampler2D t, vec2 uv) { return lin(texture2D(t, vec2(uv.x, 1.0 - uv.y)).rgb); }
vec3 sampleImage(vec2 uv) { return read(image, uv); }
vec4 encode(vec3 c) { return vec4(srgb(clamp(c, 0.0, 1.0)), 1.0); }
"""
        /// ve_reduce: the area average of the source under each output pixel.
        val REDUCE = """
uniform vec2 sourceSize;
void main() {
    vec2 ratio = sourceSize / size, count = max(vec2(1.0), ceil(ratio - 1e-4));
    vec2 q = floor(gl_FragCoord.xy); vec3 total = vec3(0.0);
    for (int y = 0; y < 16; y++) { if (float(y) >= count.y) break;
        for (int x = 0; x < 16; x++) { if (float(x) >= count.x) break;
            total += lin(texture2D(image, (q + (vec2(float(x), float(y)) + .5) / count) / size).rgb); } }
    gl_FragColor = encode(total / (count.x * count.y));
}
"""
        /// ve_highlights: each source sample thresholded before the area average,
        /// so small bright points survive the reduction.
        val HIGHLIGHTS = """
uniform vec2 sourceSize;
uniform vec2 threshold;
void main() {
    vec2 ratio = sourceSize / size, count = max(vec2(1.0), ceil(ratio - 1e-4));
    vec2 q = floor(gl_FragCoord.xy); vec3 sum = vec3(0.0);
    for (int y = 0; y < 16; y++) { if (float(y) >= count.y) break;
        for (int x = 0; x < 16; x++) { if (float(x) >= count.x) break;
            vec3 c = lin(texture2D(image, (q + (vec2(float(x), float(y)) + .5) / count) / size).rgb);
            sum += c * smoothstep(threshold.x - threshold.y, threshold.x + threshold.y, luma(c)); } }
    gl_FragColor = encode(sum / (count.x * count.y));
}
"""
        /// ve_blur: one direction of a Gaussian; each pair of taps is one bilinear read.
        val BLUR = """
uniform vec4 header;
uniform vec2 pairs[24];
void main() {
    vec2 uv = gl_FragCoord.xy / size, direction = header.zw / size;
    vec3 result = lin(texture2D(image, uv).rgb) * header.x;
    for (int i = 0; i < 24; i++) { if (float(i) >= header.y) break;
        vec2 offset = direction * pairs[i].x;
        result += (lin(texture2D(image, uv + offset).rgb) + lin(texture2D(image, uv - offset).rgb)) * pairs[i].y; }
    gl_FragColor = encode(result);
}
"""
        /// ve_bilateral: one direction of an edge-preserving blur into a (smaller) target.
        val BILATERAL = """
uniform vec4 bilateral;
void main() {
    vec2 uv = gl_FragCoord.xy / size;
    vec3 center = lin(texture2D(image, uv).rgb), result = vec3(0.0); float total = 0.0, s = max(.5, bilateral.x);
    float radius = min(10.0, ceil(s * 2.0));
    for (int k = -10; k <= 10; k++) {
        float i = float(k); if (abs(i) > radius) continue;
        vec3 c = lin(texture2D(image, uv + bilateral.yz * i / size).rgb);
        vec3 d = srgb(c) - srgb(center);
        float w = exp(-i * i / (2.0 * s * s) - dot(d, d) / .02);
        result += w * c; total += w;
    }
    gl_FragColor = encode(result / max(total, 1e-6));
}
"""
        /// A normalised dissolve in linear light (Step printing's smear).
        val BLEND = """
uniform sampler2D next;
uniform float amount;
void main() {
    vec2 uv = gl_FragCoord.xy / size;
    gl_FragColor = encode(mix(lin(texture2D(image, uv).rgb), lin(texture2D(next, uv).rgb), amount));
}
"""
        /// The effect pass; KIND is the effect's shader index. Spatial
        /// parameters scale from a 1080 pixel short edge.
        val FRAGMENT = HEADER + """
uniform sampler2D lattice;
uniform sampler2D aux;
uniform sampler2D background;
uniform sampler2D extra;
uniform vec4 p[$PARAMETERS];
uniform float time;
uniform float intensity;
uniform vec2 latticeOffset;
uniform vec4 frameValues[2];
uniform vec4 paletteLab[16];
uniform float bands[15];
uniform float colorCount;
vec3 lab(vec3 c) {
    vec3 v = pow(max(vec3(dot(c, vec3(.4122214708, .5363325363, .0514459929)), dot(c, vec3(.2119034982, .6806995451, .1073969566)), dot(c, vec3(.0883024619, .2817188376, .6299787005))), 0.0), vec3(1.0 / 3.0));
    return vec3(dot(v, vec3(.2104542553, .793617785, -.0040720468)), dot(v, vec3(1.9779984951, -2.428592205, .4505937099)), dot(v, vec3(.0259040371, .7827717662, -.808675766)));
}
vec3 unlab(vec3 c) {
    vec3 v = vec3(c.x + .3963377774 * c.y + .2158037573 * c.z, c.x - .1055613458 * c.y - .0638541728 * c.z, c.x - .0894841775 * c.y - 1.291485548 * c.z); v = v * v * v;
    return vec3(dot(v, vec3(4.0767416621, -3.3077115913, .2309699292)), dot(v, vec3(-1.2684380046, 2.6097574011, -.3413193965)), dot(v, vec3(-.0041960863, -.7034186147, 1.707614701)));
}
vec3 hsv(vec3 c) {
    float hi = max(c.r, max(c.g, c.b)), lo = min(c.r, min(c.g, c.b)), d = hi - lo, h = 0.0;
    if (d > 1e-6) { if (hi == c.r) h = mod((c.g - c.b) / d + 6.0, 6.0); else if (hi == c.g) h = (c.b - c.r) / d + 2.0; else h = (c.r - c.g) / d + 4.0; }
    return vec3(h / 6.0, hi > 1e-6 ? d / hi : 0.0, hi);
}
float hueDistance(float a, float b) { float d = abs(a - b); return min(d, 1.0 - d); }
float gate(vec2 uv, float radius) {
    vec2 d = abs(uv - .5) - vec2(.5 - radius);
    return 1.0 - smoothstep(radius - .004, radius + .004, length(max(d, 0.0)) + min(max(d.x, d.y), 0.0));
}
// The camera's per-frame movement (computed on the CPU from the iPhone app's hashes).
// Ordered dithering (the iPhone app's bayer(), bit by bit, without integer operations).
float bayer(vec2 q, float size) {
    float value = 0.0, scale = 1.0;
    for (int bit = 0; bit < 3; bit++) {
        if (scale >= size) break;
        float x = mod(floor(q.x / scale), 2.0), y = mod(floor(q.y / scale), 2.0);
        value = value * 4.0 + abs(x - y) * 2.0 + y;
        scale *= 2.0;
    }
    return (value + .5) / (size * size);
}
vec2 moved(vec2 uv) { return (uv - .5) / frameValues[0].z + .5 + frameValues[0].xy * (min(size.x, size.y) / 1080.0) / size; }
float sCurve(float y, float contrast) { y = clamp(y, 0.0, 1.0); float a = pow(y, contrast), b = pow(1.0 - y, contrast); return a / max(a + b, 1e-6); }
// Controls operate on perceptual tone values; image math and compositing stay linear.
float curve(float y, float contrast) { return lin(vec3(clamp((srgb(vec3(y)).x - .5) * contrast + .5, 0.0, 1.0))).x; }
vec3 grain(vec3 c, vec2 pixel, float strength, float cellSize, bool mono) {
    float y = clamp(luma(c), 0.0, 1.0), a = strength * .12 * pow(max(0.0, 4.0 * y * (1.0 - y)), .7);
    vec2 point = pixel / cellSize, cell = floor(point), f = fract(point); f = f * f * (3.0 - 2.0 * f);
    vec4 value = texture2D(lattice, (cell + .5 + f + latticeOffset) / ${VideoEffectRendering.latticeSize}.0) * 2.0 - 1.0;
    return clamp(c * exp((mono ? vec3(value.a) : value.rgb) * a * 2.0), 0.0, 1.0);
}
float gamutLimit(float d, float y) { return d > 0.0 ? (1.0 - y) / d : d < 0.0 ? -y / d : 1.0; }
vec3 gradeGamut(vec3 c) {
    float y = clamp(luma(c), 0.0, 1.0); vec3 d = c - y;
    float amount = min(1.0, min(gamutLimit(d.r, y), min(gamutLimit(d.g, y), gamutLimit(d.b, y))));
    return y + d * max(0.0, amount);
}
// C1 rational shoulder fits a known maximum to white, preserving every level
// and a unit slope at the knee. Mirroring it gives a toe for negative Blacks.
float gradeShoulder(float value, float maximum) {
    if (maximum <= 1.0 || value <= .8) return value;
    float a = (maximum - .8) / .2, t = (value - .8) / (maximum - .8);
    return .8 + .2 * a * t / (1.0 + (a - 1.0) * t);
}
float saturationRatio(float d, float target) { return d > 0.0 ? d / max(1.0 - target, 1e-7) : -d / max(target, 1e-7); }
vec3 colorCorrection(vec3 c) {
    if (p[0].x == 0.0 && p[1].x == 1.0 && p[2].x == 0.0 && p[3].x == 0.0 && p[4].x == 0.0 && p[5].x == 0.0 && p[7].x == 0.0 && p[8].x == 0.0 && p[9].x == 1.0 && p[10].x == 0.0 && p[11].x == 0.0) return c;
    float temperature = p[7].x, tint = p[8].x;
    vec3 gains = exp2(vec3(.45 * temperature + .2 * tint, -.2 * tint, -.45 * temperature + .2 * tint));
    c *= gains / dot(gains, LUMA) * exp2(p[0].x);
    float before = luma(c), v = srgb(vec3(max(before, 0.0))).x;
    v = gradeShoulder(v, srgb(vec3(exp2(p[0].x))).x);
    float contrast = p[1].x, pivot = p[6].x;
    if (contrast != 1.0 && v > 0.0 && v < 1.0) {
        float odds = pivot / (1.0 - pivot);
        v = 1.0 / (1.0 + pow(odds, contrast - 1.0) * pow((1.0 - v) / v, contrast));
    }
    v += p[2].x * v * (1.0 - v) * (1.0 - v);
    v += p[3].x * v * v * (1.0 - v);
    float dark = max(1.0 - v, 0.0), light = max(v, 0.0);
    v += .12 * p[4].x * dark * dark * dark * dark + .12 * p[5].x * light * light * light * light;
    v = gradeShoulder(v, 1.0 + .12 * p[5].x);
    v = 1.0 - gradeShoulder(1.0 - v, 1.0 - .12 * p[4].x);
    float target = lin(vec3(v)).x;
    c = before > 1e-7 ? c * (target / before) : vec3(target);
    vec3 perceptual = lab(max(c, 0.0));
    float chroma = length(perceptual.yz);
    // Vibrance mostly affects muted colors; protect common skin hues from boosts.
    vec3 h = hsv(srgb(max(c, 0.0)));
    float skin = smoothstep(.025, .055, h.x) * (1.0 - smoothstep(.12, .16, h.x)) * smoothstep(.08, .2, h.y);
    float vibrance = p[10].x * (1.0 - clamp(chroma / .25, 0.0, 1.0));
    if (vibrance > 0.0) vibrance *= 1.0 - .6 * skin;
    if (p[11].x != 0.0 && chroma > 1e-5) {
        float angle = p[11].x * PI / 180.0, cs = cos(angle), sn = sin(angle);
        perceptual.yz = vec2(cs * perceptual.y - sn * perceptual.z, sn * perceptual.y + cs * perceptual.z);
        c = unlab(perceptual); c *= target / max(luma(c), 1e-7);
    }
    c = gradeGamut(c);
    float gain = p[9].x * (1.0 + vibrance);
    if (gain > 1.0) {
        vec3 delta = c - target;
        float ratio = max(saturationRatio(delta.r, target), max(saturationRatio(delta.g, target), saturationRatio(delta.b, target)));
        // A monotone saturation shoulder: full gain on muted colors, smoothly tending to the gamut boundary.
        gain /= sqrt(1.0 + (gain * gain - 1.0) * ratio * ratio);
    }
    c = target + (c - target) * gain;
    return gradeGamut(c);
}
vec2 kaleidoscope(vec2 uv) {
    // Polar distances are in pixels, so a 120-degree wedge stays 120 degrees in
    // either orientation. The first clockwise sector is kept.
    float count = clamp(floor(p[0].x + 0.5), 2.0, 9.0);
    vec2 center = vec2(p[1].x, p[2].x);
    vec2 d = (uv - center) * size;
    vec2 point = uv;
    if (dot(d, d) >= 1e-8) {
        float axis = p[3].x * (PI / 180.0) - PI / 2.0;
        float turn = 2.0 * PI;
        float span = turn / count;
        float angle = fract((atan(d.y, d.x) - axis) / turn) * turn;
        float sector = min(floor(angle / span), count - 1.0);
        if (sector != 0.0) {
            float local = angle - sector * span;
            // Odd rings repeat the entire wedge by rotation. Even rings alternate reflections.
            if (mod(count, 2.0) == 0.0 && mod(sector, 2.0) == 1.0) local = span - local;
            float mapped = axis + local;
            point = center + length(d) * vec2(cos(mapped), sin(mapped)) / size;
        }
    }
    // Inverse-map the source rotation in picture pixels.
    if (p[6].x != 0.0) {
        float a = -p[6].x * (PI / 180.0);
        vec2 offset = (point - center) * size;
        float cs = cos(a), sn = sin(a);
        point = center + vec2(cs * offset.x - sn * offset.y, sn * offset.x + cs * offset.y) / size;
    }
    point -= vec2(p[4].x, p[5].x);
    if (all(greaterThanEqual(point, vec2(0.0))) && all(lessThanEqual(point, vec2(1.0)))) return point;
    return 1.0 - abs(2.0 * fract(point * 0.5) - 1.0);
}
void main() {
    vec2 q = floor(gl_FragCoord.xy);
    vec2 uv = vec2(gl_FragCoord.x / size.x, 1.0 - gl_FragCoord.y / size.y);
    float pixelScale = min(size.x, size.y) / 1080.0;
    vec3 original = sampleImage(uv), c = original, o = c;
    float y = luma(c);
#if KIND == 22
    o = sampleImage(kaleidoscope(uv));
#elif KIND == 23
    vec2 reach = 0.12 * min(size.x, size.y) / size;
    o = vec3(sampleImage(uv - vec2(p[0].x, p[1].x) * reach).r, sampleImage(uv - vec2(p[2].x, p[3].x) * reach).g, sampleImage(uv - vec2(p[4].x, p[5].x) * reach).b);
#elif KIND == 24
    // A photographic negative complements display values, so mid-gray stays mid-gray.
    o = lin(1.0 - srgb(clamp(c, 0.0, 1.0)));
#elif KIND == 20
    o = colorCorrection(c);
#elif KIND == 0
    // Silent Tint: contrast and dye in linear light.
    vec3 tint = p[0].x < 0.5 ? vec3(.243137, .435294, .658824) : p[0].x < 1.5 ? vec3(.85098, .643137, .254902) : p[0].x < 2.5 ? vec3(.721569, .262745, .184314) : p[0].x < 3.5 ? vec3(.431373, .560784, .305882) : vec3(.541176, .415686, .270588);
    tint = lin(tint);
    float l = curve(y, p[2].x);
    o = p[1].x < .5 ? l * tint : mix(tint * .15, vec3(1.0), smoothstep(0.0, 1.0, l));
#elif KIND == 1
    // Three-Strip; protect skin hue from the red-separation push.
    vec3 h = hsv(srgb(c));
    float skin = smoothstep(.03, .055, h.x) * (1.0 - smoothstep(.115, .15, h.x)) * smoothstep(.12, .3, h.y);
    vec3 separation = c - (c.yzx + c.zxy) * .5;
    o = c + separation * (p[0].x - 1.0) * (1.0 - .65 * skin);
    float red = 1.0 - smoothstep(15.0 / 360.0, 25.0 / 360.0, hueDistance(h.x, 0.0));
    o.gb -= red * p[1].x * .15 * (1.0 - skin);
    float l = luma(o), s = l * l * (3.0 - 2.0 * l); o += (s - l) * .14;
    o *= vec3(1.0 + p[2].x * .06, 1.0, 1.0 - p[2].x * .06);
    float high = max(o.r, max(o.g, o.b)); if (high > .9) o = .9 + (o - .9) / (1.0 + max(0.0, high - .9));
    o = mix(o, c, skin * .35);
#elif KIND == 2
    vec3 overlay = y < .5 ? 2.0 * c * y : 1.0 - 2.0 * (1.0 - c) * (1.0 - y);
    vec3 bleached = mix(vec3(y), overlay, .5);
    bleached = mix(vec3(luma(bleached)), bleached, 1.0 - .3 * p[0].x);
    o = mix(c, bleached, p[0].x);
#elif KIND == 3
    vec3 h = hsv(srgb(c)); float darkness = p[0].x;
    o = mix(vec3(y), c, .4) * exp2(-3.0 * darkness); o *= mix(vec3(1.0), vec3(.55, .7, 1.0), p[1].x);
    float sky = (1.0 - smoothstep(.25, .38, uv.y)) * smoothstep(.5, .85, y) * (1.0 - smoothstep(.08, .18, hueDistance(h.x, .61))); o *= 1.0 - .5 * sky;
    if (p[2].x > .5) o = mix(o, vec3(y * exp2(-.7 * darkness)), smoothstep(.94, .99, y) * .7);
    o *= 1.0 - .15 * smoothstep(.2, .75, length(uv - .5));
#elif KIND == 5
    float l = sCurve(srgb(vec3(dot(c, vec3(.4, .5, .1)))).x, p[0].x);
    l = lin(vec3(l * (1.0 - 2.0 * p[1].x * (1.0 - l)))).x;
    o = vec3(l);
    if (p[3].x > .5) o *= mix(vec3(.94, .97, 1.0), vec3(1.0), l);
    if (p[2].x > .5) o = grain(o, q / pixelScale, .25, 1.0, true);
#elif KIND == 6
    float perceptual = srgb(vec3(y)).x;
    float l = sCurve(perceptual, p[0].x);
    l = lin(vec3(mix(l, smoothstep(.35, .65, perceptual), .4))).x;
    vec2 d = (uv - .5) * 2.0; if (p[2].x > .5) d.x *= size.x / size.y;
    float v = clamp(1.0 - pow(clamp(length(d) / 1.25, 0.0, 1.0), 2.5), 0.0, 1.0);
    l *= mix(1.0, v, p[1].x); o = vec3(l);
    if (p[3].x > .5) o *= lin(vec3(.82, .84, .52));
#elif KIND == 7
    // Palette lookup: centres in Oklab, bands in linear luma.
    if (colorCount < 2.0) o = c;
    else {
        if (p[3].x > 0.0) c = read(aux, uv);
        float yy = luma(c); c += .2 * (yy * yy * (3.0 - 2.0 * yy) - yy);
        vec3 v = lab(clamp(c, 0.0, 1.0));
        if (p[4].x > .5) {
            float spread = 0.0;
            for (int i = 1; i < 16; i++) { if (float(i) >= colorCount) break; spread += distance(paletteLab[i - 1].xyz, paletteLab[i].xyz); }
            spread /= max(1.0, colorCount - 1.0);
            vec2 qTop = vec2(q.x, size.y - 1.0 - q.y);
            v.x += (bayer(qTop, p[4].x < 1.5 ? 4.0 : 8.0) - .5) * spread;
        }
        if (p[1].x > .5 || (p[2].x >= 1.0 && p[2].x <= 3.0)) {
            float l = luma(clamp(unlab(v), 0.0, 1.0)), best = 0.0;
            for (int i = 0; i < 15; i++) { if (float(i) + 1.0 >= colorCount) break; if (l >= bands[i]) best += 1.0; }
            if (p[1].x > .5) o = lin(vec3(best / max(1.0, colorCount - 1.0)));
            else { o = vec3(0.0); for (int i = 0; i < 16; i++) if (float(i) == best) o = unlab(paletteLab[i].xyz); }
        } else {
            float closest = 1e10; o = vec3(0.0);
            for (int i = 0; i < 16; i++) { if (float(i) >= colorCount) break; vec3 d = v - paletteLab[i].xyz; float e = dot(d, d); if (e < closest) { closest = e; o = unlab(paletteLab[i].xyz); } }
        }
    }
#elif KIND == 9
    // Crash zoom: the target stays put; eight samples along the zoom's motion.
    vec2 target = frameValues[0].xy, point = (uv - target) / frameValues[0].z + target;
    o = vec3(0.0);
    for (int i = 0; i < 8; i++) o += sampleImage(point + (point - target) * frameValues[0].w * (float(i) / 7.0 - .5)) / 8.0;
#elif KIND == 12
    o = sampleImage(moved(uv));
    if (p[2].x > .5) { o = mix(vec3(luma(o)), o, .95); o *= 1.0 - .12 * smoothstep(.25, .7, length(uv - .5)); o = grain(o, q / pixelScale, .3, 1.5, false); }
#elif KIND == 14
    o = sampleImage(moved(uv)) * frameValues[0].w;
#elif KIND == 11
    float ratio = p[0].x < .5 ? 2.39 : p[0].x < 1.5 ? 2.20 : p[0].x < 2.5 ? 1.85 : 1.33, aspect = size.x / size.y;
    bool bar = aspect < ratio ? abs(uv.y - .5) > .5 * aspect / ratio : abs(uv.x - .5) > .5 * ratio / aspect;
    vec3 flare = p[3].x < .5 ? vec3(.35, .6, 1.0) : p[3].x < 1.5 ? vec3(1.0, .6, .25) : vec3(1.0);
    o = c + read(aux, uv) * flare * p[2].x;
    if (bar) o = p[1].x > .5 ? read(background, uv) * .6 : vec3(0.0);
#elif KIND == 15
    vec3 glow = read(aux, uv), halo = read(background, uv);
    vec3 flash = p[3].x < .5 ? vec3(1.0) : p[3].x < 1.5 ? vec3(1.0, .8, .6) : vec3(.65, .8, 1.0);
    o = c + (glow * .65 + read(extra, uv) * .35) * p[0].x + halo.r * vec3(1.0, .25, .1) * p[1].x + flash * p[2].x * .08 * (1.0 - y);
#elif KIND == 16
    vec2 point = moved(uv);
    o = mix(sampleImage(point), read(aux, point), p[2].x);
    o = o / (1.0 + max(o - .85, 0.0) * .15);
    bool mono = p[5].x > .5; if (mono) o = vec3(luma(o));
    o = grain(o, q / pixelScale, p[0].x, p[1].x < .5 ? 1.0 : p[1].x < 1.5 ? 1.5 : 2.2, mono);
    if (p[4].x > .5) o *= gate(uv, .035);
#elif KIND == 17
    vec2 point = moved(uv), offset = (uv - .5) * pixelScale / size;
    o = vec3(read(aux, point + offset).r, read(aux, point).g, read(aux, point - offset).b);
    o = mix(o, vec3(.06) + o * .88, p[0].x); o *= vec3(1.06, 1.0, .92); o.r += o.g * .025 * p[0].x; o = mix(vec3(luma(o)), o, .8);
    o = grain(o, q / pixelScale, p[1].x, 2.2, false);
    float edge = frameValues[1].x > .5 ? uv.x : 1.0 - uv.x;
    o += vec3(1.0, .22, .035) * exp(-edge * 7.0) * frameValues[0].w * p[4].x;
    if (p[5].x > .5) o *= gate(uv, .055);
#endif
#if KIND == 22
    vec3 result = intensity >= 1.0 ? o : mix(original, o, intensity);
#else
    vec3 result = intensity >= 1.0 ? clamp(o, 0.0, 1.0) : mix(original, clamp(o, 0.0, 1.0), intensity);
#endif
    gl_FragColor = encode(result);
}
"""
    }
}
