package yourec

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/// The iPhone app's native DSP for LavaSR and VoiceFixer (ThirdParty/LavaSR/
/// LavaDSP.cpp, ThirdParty/VoiceFixer/VoiceFixerDSP.cpp), built by the NDK.
interface StudioDSP {
    /// 192000 samples at 48 kHz → log-mel [80 × 375].
    fun lavaFeatures(audio: FloatArray, mel: FloatArray)
    /// Spectra [1025 × 375] → 192000 samples.
    fun lavaReconstruct(real: FloatArray, imaginary: FloatArray, audio: FloatArray)
    /// Keeps the original below 4 kHz, the restoration above 6 kHz.
    fun lavaMerge(original: FloatArray, restored: FloatArray, output: FloatArray)
    /// 112455 samples at 44.1 kHz → mel [256 × 128], with upstream's bank [1025 × 128].
    fun voiceFixerFeatures(audio: FloatArray, bank: FloatArray, mel: FloatArray)
}
/// LavaSR v2's spectral network: mel [1 × 80 × 375] → real and imaginary [1 × 1025 × 375].
interface LavaNetwork : AutoCloseable { fun run(mel: FloatArray, real: FloatArray, imaginary: FloatArray) }
/// VoiceFixer's analysis network and vocoder: mel [1 × 1 × 256 × 128] → 114660 samples at 44.1 kHz.
interface VoiceFixerNetwork : AutoCloseable { fun run(mel: FloatArray, audio: FloatArray) }

/// A random-access source that reads `channels`-interleaved float, any position.
private fun PCMSource.block(start: Long, frames: Int): FloatArray = FloatArray(frames * AudioMix.channels).also { read(start, it, 0, frames) }

/// LavaSR speech detail, after the iPhone app's StudioVoiceProcessor: four-second
/// windows with 32 STFT hops of context each side, reflected at the edges of
/// the recording, cropped to the centre; the merge keeps the original below 4 kHz.
object StudioVoice {
    const val context = 192000
    const val margin = 16384
    const val stride = context - 2 * margin

    fun process(input: PCMSource, length: Long, amount: Float, dsp: StudioDSP, network: LavaNetwork,
                output: (FloatArray, Int) -> Unit, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val channels = AudioMix.channels
        val window = FloatArray(context); val reconstructed = FloatArray(context); val merged = FloatArray(context)
        val mel = FloatArray(80 * 375); val real = FloatArray(1025 * 375); val imaginary = FloatArray(1025 * 375)
        val result = FloatArray(stride * channels)
        var position = 0L
        while (position < length) {
            if (cancelled()) throw AudioProcessingError.Cancelled()
            val count = minOf(stride.toLong(), length - position).toInt()
            val start = position - margin
            val readStart = maxOf(0L, start); val readEnd = minOf(length, start + context)
            val read = (readEnd - readStart).toInt().coerceAtLeast(0)
            val samples = input.block(readStart, read)
            for (channel in 0 until channels) {
                // Dual mono reuses the first channel's result: same samples, same output.
                if (channel > 0 && (0 until read).all { samples[it * channels] == samples[it * channels + channel] }) {
                    for (i in 0 until count) result[i * channels + channel] = result[i * channels]
                    continue
                }
                for (index in 0 until context) {
                    var global = start + index
                    if (length > 1) {
                        val period = 2 * (length - 1)
                        global = ((global % period) + period) % period
                        if (global >= length) global = period - global
                    } else global = 0
                    val local = (global - readStart).toInt()
                    window[index] = if (local in 0 until read) samples[local * channels + channel] else 0f
                }
                if (window.all { abs(it) < 1e-8f }) { for (i in 0 until count) result[i * channels + channel] = 0f; continue }
                dsp.lavaFeatures(window, mel)
                network.run(mel, real, imaginary)
                if (cancelled()) throw AudioProcessingError.Cancelled()
                dsp.lavaReconstruct(real, imaginary, reconstructed)
                dsp.lavaMerge(window, reconstructed, merged)
                for (frame in 0 until count) {
                    val index = margin + frame
                    val value = window[index] + amount * (merged[index] - window[index])
                    if (!value.isFinite()) throw AudioProcessingError.InvalidFormat()
                    result[frame * channels + channel] = value
                }
            }
            output(result, count)
            position += count
            progress(position.toDouble() / maxOf(1L, length))
        }
    }
}

/// VoiceFixer studio restoration, after the iPhone app's StudioRestorationProcessor:
/// the recording at 44.1 kHz, 255-frame windows with 64 frames of context each
/// side, 120-frame steps crossfaded over the rest, back to 48 kHz, then blended.
object StudioRestoration {
    const val hop = 441
    const val context = 255 * hop
    const val margin = 64 * hop
    const val stride = 120 * hop
    const val overlap = context - 2 * margin - stride

    /// Indices into the recording, reflected at both ends.
    fun sourceIndices(position: Long, length: Long): LongArray = LongArray(context) { i ->
        if (length <= 1) 0L else {
            val period = 2 * (length - 1)
            val j = ((position - margin + i) % period + period) % period
            if (j < length) j else period - j
        }
    }

