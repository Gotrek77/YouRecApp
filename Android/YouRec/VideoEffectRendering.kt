package yourec

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/// Which video effects this build draws in preview and export, and what each
/// draws with at one source time: the iPhone app's MetalEffectRenderer.render
/// selection and its uniform layout (MetalVideoEffects.swift). A project with
/// any other known effect is refused by its name (ProjectCoding), never opened
/// and drawn without it.
object VideoEffectRendering {
    /// Effects drawn by a single pass over the picture (VideoEffectShader).
    val drawn: Set<VideoEffectID> = setOf(
        VideoEffectID.colorCorrection, VideoEffectID.silentTint, VideoEffectID.threeStrip, VideoEffectID.bleachBypass,
        VideoEffectID.dayForNight, VideoEffectID.noir, VideoEffectID.expressionism, VideoEffectID.rgbOffset,
        VideoEffectID.negativeStrobe, VideoEffectID.kaleidoscope,
        VideoEffectID.halation, VideoEffectID.widescreen, VideoEffectID.sixteenMM, VideoEffectID.superEight,
        VideoEffectID.undercranking, VideoEffectID.stepPrinting, VideoEffectID.freezeFrame)

    /// The iPhone app's pixel order: families in this order, each in the order
    /// VideoEffectSettings.ordered gives.
    private val stages = listOf(EffectFamily.Key, EffectFamily.Camera, EffectFamily.Time, EffectFamily.Color, EffectFamily.Quantization, EffectFamily.Texture, EffectFamily.Frame)

    /// The effects that change the picture of `clip` at `sourceTime`, in the order they apply.
    /// `fps` is the frame rate of the preview or export (Negative Strobe caps its rate at half of it).
    fun active(clip: TimelineClip, sourceTime: Double, fps: Double): List<VideoEffectSettings> {
        val effects = clip.videoEffects ?: return emptyList()
        if (effects.isEmpty()) return emptyList()
        val windows = clip.effectWindows
        val enabled = VideoEffectSettings.ordered(effects).filter { effect ->
            if (!effect.enabled || sourceTime < effect.start || sourceTime >= effect.end) return@filter false
            // A ranged effect shows only inside its windows (ScopedEffect.resolvingEffects).
            if (windows?.get(effect.id)?.none { sourceTime >= it.start && sourceTime < it.end } == true) return@filter false
            when (effect.kind) {
                // Step printing chooses source frames (VideoEffectSamplePlan); it has no pixel pass.
                VideoEffectID.stepPrinting, VideoEffectID.nightFix -> false
                VideoEffectID.negativeStrobe -> effect.strobeShowsNegative(sourceTime, fps)
                VideoEffectID.rgbOffset -> RGBChannel.values().any { effect.number(it.x, sourceTime) != 0.0 || effect.number(it.y, sourceTime) != 0.0 }
                else -> true
            }
        }.filter { !it.kind.descriptor.supportsIntensity || it.number("intensity", sourceTime) > 0 }
        return stages.flatMap { stage -> enabled.filter { it.kind.descriptor.family == stage } }
    }

    /// What one effect pass reads, in the iPhone app's EffectUniforms layout:
    /// `p` holds 40 four-component values, parameter i (catalogue order, without
    /// intensity) in p[i]; a number, a toggle (0/1) or a choice (its index) in
    /// x, a colour as linear RGB with 1 in w.
    class Uniforms(val kind: Int, val frame: Int, val time: Float, val intensity: Float, val duration: Float, val fps: Float, val p: FloatArray,
                   val released: Boolean = false, val targetX: Float? = null, val targetY: Float? = null)

    fun frameIndex(time: Double, fps: Double): Int = max(0, floor(max(0.0, time) * fps + 0.000001).toInt())
    fun linear(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)

