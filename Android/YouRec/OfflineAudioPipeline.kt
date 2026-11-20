package yourec

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

sealed class AudioProcessingError(message: String) : Exception(message) {
    class UnavailableModel : AudioProcessingError("This project's audio model is unavailable. Bypass that stage to use the original audio.")
    class InvalidFormat : AudioProcessingError("The audio could not be processed at its expected sample rate.")
    class Cancelled : AudioProcessingError("Audio processing was cancelled.")
}

/// RNNoise on one channel: 480 samples (10 ms at 48 kHz) in, 480 out, the
/// output one frame late. One denoiser per channel.
interface FrameDenoiser : AutoCloseable {
    fun process(input: FloatArray, output: FloatArray)
}

/// The sound of one source through one chain, processed once over the whole
/// recording, in source time. Preview and export then read the same samples
/// wherever playback starts, and the chain comes before any speed change.
object OfflineAudioPipeline {
    const val frame = 480
    private const val channels = AudioMix.channels
    // Bump when processing math changes: cached results of older math are not reused.
    const val cacheVersion = "android-audio-v2|rnnoise-adapter-1|eq-1|dfn3-onnx-ort1.31.1-chunk1500-warm300|lava-onnx-ort1.31.1|voicefixer-onnx-ort1.31.1-kaiser32"
    /// Processed sound is a replaceable cache, outside every project. Set at launch.
    @Volatile var cacheFolder = File(System.getProperty("java.io.tmpdir") ?: ".", "Enhanced audio")

    /// Stages that change the sound. A neural stage at zero amount is a bypass.
    fun active(effects: List<AudioEffect>) = effects.filter { it.enabled && !(it.kind.isNeural && it.amount == 0.0) }

    /// The cache name of one source through one chain.
    fun key(sourceIdentity: String, effects: List<AudioEffect>): String {
        val text = cacheVersion + "|" + sourceIdentity + "|" + String(JSON.write(active(effects).map { ProjectCoding.encode(it) }), Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { String.format("%02x", it) }
    }

    private interface Stage {
        val latency: Int
        /// In place: one block of `frame` interleaved stereo frames.
        fun process(block: FloatArray)
    }
    private class Filter(effect: AudioEffect) : Stage {
        private val processor = AudioChainProcessor(listOf(effect), channels = channels)
        override val latency = 0
        override fun process(block: FloatArray) {
            for (i in 0 until frame) for (c in 0 until channels) block[i * channels + c] = processor.sample(block[i * channels + c], c)
        }
    }
    /// DeepFilterNet3 per channel. Its output comes `latency` hops late; the dry
    /// sound is held back as long, so Amount blends the same moments.
    private class DeepFilter(private val amount: Float, private val streams: List<DeepFilterStream>) : Stage {
        private val delay = streams.first().latency
        override val latency = delay * frame
        private val dry = Array(channels) { FloatArray((delay + 1) * frame) }
        private val hop = FloatArray(frame); private val wet = FloatArray(frame)
        private var count = 0L
        override fun process(block: FloatArray) {
            val slot = ((count % (delay + 1)) * frame).toInt()
            val past = (((count - delay).mod(delay + 1L)) * frame).toInt()
            for (c in 0 until channels) {
                for (i in 0 until frame) { hop[i] = block[i * channels + c]; dry[c][slot + i] = hop[i] }
                streams[c].process(hop, wet)
                for (i in 0 until frame) {
                    val original = dry[c][past + i]
                    block[i * channels + c] = if (amount == 1f) wet[i] else original + amount * (wet[i] - original)
                }
            }
            count++
        }
    }
    /// Wet from model frame n is the cleaned dry frame n−1; Amount blends them.
    private class Denoise(private val amount: Float, private val denoisers: List<FrameDenoiser>) : Stage {
        override val latency = frame
        private val previous = Array(channels) { FloatArray(frame) }
        private val input = FloatArray(frame); private val wet = FloatArray(frame)
        override fun process(block: FloatArray) {
            for (c in 0 until channels) {
                for (i in 0 until frame) input[i] = block[i * channels + c]
                denoisers[c].process(input, wet)
                val dry = previous[c]
                for (i in 0 until frame) block[i * channels + c] = dry[i] + amount * (wet[i] - dry[i])
                System.arraycopy(input, 0, dry, 0, frame)
            }
        }
    }

    /// Process `length` frames of `input` through `effects` in order. Every
    /// stage sees exactly what the iPhone app's file-by-file stages see: the
    /// recording, then silence after its end; the result has the same length.
    /// `output` receives interleaved stereo blocks. Returns the peak.
    fun process(input: PCMSource, length: Long, effects: List<AudioEffect>, denoiser: () -> FrameDenoiser,
                output: (FloatArray, Int) -> Unit, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false },
                deepFilter: (() -> DeepFilterStream)? = null): Float {
        if (!effects.filter { it.enabled }.all { it.available }) throw AudioProcessingError.UnavailableModel()
        val chain = active(effects)
        if (chain.any { windowed(it) }) throw AudioProcessingError.UnavailableModel()
        return stream(input, length, chain, denoiser, output, progress, cancelled, deepFilter)
    }
    /// LavaSR and VoiceFixer look seconds ahead and behind, reflected at the
    /// ends of the recording: they read their whole input, as on iPhone.
    fun windowed(effect: AudioEffect) = effect.kind == EffectKind.studioVoice || effect.kind == EffectKind.studioRestoration
    private fun stream(input: PCMSource, length: Long, chain: List<AudioEffect>, denoiser: () -> FrameDenoiser,
                       output: (FloatArray, Int) -> Unit, progress: (Double) -> Unit, cancelled: () -> Boolean,
                       deepFilter: (() -> DeepFilterStream)?): Float {
        val opened = ArrayList<AutoCloseable>()
        try {
            val stages = chain.map { effect ->
                if (effect.kind == EffectKind.voiceIsolation && effect.model == AudioModel.deepFilter) {
                    val make = deepFilter ?: throw AudioProcessingError.UnavailableModel()
                    DeepFilter(effect.amount.toFloat(), List(channels) { make().also { opened.add(it) } })
                } else if (effect.kind == EffectKind.voiceIsolation) Denoise(effect.amount.toFloat(), List(channels) { denoiser().also { opened.add(it) } })
                else Filter(effect)
            }
            val block = FloatArray(frame * channels)
            var peak = 0f
            var position = 0L
            while (true) {
                if (cancelled()) throw AudioProcessingError.Cancelled()
                input.read(position, block, 0, frame)
                silenceAfter(block, position, length)
                var at = position
                var dropped = false
                for (stage in stages) {
                    stage.process(block)
                    at -= stage.latency
                    // A stage's first block is latency, before the recording: the next stage never sees it.
                    if (at < 0) { dropped = true; break }
                    silenceAfter(block, at, length)
                }
                if (!dropped && at < length) {
                    val count = minOf(frame.toLong(), length - at).toInt()
                    for (i in 0 until count * channels) {
                        val value = block[i]
                        if (!value.isFinite()) throw AudioProcessingError.InvalidFormat()
                        if (abs(value) > peak) peak = abs(value)
                    }
                    output(block, count)
                    progress((at + count).toDouble() / maxOf(1L, length))
                }
                if (!dropped && at + frame >= length) break
                position += frame
            }
            return peak
        } finally { for (d in opened) d.close() }
    }
    private fun silenceAfter(block: FloatArray, start: Long, length: Long) {
        if (start + frame <= length) return
        val from = maxOf(0L, length - start).toInt()
        java.util.Arrays.fill(block, from * channels, frame * channels, 0f)
    }

