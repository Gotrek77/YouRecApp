package yourec

import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/// One stage of Enhance audio, by the iPhone app's name. A kind written by a
/// newer app keeps its name: it is kept in the project and refused when the
/// audio is processed, never silently dropped or replaced.
data class EffectKind(val raw: String) {
    companion object {
        val voiceIsolation = EffectKind("voiceIsolation")
        val studioVoice = EffectKind("studioVoice")
        val studioRestoration = EffectKind("studioRestoration")
        val highPass = EffectKind("highPass")
        val equalizer = EffectKind("equalizer")
        val gain = EffectKind("gain")
        val allCases = listOf(voiceIsolation, studioVoice, studioRestoration, highPass, equalizer, gain)
    }
    val isSupported: Boolean get() = this in allCases
    val isNeural: Boolean get() = this == voiceIsolation || this == studioVoice || this == studioRestoration
    val title: String get() = when (this) {
        voiceIsolation -> "Noise reduction"
        studioVoice -> "Speech detail restoration"
        studioRestoration -> "Studio restoration"
        highPass -> "Low-cut filter"
        equalizer -> "Voice EQ"
        gain -> "Output gain"
        else -> "Unavailable filter"
    }
    val stripName: String get() = when (this) {
        voiceIsolation -> "Denoise"; studioVoice -> "Detail"; studioRestoration -> "Studio"
        highPass -> "Low-cut"; equalizer -> "EQ"; gain -> "Gain"; else -> "Unknown"
    }
}

/// The exact model a neural stage was made with. Weights and runtime are part
/// of the identity: a project made with one model never plays through another.
data class AudioModel(val id: String, val weightsSHA256: String, val runtime: String) {
    companion object {
        val rnnoise = AudioModel("rnnoise-v0.1-cdf196b1", "f0cdb52b30501aab489f90fedbc7a023c719d91b337db2da7f26fc3036556b95", "yourec-rnnoise-c-v1")
        val lava = AudioModel("lavasr-v2-33ac0408", "1ef7557e72c9b2ee78ccb35cf3ab3400c0c73be1838ab16ff7dde52934f4d2cf", "yourec-coreml-fp32-lava-dsp-v1")
        val voiceFixer = AudioModel("voicefixer-aae2253c", "67971970d18c06c1220dc63df11aa0186f787dc961c829dcd1624dad3d612360",
            "yourec-voicefixer-coreml-44100-context256-overlap-v2")
        val deepFilter = AudioModel("deepfilternet3-dfc12319", "1114ff972e25679e7f9707da85eee62659e1bdf50199035ecacba12e4ec0425d",
            "yourec-dfn3-coreml-cpu-v1-dsp3dd66dea-milc2141d48")
    }
}