    /// Restore 44.1 kHz `input` of `length` frames, writing as many 44.1 kHz frames.
    fun restore(input: PCMSource, length: Long, dsp: StudioDSP, bank: FloatArray, network: VoiceFixerNetwork,
                output: (FloatArray, Int) -> Unit, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val channels = AudioMix.channels
        val window = FloatArray(context); val mel = FloatArray(256 * 128); val audio = FloatArray(260 * hop)
        val raw = Array(channels) { FloatArray(stride + overlap) }
        val tail = Array(channels) { FloatArray(overlap) }
        val result = FloatArray(stride * channels)
        var position = 0L
        while (position < length) {
            if (cancelled()) throw AudioProcessingError.Cancelled()
            val count = minOf(stride.toLong(), length - position).toInt()
            val valid = minOf((stride + overlap).toLong(), length - position).toInt()
            val indices = sourceIndices(position, length)
            val readStart = indices.min(); val readEnd = indices.max() + 1
            val samples = input.block(readStart, (readEnd - readStart).toInt())
            val read = (readEnd - readStart).toInt()
            for (channel in 0 until channels) {
                if (channel > 0 && (0 until read).all { samples[it * channels] == samples[it * channels + channel] }) {
                    System.arraycopy(raw[0], 0, raw[channel], 0, valid); continue
                }
                for (i in 0 until context) window[i] = samples[((indices[i] - readStart) * channels).toInt() + channel]
                if (window.all { abs(it) < 1e-8f }) { java.util.Arrays.fill(raw[channel], 0, valid, 0f); continue }
                dsp.voiceFixerFeatures(window, bank, mel)
                network.run(mel, audio)
                if (cancelled()) throw AudioProcessingError.Cancelled()
                // Upstream centres the vocoder padding around the input.
                val trim = (audio.size - context) / 2
                for (i in 0 until valid) {
                    val value = audio[margin + trim + i]
                    if (!value.isFinite()) throw AudioProcessingError.InvalidFormat()
                    raw[channel][i] = value
                }
            }
            for (channel in 0 until channels) {
                for (i in 0 until count) {
                    val weight = if (position > 0 && i < overlap) (i + 1).toFloat() / (overlap + 1) else 1f
                    result[i * channels + channel] = weight * raw[channel][i] + (1 - weight) * (if (i < overlap) tail[channel][i] else 0f)
                }
                for (i in 0 until overlap) tail[channel][i] = if (count + i < valid) raw[channel][count + i] else 0f
            }
            output(result, count)
            position += count
            progress(position.toDouble() / maxOf(1L, length))
        }
    }
}

/// Band-limited sample-rate conversion of interleaved stereo: a Kaiser-windowed
/// sinc evaluated at each output instant, cut off below the lower Nyquist.
class Resampler(private val from: Int, private val to: Int, private val zeroCrossings: Int = 32) {
    private val cutoff = 0.5 * minOf(from, to) / from * 0.97   // in input cycles per sample
    private val radius = ceil(zeroCrossings / (2 * cutoff)).toInt()
    private val beta = 9.0
    private fun bessel(x: Double): Double { var sum = 1.0; var term = 1.0; var k = 1; while (k < 50) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }; return sum }
    private val norm = bessel(beta)
    private fun kernel(t: Double): Double {
        if (abs(t) >= radius) return 0.0
        val x = 2 * cutoff * t
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
        val r = t / radius
        return 2 * cutoff * sinc * bessel(beta * sqrt(1 - r * r)) / norm
    }
    /// Output frames for `length` input frames.
    fun outputLength(length: Long) = Math.ceil(length.toDouble() * to / from - 1e-9).toLong()

    // The ratio is rational: each output instant falls on one of `phases`
    // fractional positions, so the filter is tabulated once per phase.
    private val divisor = gcd(from, to)
    private val step = from / divisor            // input frames per `phases` output frames
    private val phases = to / divisor
    private val taps = 2 * radius + 1
    private val table = Array(phases) { phase -> FloatArray(taps) { k -> kernel(phase.toDouble() / phases - (k - radius)).toFloat() } }
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun convert(input: PCMSource, length: Long, output: (FloatArray, Int) -> Unit, cancelled: () -> Boolean = { false }) {
        val channels = AudioMix.channels
        val total = outputLength(length)
        val block = 4096
        val out = FloatArray(block * channels)
        var n = 0L
        while (n < total) {
            if (cancelled()) throw AudioProcessingError.Cancelled()
            val count = minOf(block.toLong(), total - n).toInt()
            val first = Math.floorDiv(n * step, phases.toLong()) - radius
            val last = Math.floorDiv((n + count) * step, phases.toLong()) + radius + 1
            val span = (last - first + 1).toInt()
            val samples = FloatArray(span * channels)
            input.read(first, samples, 0, span)
            for (i in 0 until span) { val p = first + i; if (p < 0 || p >= length) { samples[i * channels] = 0f; samples[i * channels + 1] = 0f } }
            for (i in 0 until count) {
                val position = (n + i) * step
                val q = Math.floorDiv(position, phases.toLong()); val phase = (position - q * phases).toInt()
                val weights = table[phase]
                var left = 0.0; var right = 0.0
                var o = ((q - radius - first) * channels).toInt()
                for (k in 0 until taps) { val w = weights[k]; left += w * samples[o]; right += w * samples[o + 1]; o += channels }
                out[i * channels] = left.toFloat(); out[i * channels + 1] = right.toFloat()
            }
            output(out, count)
            n += count
        }
    }
}
