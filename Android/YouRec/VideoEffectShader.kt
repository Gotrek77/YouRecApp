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
    private val textures = IntArray(2); private val buffers = IntArray(2)
    private var width = 0; private var height = 0
    private var lattice = 0
    private val programs = HashMap<Int, Program>()
    private val present: Int
    private val presentPosition: Int; private val presentCoordinate: Int; private val presentImage: Int
    private val full: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val unit: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val quad = FloatArray(8)
    private val quadBuffer: FloatBuffer = floats(*quad)

    private class Program(kind: Int) {
        val id = link(VERTEX, "#define KIND $kind\n" + FRAGMENT)
        val position = GLES20.glGetAttribLocation(id, "position")
        val image = GLES20.glGetUniformLocation(id, "image")
        val latticeTexture = GLES20.glGetUniformLocation(id, "lattice")
        val size = GLES20.glGetUniformLocation(id, "size")
        val p = GLES20.glGetUniformLocation(id, "p")
        val time = GLES20.glGetUniformLocation(id, "time")
        val intensity = GLES20.glGetUniformLocation(id, "intensity")
        val offset = GLES20.glGetUniformLocation(id, "latticeOffset")
    }

    init {
        present = link("attribute vec4 position; attribute vec2 coordinate; varying vec2 uv; void main() { gl_Position = position; uv = coordinate; }",
            "precision mediump float; uniform sampler2D image; varying vec2 uv; void main() { gl_FragColor = vec4(texture2D(image, uv).rgb, 1.0); }")
        presentPosition = GLES20.glGetAttribLocation(present, "position"); presentCoordinate = GLES20.glGetAttribLocation(present, "coordinate")
        presentImage = GLES20.glGetUniformLocation(present, "image")
    }

    /// The framebuffer to draw the layer's picture into, cleared, at the canvas size.
    fun begin(w: Int, h: Int): Int {
        if (w != width || h != height) {
            releaseTargets(); width = w; height = h
            GLES20.glGenTextures(2, textures, 0); GLES20.glGenFramebuffers(2, buffers, 0)
            for (i in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                parameters(GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[0])
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return buffers[0]
    }

    /// Apply `effects` in order to the picture drawn after `begin`. Returns the texture holding the result.
    fun apply(effects: List<VideoEffectSettings>, sourceTime: Double, fps: Double): Int {
        var current = 0
        GLES20.glDisable(GLES20.GL_BLEND)
        for (effect in effects) {
            val u = VideoEffectRendering.uniforms(effect, sourceTime, fps)
            val program = programs.getOrPut(u.kind) { Program(u.kind) }
            val target = 1 - current
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[target])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[current])
            GLES20.glUniform1i(program.image, 0)
            if (program.latticeTexture >= 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latticeTexture())
                GLES20.glUniform1i(program.latticeTexture, 1)
                val (x, y) = VideoEffectRendering.latticeOffset(u.frame)
                GLES20.glUniform2f(program.offset, x.toFloat(), y.toFloat())
            }
            GLES20.glUniform2f(program.size, width.toFloat(), height.toFloat())
            GLES20.glUniform4fv(program.p, PARAMETERS, u.p, 0)
            GLES20.glUniform1f(program.time, u.time)
            GLES20.glUniform1f(program.intensity, u.intensity)
            GLES20.glEnableVertexAttribArray(program.position)
            GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 0, full)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(program.position)
            current = target
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return textures[current]
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
        return lattice
    }
    private fun parameters(wrap: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
    }
    private fun releaseTargets() {
        if (width == 0) return
        GLES20.glDeleteFramebuffers(2, buffers, 0); GLES20.glDeleteTextures(2, textures, 0); width = 0; height = 0
    }
    fun release() {
        releaseTargets()
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
        val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D image;
uniform sampler2D lattice;
uniform vec2 size;
uniform vec4 p[$PARAMETERS];
uniform float time;
uniform float intensity;
uniform vec2 latticeOffset;
const float PI = 3.14159265358979;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
float luma(vec3 c) { return dot(c, LUMA); }
vec3 srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 lin(vec3 c) { return mix(c / 12.92, pow(max((c + 0.055) / 1.055, 0.0), vec3(2.4)), step(vec3(0.04045), c)); }
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
vec3 sampleImage(vec2 uv) { return lin(texture2D(image, vec2(uv.x, 1.0 - uv.y)).rgb); }
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
#endif
#if KIND == 22
    vec3 result = intensity >= 1.0 ? o : mix(original, o, intensity);
#else
    vec3 result = intensity >= 1.0 ? clamp(o, 0.0, 1.0) : mix(original, clamp(o, 0.0, 1.0), intensity);
#endif
    gl_FragColor = vec4(srgb(clamp(result, 0.0, 1.0)), 1.0);
}
"""
    }
}