    /// `frameTime` numbers the frame (grain and weave advance with it); a
    /// freeze's own time is `holdTime`, how far into the hold this frame is.
    fun uniforms(settings: VideoEffectSettings, sourceTime: Double, fps: Double, frameTime: Double = sourceTime, holdTime: Double? = null): Uniforms {
        val p = FloatArray(40 * 4)
        for ((index, definition) in settings.kind.descriptor.parameters.withIndex()) {
            when (val value = settings.value(definition.id, sourceTime) ?: definition.value) {
                is EffectValue.Number -> p[index * 4] = value.value.toFloat()
                is EffectValue.Toggle -> p[index * 4] = if (value.value) 1f else 0f
                is EffectValue.Choice -> (definition.kind as? EffectParameterKind.Picker)?.let { p[index * 4] = max(0, it.options.indexOf(value.value)).toFloat() }
                is EffectValue.Color -> {
                    p[index * 4] = linear(value.value.red).toFloat(); p[index * 4 + 1] = linear(value.value.green).toFloat()
                    p[index * 4 + 2] = linear(value.value.blue).toFloat(); p[index * 4 + 3] = 1f
                }
                is EffectValue.Media -> {}
            }
        }
        settings.releaseTime?.let { p[39 * 4] = max(0.0, it - settings.start + (settings.phaseOffset ?: 0.0)).toFloat() }
        val intensity = if (settings.kind.descriptor.supportsIntensity) settings.number("intensity", sourceTime).toFloat() else 1f
        val time = if (settings.kind == VideoEffectID.freezeFrame && holdTime != null) holdTime else max(0.0, sourceTime - settings.start + (settings.phaseOffset ?: 0.0))
        return Uniforms(settings.kind.shaderIndex, frameIndex(frameTime, fps), time.toFloat(),
            intensity, (settings.end - settings.start).toFloat(), fps.toFloat(), p, settings.releaseTime != null)
    }

    /// The film grain lattice: random values in -1...1, colour in RGB (a
    /// mostly shared value with some per-channel variation) and monochrome in
    /// A, as the iPhone app's ve_grainNoise. A fixed tile; each frame reads it
    /// from a different place, so the grain moves as the iPhone app's does.
    const val latticeSize = 1024
    fun lattice(): ByteArray {
        val random = java.util.Random(0x59_6f_75_52)
        val bytes = ByteArray(latticeSize * latticeSize * 4)
        fun byte(v: Double) = Math.round((v.coerceIn(-1.0, 1.0) + 1) * 127.5).toInt().toByte()
        for (i in 0 until latticeSize * latticeSize) {
            val base = random.nextDouble() * 2 - 1
            for (c in 0 until 3) bytes[i * 4 + c] = byte(base * 0.7 + (random.nextDouble() * 2 - 1) * 0.3)
            bytes[i * 4 + 3] = byte(base)
        }
        return bytes
    }
    /// Where frame `frame` reads the lattice, in whole cells.
    fun latticeOffset(frame: Int): Pair<Int, Int> {
        var n = frame * -0x61c88647 + 0x3c6ef372
        n = n xor (n ushr 16); n *= 0x7feb352d; n = n xor (n ushr 15); n *= -0x7b3597b5; n = n xor (n ushr 16)
        return Pair(n and (latticeSize - 1), (n ushr 10) and (latticeSize - 1))
    }

    /// The iPhone app's shader hashes (VideoEffects.metal hash32, random01,
    /// noise) on 32-bit unsigned arithmetic. Values that are the same for a
    /// whole frame are computed here and handed to the shader.
    fun hash32(value: Int): Int {
        var n = value
        n = n xor (n ushr 16); n *= 0x7feb352d; n = n xor (n ushr 15); n *= 0x846ca68b.toInt(); n = n xor (n ushr 16)
        return n
    }
    fun random01(x: Int, y: Int, z: Int): Double =
        (hash32(x xor hash32(y + 0x9e3779b9.toInt()) xor hash32(z + 0x85ebca6b.toInt())) ushr 8).toDouble() / 16777216.0
    fun noise(px: Double, py: Double, frame: Int, seed: Int): Double {
        val cx = floor(px).toInt(); val cy = floor(py).toInt()
        var fx = px - floor(px); var fy = py - floor(py)
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
        val z = frame + seed
        val a = random01(cx, cy, z); val b = random01(cx + 1, cy, z); val c = random01(cx, cy + 1, z); val d = random01(cx + 1, cy + 1, z)
        val top = a + (b - a) * fx; val bottom = c + (d - c) * fx
        return (top + (bottom - top) * fy) * 2 - 1
    }

