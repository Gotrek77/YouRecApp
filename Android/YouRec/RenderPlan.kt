package yourec

import java.util.UUID
import kotlin.math.abs

/// What the movie shows and plays at any time. Preview and export both ask this
/// one plan, so a cut seen in the editor is the cut written to the file.
class RenderPlan(edit: EditState) {
    val timeline: TrackTimeline = edit.timeline.copy()
    val duration: EditTime = timeline.duration
    private val pictures = timeline.video.sortedWith(TrackTimeline.backToFront)

    data class Picture(val item: UUID, val source: UUID, val sourceTime: Double, val interval: EditInterval, val clip: TimelineClip)
    /// One picture to draw. `slot` tells apart two pictures on screen at once from the same recording.
    data class Layer(val picture: Picture, val placement: VideoPlacement?, val slot: Int)
    /// What to draw at one time, back to front. Pictures that share the frame
    /// through an arrangement are all drawn; without one the front picture
    /// covers the frame. Nothing at all means black, never a held frame.
    fun layers(time: EditTime): List<Layer> {
        val active = pictures.filter { time in it.interval }
        if (active.isEmpty()) return emptyList()
        fun picture(item: VideoTimelineItem) = item.mapping.source(time - item.start)?.let { Picture(item.id, item.content.sourceID, it, item.interval, item.content) }
        val ids = active.map { it.id }
        val region = if (active.size > 1) timeline.placements.firstOrNull { it.participants == ids.toSet() } else null
        if (region == null) return listOfNotNull(picture(active.last())?.let { Layer(it, null, 0) })
        val result = ArrayList<Layer>()
        for (id in VideoPlacement.ordered(ids, region.videos)) {
            val shown = picture(active.first { it.id == id }) ?: continue
            result.add(Layer(shown, region.videos[id], result.count { it.picture.source == shown.source }))
        }
        return result
    }
    fun picture(time: EditTime): Picture? = layers(time).lastOrNull()?.picture
    /// What stays on screen once playback has reached the end: the last frame
    /// of the last pictures, or nothing when the movie ends on sound alone.
    val ending: List<Layer> get() = if (duration > EditTime.zero) layers(duration - EditTime.ticks(EditTime.scale / 60)) else emptyList()
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
    private val stretchers = HashMap<Triple<UUID, Long, Double>, TimeStretch>()
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
                    val rate = (run.sourceEnd - run.sourceStart) / run.duration.seconds
                    if (source != null && Math.abs(rate - 1) < 1e-6) add(item, source, a, (b - a).toInt(), sourceStart + (a - runStart), first, out, ((a - start) * channels).toInt())
                    else if (source != null) {
                        // A retimed part plays through a stretcher: its own length changes, its pitch does not.
                        val stretched = stretchers.getOrPut(Triple(item.id, runStart, rate)) { TimeStretch(source, sourceStart, rate) }
                        add(item, stretched, a, (b - a).toInt(), a - runStart, first, out, ((a - start) * channels).toInt())
                    }
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

/// Plays a recording faster or slower at its own pitch (waveform-similarity
/// overlap-add). Output is built from 40 ms windows taken from the source at
/// the new pace; each window is nudged by up to 10 ms to where it best continues
/// the one before, which is what keeps a voice from sounding rough. Position 0
/// is the source frame `origin`. Reading on from where the last read ended
/// continues the same chain of windows; a jump starts a new one.
class TimeStretch(private val source: PCMSource, private val origin: Long, private val rate: Double) : PCMSource {
    private companion object { const val window = 1920; const val hop = window / 2; const val reach = 480 }
    private val shape = FloatArray(window) { (0.5 - 0.5 * Math.cos(2 * Math.PI * it / window)).toFloat() }
    private val current = FloatArray(window * 2)      // window `block`; `following` is the next one
    private val following = FloatArray(window * 2)
    private val candidates = FloatArray((window + 2 * reach) * 2)
    private var block = Long.MIN_VALUE                // index of `current`
    private var followingStart = 0L                   // source frame where `following` was taken

    // Window j is centred on output frame j * hop, and on the source frame that belongs there.
    private fun nominal(index: Long) = origin + Math.round(index * hop * rate) - hop
    private fun take(start: Long, into: FloatArray) { source.read(start, into, 0, window) }
    /// Choose the start of block `index` near its nominal place so that it lines up with `target`, the natural continuation.
    private fun aligned(index: Long, target: Long): Long {
        val base = nominal(index) - reach
        source.read(base, candidates, 0, window + 2 * reach)
        source.read(target, following, 0, window)
        fun score(shift: Int): Double {
            var sum = 0.0; var i = 0
            while (i < window) { sum += (candidates[(shift + i) * 2] + candidates[(shift + i) * 2 + 1]) * (following[i * 2] + following[i * 2 + 1]); i += 4 }
            return sum
        }
        // The nominal place wins unless another is clearly better: a steady
        // sound has many equally good matches, and wandering between them drifts.
        var best = reach; var bestScore = score(reach)
        val needed = Math.abs(bestScore) * 0.02 + 1e-9
        // A coarse search over a mono mix is enough to find the matching phase.
        var shift = 0
        while (shift <= 2 * reach) {
            val value = score(shift)
            if (value > bestScore + needed) { bestScore = value; best = shift }
            shift += 2
        }
        return base + best
    }
    private fun seek(index: Long) {
        // A chain restarts on its nominal grid, so a restart at the same place always sounds the same.
        val start = nominal(index)
        take(start, current)
        followingStart = aligned(index + 1, start + hop)
        take(followingStart, following)
        block = index
    }
    private fun advance() {
        System.arraycopy(following, 0, current, 0, window * 2)
        val start = followingStart
        block += 1
        followingStart = aligned(block + 1, start + hop)
        take(followingStart, following)
    }

    override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        for (n in 0 until frames) {
            val p = position + n
            if (p < 0) { into[offset + n * 2] = 0f; into[offset + n * 2 + 1] = 0f; continue }
            val index = p / hop
            if (index != block) { if (index == block + 1 && block != Long.MIN_VALUE) advance() else seek(index) }
            // Each output frame is the fading end of one window plus the rising start of the next.
            val inside = (p - index * hop).toInt()
            val falling = shape[inside + hop]; val rising = shape[inside]
            into[offset + n * 2] = current[(inside + hop) * 2] * falling + following[inside * 2] * rising
            into[offset + n * 2 + 1] = current[(inside + hop) * 2 + 1] * falling + following[inside * 2 + 1] * rising
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
