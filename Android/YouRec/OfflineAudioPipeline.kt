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
    const val cacheVersion = "android-audio-v1|rnnoise-adapter-1|eq-1"
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
                output: (FloatArray, Int) -> Unit, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }): Float {
        if (!effects.filter { it.enabled }.all { it.available }) throw AudioProcessingError.UnavailableModel()
        val chain = active(effects)
        val opened = ArrayList<FrameDenoiser>()
        try {
            val stages = chain.map { effect ->
                if (effect.kind == EffectKind.voiceIsolation) Denoise(effect.amount.toFloat(), List(channels) { denoiser().also { opened.add(it) } })
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
    /// stereo. A partial file is never left under the final name.
    fun write(input: PCMSource, length: Long, effects: List<AudioEffect>, denoiser: () -> FrameDenoiser, destination: File,
              progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".partial-" + UUID.randomUUID())
        try {
            RandomAccessFile(partial, "rw").use { file ->
                val channel = file.channel
                val bytes = ByteBuffer.allocate(frame * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                process(input, length, effects, denoiser, { block, count ->
                    bytes.clear(); for (i in 0 until count * channels) bytes.putFloat(block[i]); bytes.flip()
                    while (bytes.hasRemaining()) channel.write(bytes)
                }, progress, cancelled)
                channel.force(false)
            }
            if (!partial.renameTo(destination)) throw java.io.IOException("cannot store processed audio")
        } finally { partial.delete() }
    }
}

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
