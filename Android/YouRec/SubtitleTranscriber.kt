package yourec

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sqrt

/// Speech to text for one section of 16 kHz mono sound. `language` is a
/// name such as "Italian", or null to detect it.
interface SpeechRecognizer : AutoCloseable {
    fun transcribe(samples: FloatArray, language: String?): String
}

/// After the iPhone app's SubtitleTranscriber: the edited project's own sound
/// in the range (speed changes, levels and effects included) at 16 kHz mono,
/// split into speech sections, each transcribed, words timed over its speech.
object SubtitleTranscriber {
    const val rate = 16000
    val languages = listOf("Auto", "Italian", "English", "French", "German", "Spanish", "Portuguese", "Chinese", "Japanese", "Korean", "Russian", "Arabic")

    /// The mix of `range` (project seconds) as 16 kHz mono float in `scratch`,
    /// and its 20 ms levels for the speech plan.
    fun extract(mix: AudioMix, range: ClosedFloatingPointRange<Double>, scratch: File, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }): FloatArray {
        val first = AudioMix.frame(EditTime.seconds(range.start))
        val length = AudioMix.frame(EditTime.seconds(range.endInclusive)) - first
        if (length <= 0) throw SubtitleError("This range has no audio.")
        // The mix as a source: blocks rendered in order, as playback does.
        val source = object : PCMSource {
            private val block = FloatArray(4096 * AudioMix.channels)
            override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
                var done = 0
                while (done < frames) {
                    val p = position + done
                    val count = minOf(4096, frames - done)
                    if (p >= length || p + count <= 0) { java.util.Arrays.fill(into, offset + done * 2, offset + (done + count) * 2, 0f); done += count; continue }
                    mix.render(first + p, block, count)
                    for (i in 0 until count) {
                        val inside = p + i in 0 until length
                        into[offset + (done + i) * 2] = if (inside) block[i * 2] else 0f; into[offset + (done + i) * 2 + 1] = if (inside) block[i * 2 + 1] else 0f
                    }
                    done += count
                }
            }
        }
        val levels = ArrayList<Float>(); var energy = 0.0; var energyCount = 0
        val resampler = Resampler(AudioMix.sampleRate, rate)
        val total = resampler.outputLength(length)
        RandomAccessFile(scratch, "rw").use { file ->
            file.setLength(0)
            val channel = file.channel
            var written = 0L
            resampler.convert(source, length, { block, count ->
                val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val sample = ((block[i * 2] + block[i * 2 + 1]) / 2).let { if (it.isFinite()) it else 0f }
                    bytes.putFloat(sample)
                    energy += sample.toDouble() * sample; energyCount++
                    if (energyCount == 320) { levels.add(sqrt(energy / 320).toFloat()); energy = 0.0; energyCount = 0 }
                }
                bytes.flip(); while (bytes.hasRemaining()) channel.write(bytes)
                written += count; progress(written.toDouble() / maxOf(1L, total))
            }, cancelled)
        }
        if (energyCount > 0) levels.add(sqrt(energy / energyCount).toFloat())
        return levels.toFloatArray()
    }

    /// Words for `range` from the extracted sound. `progress` reports a
    /// fraction and the section being transcribed.
    fun transcribe(scratch: File, levels: FloatArray, range: ClosedFloatingPointRange<Double>, language: String, recognizer: SpeechRecognizer,
                   progress: (Double, String) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): List<SubtitleWord> {
        val chunks = SubtitleSpeechPlan.chunks(levels, range.endInclusive - range.start)
        if (chunks.isEmpty()) throw SubtitleError("No speech was detected in this range. Try a range with audible speech.")
        val words = ArrayList<SubtitleWord>()
        val started = System.nanoTime(); var decoded = 0.0
        val totalAudio = chunks.sumOf { it.range.endInclusive - it.range.start }
        RandomAccessFile(scratch, "r").use { file ->
            for ((index, chunk) in chunks.withIndex()) {
                if (cancelled()) throw AudioProcessingError.Cancelled()
                var message = "Transcribing section ${index + 1} of ${chunks.size}"
                if (decoded > 0) {
                    val left = (System.nanoTime() - started) / 1e9 / decoded * (totalAudio - decoded)
                    message += " · about " + (if (left >= 60) "${Math.round(left / 60)} min" else "${maxOf(1, Math.round(left))} s") + " left"
                } else message += " · estimating time…"
                progress(index.toDouble() / chunks.size, message)
                val a = Math.round(chunk.range.start * rate); val count = maxOf(1, Math.round((chunk.range.endInclusive - chunk.range.start) * rate).toInt())
                val bytes = ByteArray(count * 4)
                file.seek(a * 4); val read = maxOf(0, file.read(bytes))
                val samples = FloatArray(count).also { ByteBuffer.wrap(bytes, 0, read - read % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it, 0, read / 4) }
                val text = recognizer.transcribe(samples, if (language == "Auto") null else language)
                decoded += chunk.range.endInclusive - chunk.range.start
                val speech = chunk.speech.map { (it.start + range.start)..(it.endInclusive + range.start) }
                words += SubtitleTiming.estimate(text, speech)
            }
        }
        if (words.isEmpty()) throw SubtitleError("The model did not find spoken words. Your previous subtitles are unchanged.")
        progress(1.0, "Subtitles ready")
        return words
    }
}
