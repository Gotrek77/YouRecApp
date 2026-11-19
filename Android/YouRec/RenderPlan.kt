package yourec

import java.util.UUID
import kotlin.math.abs

/// What the movie shows and plays at any time. Preview and export both ask this
/// one plan, so a cut seen in the editor is the cut written to the file.
class RenderPlan(edit: EditState) {
    val timeline: TrackTimeline = edit.timeline.copy()
    val duration: EditTime = timeline.duration
    private val pictures = timeline.video.sortedWith(TrackTimeline.backToFront)

    data class Picture(val item: UUID, val source: UUID, val sourceTime: Double, val interval: EditInterval)
    /// The front picture covers the frame. No picture means black, never a held frame.
    fun picture(time: EditTime): Picture? {
        val item = pictures.lastOrNull { time in it.interval } ?: return null
        return Picture(item.id, item.content.sourceID, item.mapping.source(time - item.start) ?: return null, item.interval)
    }
    /// What stays on screen once playback has reached the end: the last frame
    /// of the last picture, or nothing when the movie ends on sound alone.
    val ending: Picture? get() = if (duration > EditTime.zero) picture(duration - EditTime.ticks(EditTime.scale / 60)) else null
    /// The next time at which a different picture, or none, takes over.
    fun pictureChange(after: EditTime): EditTime =
        (pictures.flatMap { listOf(it.start, it.end) }.filter { it > after }.minOrNull() ?: duration)

    fun frameCount(framesPerSecond: Int): Long = Math.ceil(duration.seconds * framesPerSecond - 1e-9).toLong()
    val sourcesInUse: Set<UUID> get() = (timeline.video.map { it.content.sourceID } + timeline.audio.map { it.sourceID }).toSet()

    /// Scale a source picture to sit wholly inside the canvas, centred: footage
    /// keeps its full frame when the project shape differs from its own.
    data class Fit(val scaleX: Float, val scaleY: Float)
    companion object {
        fun fit(sourceWidth: Int, sourceHeight: Int, canvasWidth: Int, canvasHeight: Int): Fit {
            if (sourceWidth <= 0 || sourceHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return Fit(1f, 1f)
            val source = sourceWidth.toDouble() / sourceHeight; val canvas = canvasWidth.toDouble() / canvasHeight
            // Sources a pixel or two away from the canvas shape fill it, without hairline bars.
            if (abs(source - canvas) < 0.01) return Fit(1f, 1f)
            return if (source > canvas) Fit(1f, (canvas / source).toFloat()) else Fit((source / canvas).toFloat(), 1f)
        }
        /// Encoders need even dimensions.
        fun even(value: Double): Int = maxOf(2, (Math.rint(value / 2) * 2).toInt())
    }
}

/// Decoded sound of one source as interleaved stereo float at the mix rate.
/// Reading outside the recording yields silence.
interface PCMSource {
    fun read(position: Long, into: FloatArray, offset: Int, frames: Int)
}

/// The project sound for any block of time: every active item, at its own
/// level and fades, summed. Nothing is limited here; export measures the peak.
class AudioMix(private val timeline: TrackTimeline, private val open: (UUID) -> PCMSource?) {
    companion object {
        const val sampleRate = 48_000
        const val channels = 2
        fun frame(time: EditTime): Long = Math.floorDiv(time.ticks * sampleRate, EditTime.scale)
    }
    private val scratch = FloatArray(4096 * channels)
    var peak = 0f
        private set

    /// Fill `frames` frames starting at project frame `start`. The block is overwritten.
    fun render(start: Long, out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames * channels, 0f)
        for (item in timeline.audio) {
            if (item.gain == 0.0) continue
            val first = frame(item.start)
            var runStart = first
            var local = EditTime.zero
            for (run in item.mapping.runs) {
                val runEnd = first + frame(local + run.duration)
                val sourceStart = Math.round(run.sourceStart * sampleRate)
                val a = maxOf(start, runStart); val b = minOf(start + frames, runEnd)
                if (b > a && !run.silent && !run.isHold) {
                    val source = open(item.sourceID)
                    if (source != null) add(item, source, a, (b - a).toInt(), sourceStart + (a - runStart), first, out, ((a - start) * channels).toInt())
                }
                runStart = runEnd; local += run.duration
            }
        }
        for (i in 0 until frames * channels) { val level = abs(out[i]); if (level > peak) peak = level }
    }

    private fun add(item: AudioTimelineItem, source: PCMSource, projectFrame: Long, frames: Int, sourceFrame: Long, itemStart: Long, out: FloatArray, outOffset: Int) {
        val plain = item.fadeIn == null && item.fadeOut == null
        val level = item.gain.toFloat()
        var done = 0
        while (done < frames) {
            val count = minOf(frames - done, scratch.size / channels)
            source.read(sourceFrame + done, scratch, 0, count)
            for (i in 0 until count) {
                val gain = if (plain) level else item.gain((projectFrame + done + i - itemStart).toDouble() / sampleRate).toFloat()
                val o = outOffset + (done + i) * channels
                out[o] += scratch[i * channels] * gain
                out[o + 1] += scratch[i * channels + 1] * gain
            }
            done += count
        }
    }
}

/// Peak buckets for drawing a waveform: one value per fixed slice of time, so a
/// long recording draws from a small array instead of its samples.
class WaveformPeaks(val bucketsPerSecond: Int, val peaks: ByteArray) {
    val duration: Double get() = peaks.size.toDouble() / bucketsPerSecond
    /// The loudest bucket in a source interval, from 0 to 1.
    fun level(start: Double, end: Double): Float {
        val a = (start * bucketsPerSecond).toInt().coerceIn(0, peaks.size)
        val b = Math.ceil(end * bucketsPerSecond).toInt().coerceIn(a, peaks.size)
        var top = 0
        for (i in a until maxOf(b, minOf(a + 1, peaks.size))) top = maxOf(top, peaks[i].toInt() and 0xff)
        return top / 255f
    }
    class Builder(val bucketsPerSecond: Int, private val sampleRate: Int) {
        private val out = java.io.ByteArrayOutputStream()
        private var filled = 0L
        private var top = 0f
        private val bucket = sampleRate / bucketsPerSecond
        fun add(samples: FloatArray, frames: Int, channels: Int) {
            for (i in 0 until frames) {
                for (c in 0 until channels) { val level = abs(samples[i * channels + c]); if (level > top) top = level }
                if (++filled % bucket == 0L) flush()
            }
        }
        private fun flush() { out.write((minOf(1f, top) * 255).toInt()); top = 0f }
        fun build(): WaveformPeaks { if (filled % bucket != 0L) flush(); return WaveformPeaks(bucketsPerSecond, out.toByteArray()) }
    }
}
