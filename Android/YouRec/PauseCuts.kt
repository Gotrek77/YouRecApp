package yourec

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class SourceInterval(val start: Double, val end: Double) { val duration: Double get() = end - start }
data class DetectedPause(val raw: SourceInterval, val enabled: Boolean = true)
enum class PauseLevelMode { Automatic, Manual; val raw: String get() = name.lowercase() }

/// Silence removed from a clip, as the iPhone app keeps it: the cuts in source
/// seconds, and the detection they came from so it can be reviewed again.
data class PauseSettings(val id: UUID = UUID.randomUUID(), val thresholdDB: Double = -38.0, val minimum: Double = 0.6, val before: Double = 0.12,
                         val after: Double = 0.18, val cuts: List<SourceInterval> = emptyList(), val candidates: List<DetectedPause>? = null,
                         val analyzed: SourceInterval? = null, val levelMode: PauseLevelMode? = PauseLevelMode.Automatic) {
    fun valid(sourceDuration: Double): Boolean {
        fun inside(i: SourceInterval) = i.start.isFinite() && i.end.isFinite() && i.start >= 0 && i.end > i.start && i.end <= sourceDuration + 0.001
        return thresholdDB.isFinite() && thresholdDB in -80.0..0.0 && minimum.isFinite() && minimum in 0.1..10.0 && before.isFinite() && before in 0.0..2.0
            && after.isFinite() && after in 0.0..2.0 && cuts.all(::inside) && (candidates ?: emptyList()).all { inside(it.raw) } && (analyzed?.let(::inside) ?: true)
    }
    /// Only what lies inside a clip's own source range.
    fun limited(start: Double, end: Double) = copy(cuts = cuts.filter { it.start < end && it.end > start },
        candidates = candidates?.filter { it.raw.start < end && it.raw.end > start },
        analyzed = analyzed?.let { val a = maxOf(start, it.start); val b = minOf(end, it.end); if (b > a) SourceInterval(a, b) else null })
}

/// One stretch of source played at one rate.
data class PlaybackSpan(val start: Double, val end: Double, val rate: Double) { val duration: Double get() = (end - start) / rate }

/// Loudness of a source in 10 ms bins, for finding pauses, with RNNoise's
/// speech probability per bin (0–255) when it could be measured.
class SourceLevels(val duration: Double, val rms: FloatArray, val speech: ByteArray? = null)

object PauseDetector {
    const val binsPerSecond = 100

    /// The silence threshold: measured from the recording, or the one set by hand.
    fun levelDB(levels: SourceLevels, settings: PauseSettings): Double {
        if (settings.levelMode != PauseLevelMode.Automatic || levels.rms.isEmpty()) return settings.thresholdDB
        val sorted = levels.rms.sorted()
        fun db(percentile: Int) = 20 * log10(maxOf(1e-8, sorted[(sorted.size - 1) * percentile / 100].toDouble()))
        val floorDB = db(10)
        var speechDB = db(90)
        // A take can be mostly steady room tone with only a few spoken words.
        // A sustained louder passage qualifies as a reference; a lone click cannot.
        if (speechDB - floorDB < 10 && db(50) - floorDB < 5 && levels.duration > 0) {
            val block = maxOf(1, ceil(0.2 * levels.rms.size / levels.duration).toInt())
            var loudest = 0f
            if (block <= levels.rms.size) {
                var i = 0
                while (i <= levels.rms.size - block) { var low = Float.MAX_VALUE; for (j in i until i + block) low = minOf(low, levels.rms[j]); loudest = maxOf(loudest, low); i += block }
            }
            speechDB = maxOf(speechDB, 20 * log10(maxOf(1e-8, loudest.toDouble())))
        }
        // Leave headroom above room tone and stay well below the stronger voice.
        return Math.rint(maxOf(-56.0, minOf(-27.0, floorDB + 8, speechDB - 18)))
    }

    fun padded(raw: SourceInterval, clip: TimelineClip, settings: PauseSettings): SourceInterval {
        val full = clip.copy(pauses = null)
        val a = minOf(raw.end, full.sourceTime(full.timelineTime(raw.start) + settings.before))
        val b = maxOf(a, full.sourceTime(maxOf(0.0, full.timelineTime(raw.end) - settings.after)))
        return SourceInterval(a, b)
    }