    /// Per-frame values of one pass (eight floats, two vec4 in the shader):
    /// camera movement in 1080p pixels (x, y), picture scale, and a per-effect
    /// value; then a second per-effect group. See VideoEffectShader for their use.
    fun frameValues(u: Uniforms): FloatArray {
        val v = FloatArray(8); v[2] = 1f
        fun p(i: Int) = u.p[i * 4].toDouble()
        when (u.kind) {
            14 -> { // Undercranking: a new weave position and exposure per cranked frame.
                val frame = floor(u.time * p(0)).toInt()
                v[0] = (noise(frame * .18, 3.0, 0, 7) * 2 * p(3)).toFloat(); v[1] = (noise(frame * .18, 8.0, 0, 11) * 3 * p(3)).toFloat(); v[2] = 1.01f
                v[3] = (1 + (random01(4, 7, frame) * 2 - 1) * .08 * p(2)).toFloat()
            }
            16 -> {
                v[0] = (noise(u.frame * .13, 2.0, 0, 7) * p(3) * 2).toFloat(); v[1] = (noise(u.frame * .13, 9.0, 0, 3) * p(3) * 2).toFloat(); v[2] = 1.006f
            }
            12 -> v[2] = (1 + p(1) * (u.time / maxOf(.001, p(0))).coerceIn(0.0, 1.0)).toFloat()  // Freeze frame: push-in over the hold.
            9 -> { // Crash zoom: the snap, a small overshoot, an optional return; blur follows the zoom's speed.
                val t = u.time.toDouble(); val speed = maxOf(.001, p(1)); val amount = p(0)
                val release = if (u.released) u.p[39 * 4].toDouble() else speed + .08 + p(2)
                var z: Double; var velocity = 0.0
                if (t < speed) { val f = (t / speed).coerceIn(0.0, 1.0); z = 1 + (amount - 1) * Math.pow(f, 5.0); velocity = 5 * Math.pow(f, 4.0) / speed }
                else if (t < speed + .08) { val f = (t - speed) / .08; z = amount * (1 + .03 * Math.sin(f * Math.PI)); velocity = .03 * Math.PI / .08 * Math.cos(f * Math.PI) }
                else if ((p(3) > .5 || u.released) && t > release) { val f = ((t - release) / speed).coerceIn(0.0, 1.0); z = amount + (1 - amount) * f * f * (3 - 2 * f); velocity = -6 * f * (1 - f) / speed }
                else z = amount
                v[0] = u.targetX ?: p(5).toFloat(); v[1] = u.targetY ?: p(6).toFloat(); v[2] = z.toFloat(); v[3] = minOf(.12, Math.abs(velocity) * .002 * p(4)).toFloat()
            }
            17 -> { // Super 8: weave, and a light leak from one side once in a while.
                v[0] = (noise(u.frame * .16, 2.0, 0, 5) * 2 * p(2)).toFloat(); v[1] = (noise(u.frame * .16, 9.0, 0, 17) * 3 * p(2)).toFloat(); v[2] = 1.012f
                val cycle = floor(u.time / 6.0); val phase = u.time - cycle * 6
                val begin = random01(cycle.toInt(), 12, 3) * 4.5; val length = .5 + random01(cycle.toInt(), 4, 6)
                v[3] = (if (phase >= begin && phase < begin + length) Math.sin((phase - begin) / length * Math.PI) else 0.0).toFloat()
                v[4] = if (random01(cycle.toInt(), 7, 11) > .5) 1f else 0f
            }
        }
        return v
    }

    /// MetalEffectRenderer.gaussian's normalised weights: [centre, pair count,
    /// then 24 pairs of (offset in pixels, weight)], adjacent taps paired so
    /// that one bilinear read takes both.
    fun gaussianWeights(sigmaIn: Float): FloatArray {
        val sigma = maxOf(0.25f, sigmaIn)
        val radius = minOf(48, Math.ceil(sigma * 3.0).toInt())
        val raw = FloatArray(radius + 1) { Math.exp(-(it * it).toDouble() / (2.0 * sigma * sigma)).toFloat() }
        val total = raw[0] + 2 * raw.drop(1).sum()
        val values = FloatArray(2 + 48)
        values[0] = raw[0] / total; values[1] = ((radius + 1) / 2).toFloat()
        var i = 1
        while (i <= radius) {
            val a = raw[i]; val b = if (i + 1 <= radius) raw[i + 1] else 0f
            val pair = (i + 1) / 2 - 1
            values[2 + pair * 2] = i + b / (a + b); values[3 + pair * 2] = (a + b) / total
            i += 2
        }
        return values
    }
}
