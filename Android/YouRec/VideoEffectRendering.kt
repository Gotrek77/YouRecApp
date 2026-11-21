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
        VideoEffectID.negativeStrobe, VideoEffectID.kaleidoscope)

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
    class Uniforms(val kind: Int, val frame: Int, val time: Float, val intensity: Float, val duration: Float, val fps: Float, val p: FloatArray)

    fun frameIndex(time: Double, fps: Double): Int = max(0, floor(max(0.0, time) * fps + 0.000001).toInt())
    fun linear(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)

    fun uniforms(settings: VideoEffectSettings, sourceTime: Double, fps: Double): Uniforms {
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
        return Uniforms(settings.kind.shaderIndex, frameIndex(sourceTime, fps), max(0.0, sourceTime - settings.start + (settings.phaseOffset ?: 0.0)).toFloat(),
            intensity, (settings.end - settings.start).toFloat(), fps.toFloat(), p)
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
}