    /// The cuts the enabled pauses make, merged, always leaving one frame of the clip.
    fun effectiveCuts(candidates: List<DetectedPause>, clip: TimelineClip, settings: PauseSettings): List<SourceInterval> {
        val frame = TimelineEditing.frame * clip.rate
        val proposed = candidates.filter { it.enabled }.map { padded(it.raw, clip, settings) }.map { SourceInterval(maxOf(clip.start, it.start), minOf(clip.end, it.end)) }
            .filter { it.duration >= frame - 0.000001 }.sortedBy { it.start }
        val cuts = ArrayList<SourceInterval>()
        for (cut in proposed) {
            val last = cuts.lastOrNull()
            if (last != null && cut.start <= last.end) cuts[cuts.size - 1] = last.copy(end = maxOf(last.end, cut.end)) else cuts.add(cut)
        }
        val excess = cuts.sumOf { it.duration } - maxOf(0.0, (clip.end - clip.start) - frame)
        if (excess > 0 && cuts.isNotEmpty()) cuts[cuts.size - 1] = cuts.last().copy(end = cuts.last().end - excess)
        return cuts.filter { it.duration >= frame - 0.000001 }
    }

    /// Unpadded silent stretches in the clip, in source seconds.
    fun raw(levels: SourceLevels, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null) =
        detect(levels, clip, settings.copy(before = 0.0, after = 0.0), levelDB)

    fun detect(levels: SourceLevels, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null): List<SourceInterval> {
        val bins = levels.rms
        if (bins.isEmpty() || levels.duration <= 0) return emptyList()
        val step = levels.duration / bins.size
        val threshold = 10.0.pow((levelDB ?: levelDB(levels, settings)) / 20).toFloat()
        val result = ArrayList<SourceInterval>()
        val full = clip.copy(pauses = null)
        fun append(from: Int, to: Int) {
            val start = from * step; val time = minOf(clip.end, to * step)
            if (full.timelineTime(time) - full.timelineTime(start) < settings.minimum) return
            val interval = padded(SourceInterval(start, time), full, settings)
            if (full.timelineTime(interval.end) - full.timelineTime(interval.start) >= TimelineEditing.frame) result.add(interval)
        }
        val first = maxOf(0, ceil(clip.start / step).toInt()); val last = minOf(bins.size, floor(clip.end / step).toInt())
        if (first >= last) return result
        // Establish that this is dialogue before relaxing the energy gate. For music,
        // steady room tone or an unvoiced selection, keep the ordinary silence test.
        val speech = levels.speech?.takeIf { settings.levelMode == PauseLevelMode.Automatic && it.size == bins.size }
        fun probability(i: Int) = speech!![i].toInt() and 0xff
        var hasDialogue = false
        if (speech != null) {
            var voiced = 0; val reference = maxOf(1, ceil(0.16 / step).toInt())
            for (i in first until last) { voiced = if (probability(i) >= 179) voiced + 1 else 0; if (voiced >= reference) { hasDialogue = true; break } }
        }
        // Let steady noise exceed the normal threshold by up to 12 dB, bounded
        // at -15 dBFS. A likely voice protects even a quiet syllable.
        val noiseLimit = minOf(0.177828f, threshold * 3.981072f)
        var beginning = -1
        fun close(end: Int) {
            val start = beginning
            if (start < 0) return
            beginning = -1
            if (!hasDialogue || end - start < 5 || (start until end).none { bins[it] > threshold }) { append(start, end); return }
            // A faint utterance can slip past the voice classifier. Protect sustained
            // onsets that rise 7 dB above this gap's own background. Three of five
            // 10 ms bins distinguish a short syllable from an isolated noise spike.
            val sorted = (start until end).map { bins[it] }.sorted()
            val onset = maxOf(threshold, sorted[sorted.size / 2] * 2.23872f)
            var elevated = 0; var cursor = start
            for (i in start until end + 2) {
                if (i < end && bins[i] > onset) elevated += 1
                if (i - 5 >= start && bins[i - 5] > onset) elevated -= 1
                if (i >= start + 2 && elevated >= 3) {
                    val protected = i - 2
                    if (cursor < protected) append(cursor, protected)
                    cursor = protected + 1
                }
            }
            if (cursor < end) append(cursor, end)
        }
        for (index in first until last) {
            var quiet = bins[index] <= threshold
            if (hasDialogue) { val p = probability(index); quiet = (quiet && p < 102) || (bins[index] <= noiseLimit && p < 31) }
            if (quiet) { if (beginning < 0) beginning = index } else close(index)
        }
        close(last)
        return result
    }
}