/// One filter of a chain. Amount is 0–1 for neural stages and decibels for
/// EQ and gain; frequency is the low-cut cutoff or the EQ band.
data class AudioEffect(val id: UUID = UUID.randomUUID(), val kind: EffectKind, val enabled: Boolean = true, val amount: Double = 0.0,
                       val frequency: Double = 1800.0, val implementationVersion: Int = 1, val model: AudioModel? = null) {
    /// What the iPhone app can process.
    val supported: Boolean get() {
        if (!kind.isSupported || implementationVersion != 1) return false
        if (kind == EffectKind.voiceIsolation) return amount == 0.0 || model == AudioModel.rnnoise || model == AudioModel.deepFilter
        if (kind == EffectKind.studioVoice) return amount == 0.0 || model == AudioModel.lava
        if (kind == EffectKind.studioRestoration) return amount == 0.0 || model == AudioModel.voiceFixer
        return true
    }
    /// What this build can process. RNNoise and DeepFilterNet3 run here; LavaSR
    /// and VoiceFixer are Core ML models of the iPhone app, not ported yet.
    val available: Boolean get() = supported && (!kind.isNeural || amount == 0.0 || kind == EffectKind.voiceIsolation)
    /// Stages that can make the sound louder than it was.
    val canRaisePeak: Boolean get() = enabled && when (kind) {
        EffectKind.gain -> amount > 0
        EffectKind.equalizer, EffectKind.voiceIsolation, EffectKind.studioVoice, EffectKind.studioRestoration -> amount != 0.0
        else -> true
    }
    /// The position of the main control, 0–1.
    val normalizedPrimary: Double get() = when {
        kind.isNeural -> amount.coerceIn(0.0, 1.0)
        kind == EffectKind.highPass -> (ln(maxOf(20.0, frequency) / 20) / ln(25.0)).coerceIn(0.0, 1.0)
        else -> ((amount + 24) / 48).coerceIn(0.0, 1.0)
    }
    fun settingPrimary(normalized: Double): AudioEffect {
        val n = normalized.coerceIn(0.0, 1.0)
        return when {
            kind.isNeural -> copy(amount = Math.rint(n * 100) / 100)
            kind == EffectKind.highPass -> copy(frequency = Math.rint(20 * 25.0.pow(n)))
            else -> copy(amount = Math.rint((n * 48 - 24) * 10) / 10)
        }
    }
    val primaryLabel: String get() = if (kind.isNeural) "Amount" else if (kind == EffectKind.highPass) "Cutoff" else "Gain"
    val frequencyText: String get() = if (frequency >= 1000) String.format(java.util.Locale.US, "%.1f kHz", frequency / 1000) else String.format(java.util.Locale.US, "%.0f Hz", frequency)
    val primaryText: String get() = when {
        kind.isNeural -> String.format(java.util.Locale.US, "%.0f%%", amount * 100)
        kind == EffectKind.highPass -> frequencyText
        else -> String.format(java.util.Locale.US, "%+.1f dB", amount)
    }
    companion object {
        fun make(kind: EffectKind) = AudioEffect(kind = kind, amount = if (kind == EffectKind.studioVoice) 0.35 else if (kind.isNeural) 1.0 else 0.0,
            frequency = if (kind == EffectKind.highPass) 80.0 else 1800.0,
            model = when (kind) { EffectKind.voiceIsolation -> AudioModel.rnnoise; EffectKind.studioVoice -> AudioModel.lava
                EffectKind.studioRestoration -> AudioModel.voiceFixer; else -> null })
        /// The EQ band control: 80 Hz to 12 kHz.
        fun equalizerPosition(frequency: Double) = ln(maxOf(80.0, frequency) / 80) / ln(150.0)
        fun equalizerFrequency(position: Double) = Math.rint(80 * 150.0.pow(position.coerceIn(0.0, 1.0)))
    }
}

/// A filter chain over part of one audio item, in the item's own time.
data class AudioEnhancementRegion(val id: UUID = UUID.randomUUID(), val interval: EditInterval, val effects: List<AudioEffect>)

/// Biquad stages retain their own channel state across blocks. Audio is
/// processed at 48 kHz. Bypass is exact; no model inference is implied by EQ.
class AudioChainProcessor(effects: List<AudioEffect>, sampleRate: Double = 48000.0, channels: Int = 2) {
    class Stage(effect: AudioEffect, sampleRate: Double, channels: Int) {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)
        init {
            val frequency = minOf(sampleRate * 0.45, maxOf(20.0, effect.frequency))
            val w = 2 * PI * frequency / sampleRate
            val c = cos(w); val s = sin(w)
            when (effect.kind) {
                EffectKind.gain -> b0 = 10.0.pow(effect.amount / 20)
                EffectKind.highPass -> {
                    val alpha = s / (2 * sqrt(0.5)); val a0 = 1 + alpha
                    b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0
                    a1 = -2 * c / a0; a2 = (1 - alpha) / a0
                }
                EffectKind.equalizer -> {
                    val amplitude = 10.0.pow(effect.amount / 40); val alpha = s / 2
                    val a0 = 1 + alpha / amplitude
                    b0 = (1 + alpha * amplitude) / a0; b1 = -2 * c / a0
                    b2 = (1 - alpha * amplitude) / a0
                    a1 = -2 * c / a0; a2 = (1 - alpha / amplitude) / a0
                }
                else -> throw IllegalArgumentException("Unsupported direct DSP stage")
            }
        }
        fun sample(input: Double, channel: Int): Double {
            val result = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * result + z2[channel]
            z2[channel] = b2 * input - a2 * result
            return result
        }
    }
    private val stages = effects.filter { it.enabled }.map { Stage(it, sampleRate, channels) }
    var peak = 0f
        private set
    fun sample(value: Float, channel: Int): Float {
        var output = value.toDouble()
        for (stage in stages) output = stage.sample(output, channel)
        val result = if (output.isFinite()) output.toFloat() else 0f
        peak = maxOf(peak, abs(result))
        return result
    }
}