    /// Write the processed sound to `destination` as little-endian float
    /// stereo. Runs of streaming stages go in one pass; LavaSR and VoiceFixer
    /// each read the previous result from a file, as the iPhone app's stages do.
    /// A partial file is never left under the final name.
    fun write(input: PCMSource, length: Long, effects: List<AudioEffect>, denoiser: () -> FrameDenoiser, destination: File,
              progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }, deepFilter: (() -> DeepFilterStream)? = null,
              studio: StudioModels? = null) {
        if (!effects.filter { it.enabled }.all { it.available }) throw AudioProcessingError.UnavailableModel()
        val chain = active(effects)
        val groups = ArrayList<List<AudioEffect>>()
        for (effect in chain) {
            if (windowed(effect) || groups.isEmpty() || groups.last().any { windowed(it) }) groups.add(listOf(effect))
            else groups[groups.size - 1] = groups.last() + effect
        }
        if (groups.isEmpty()) groups.add(emptyList())
        destination.parentFile?.mkdirs()
        val temporary = ArrayList<File>()
        var current: PCMSource = input
        try {
            for ((index, group) in groups.withIndex()) {
                val target = if (index == groups.lastIndex) destination else File(destination.parentFile, destination.name + ".stage-" + UUID.randomUUID()).also { temporary.add(it) }
                val report: (Double) -> Unit = { progress((index + it) / groups.size) }
                val effect = group.singleOrNull()
                when {
                    effect?.kind == EffectKind.studioVoice -> {
                        val models = studio?.takeIf { it.lava != null } ?: throw AudioProcessingError.UnavailableModel()
                        store(target) { out -> StudioVoice.process(current, length, effect.amount.toFloat(), models.dsp, models.lava!!, out, report, cancelled) }
                    }
                    effect?.kind == EffectKind.studioRestoration -> {
                        val models = studio?.takeIf { it.voiceFixer != null && it.bank != null } ?: throw AudioProcessingError.UnavailableModel()
                        restore(current, length, effect.amount.toFloat(), models, target, report, cancelled)
                    }
                    else -> { val from = current; store(target) { out -> stream(from, length, group, denoiser, out, report, cancelled, deepFilter) } }
                }
                if (current !== input) (current as? ProcessedAudio)?.close()
                if (index < groups.lastIndex) current = ProcessedAudio(target)
            }
        } finally {
            if (current !== input) (current as? ProcessedAudio)?.close()
            for (file in temporary) file.delete()
        }
    }

    /// VoiceFixer at 44.1 kHz, then back to 48 kHz and blended with the original.
    private fun restore(input: PCMSource, length: Long, amount: Float, models: StudioModels, target: File, progress: (Double) -> Unit, cancelled: () -> Boolean) {
        val down = Resampler(48000, 44100); val up = Resampler(44100, 48000)
        val lower = File(target.parentFile, target.name + ".44k-" + UUID.randomUUID())
        val restored = File(target.parentFile, target.name + ".restored-" + UUID.randomUUID())
        try {
            store(lower) { out -> down.convert(input, length, out, cancelled) }
            progress(0.05)
            val frames = down.outputLength(length)
            ProcessedAudio(lower).use { source ->
                store(restored) { out -> StudioRestoration.restore(source, frames, models.dsp, models.bank!!, models.voiceFixer!!, out, { progress(0.05 + 0.85 * it) }, cancelled) }
            }
            ProcessedAudio(restored).use { wet ->
                store(target) { out ->
                    val dry = FloatArray(4096 * channels)
                    var position = 0L
                    fun mix(block: FloatArray, count: Int) {
                        val n = minOf(count.toLong(), length - position).toInt()
                        if (n <= 0) return
                        input.read(position, dry, 0, n)
                        for (i in 0 until n * channels) dry[i] += amount * (block[i] - dry[i])
                        out(dry, n); position += n
                    }
                    up.convert(wet, frames, { block, count -> mix(block, count) }, cancelled)
                    // A frame or two the resampler did not reach: restored sound there is silence.
                    val silence = FloatArray(4096 * channels)
                    while (position < length) mix(silence, minOf(4096L, length - position).toInt())
                }
            }
            progress(1.0)
        } finally { lower.delete(); restored.delete() }
    }

    /// Run `produce` into `target` through a partial file, renamed when complete.
    private fun store(target: File, produce: ((FloatArray, Int) -> Unit) -> Unit) {
        val partial = File(target.parentFile, target.name + ".partial-" + UUID.randomUUID())
        try {
            RandomAccessFile(partial, "rw").use { file ->
                val channel = file.channel
                var bytes = ByteBuffer.allocate(frame * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                produce { block, count ->
                    if (bytes.capacity() < count * channels * 4) bytes = ByteBuffer.allocate(count * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.clear(); for (i in 0 until count * channels) bytes.putFloat(block[i]); bytes.flip()
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
                channel.force(false)
            }
            if (!partial.renameTo(target)) throw java.io.IOException("cannot store processed audio")
        } finally { partial.delete() }
    }
}

/// What LavaSR and VoiceFixer need: the iPhone app's DSP and the networks this build carries.
class StudioModels(val dsp: StudioDSP, val lava: LavaNetwork?, val voiceFixer: VoiceFixerNetwork?, val bank: FloatArray?)

/// Processed sound written by OfflineAudioPipeline.write. Reading outside it yields silence.
class ProcessedAudio(file: File) : PCMSource, AutoCloseable {
    private val access = RandomAccessFile(file, "r")
    private val channel: FileChannel = access.channel
    private val frames = channel.size() / (AudioMix.channels * 4)
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        java.util.Arrays.fill(into, offset, offset + frames * AudioMix.channels, 0f)
        val a = maxOf(0L, position); val b = minOf(this.frames, position + frames)
        if (b <= a) return
        val count = ((b - a) * AudioMix.channels).toInt()
        if (bytes.capacity() < count * 4) bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear(); bytes.limit(count * 4)
        var at = a * AudioMix.channels * 4
        while (bytes.hasRemaining()) { val n = channel.read(bytes, at); if (n < 0) break; at += n }
        bytes.flip()
        val start = offset + ((a - position) * AudioMix.channels).toInt()
        bytes.asFloatBuffer().get(into, start, bytes.remaining() / 4)
    }
    override fun close() { access.close() }
}

/// The chains a timeline plays, each over one source.
object AudioEnhancements {
    data class Request(val source: UUID, val effects: List<AudioEffect>)
    fun requests(timeline: TrackTimeline): List<Request> = timeline.audio.flatMap { item ->
        item.enhancements.filter { OfflineAudioPipeline.active(it.effects).isNotEmpty() || it.effects.any { e -> e.enabled && !e.available } }
            .map { Request(item.sourceID, it.effects.filter { e -> e.enabled }) }
    }.distinct()
    /// The first stage this build cannot process, if any.
    fun unavailable(timeline: TrackTimeline): AudioEffect? =
        timeline.audio.flatMap { it.enhancements }.flatMap { it.effects }.firstOrNull { it.enabled && !it.available }
}
