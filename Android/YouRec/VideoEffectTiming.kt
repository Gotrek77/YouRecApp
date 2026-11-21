package yourec

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/// Which source frames a picture shows under the time effects, after the
/// iPhone app's VideoEffectSampling.swift and VideoEffectCadenceClock:
/// Undercranking and Super 8 hold each frame for their cranked rate; Step
/// printing shows (and smears) a block of source frames; a freeze holds one.

/// The displayed frames of one source movie, in seconds, from its own timestamps.
class VideoEffectFrameIndex(val times: DoubleArray) {
    val valid: Boolean get() = times.isNotEmpty() && times.size <= 3_000_000 && times.all { it.isFinite() && it in 0.0..604800.0 } && (1 until times.size).all { times[it - 1] < times[it] }
    fun first(at: Double): Int {
        var a = 0; var b = times.size
        while (a < b) { val m = (a + b) / 2; if (times[m] < at - 0.0000001) a = m + 1 else b = m }
        return a
    }
    fun last(at: Double): Int {
        var a = 0; var b = times.size
        while (a < b) { val m = (a + b) / 2; if (times[m] <= at + 0.0000001) a = m + 1 else b = m }
        return max(0, a - 1)
    }
    fun block(at: Double, origin: Double, count: Int, lower: Double, upper: Double, firstAtOrAfter: Boolean): List<Double> {
        val start = last(origin); val index = if (firstAtOrAfter) first(at) else last(at)
        val block = start + (max(start, min(times.size - 1, index)) - start) / count * count
        val minimum = min(times.size - 1, first(lower)); val lastBefore = first(upper) - 1
        if (lastBefore < minimum) return List(count) { times[last(lower)] }
        val maximum = min(times.size - 1, lastBefore)
        return (0 until count).map { times[min(maximum, max(minimum, block + it))] }
    }
}

/// Undercranking and Super 8: a held frame per cranked frame, integrated
/// over the effects' own source intervals so splits and pauses keep the phase.
class VideoEffectCadenceClock(effects: List<VideoEffectSettings>, rates: List<PlaybackSpan> = emptyList()) {
    class Segment(val start: Double, val end: Double, val phase: Double, val frequency: Double, val groupStart: Double) {
        val endPhase: Double get() = phase + (end - start) * frequency
    }
    data class Hold(val index: Int, val boundary: Double)
    private val segments: List<Segment>
    init {
        val cranked = effects.filter { it.enabled && (it.kind == VideoEffectID.superEight || it.kind == VideoEffectID.undercranking) }
        var points = cranked.flatMap { e -> listOf(e.start, e.end) + e.parameters.filter { it.id == "fps" || it.id == "intensity" }.flatMap { p -> p.keyframes.map { it.time } } }
        points = points + rates.flatMap { listOf(it.start, it.end) }
        val sorted = points.toSortedSet().toList()
        val values = ArrayList<Segment>(); var phase = 0.0; var group = 0.0; var active = false
        for ((a, b) in sorted.zipWithNext()) {
            if (b <= a) continue
            val middle = (a + b) / 2
            val fps = cranked.filter { it.start <= middle && it.end > middle && (!it.kind.descriptor.supportsIntensity || it.number("intensity", middle) > 0) }
                .map { it.number("fps", middle) }.minOrNull() ?: 0.0
            if (fps <= 0) { active = false; continue }
            if (!active) { phase = ceil(phase - 0.000001); group = a }
            active = true
            val rate = rates.firstOrNull { it.start <= middle && it.end > middle }?.rate ?: 1.0
            val segment = Segment(a, b, phase, fps / max(0.001, rate), group)
            values.add(segment); phase = segment.endPhase
        }
        segments = values
    }
    fun hold(at: Double): Hold? {
        var a = 0; var b = segments.size
        while (a < b) { val m = (a + b) / 2; if (segments[m].start <= at) a = m + 1 else b = m }
        if (a <= 0) return null
        val current = segments[a - 1]
        if (at >= current.end) return null
        val index = floor(current.phase + (at - current.start) * current.frequency + 0.000001)
        a = 0; b = segments.size
        while (a < b) { val m = (a + b) / 2; if (segments[m].endPhase < index - 0.000001) a = m + 1 else b = m }
        val source = segments[min(a, segments.size - 1)]
        return Hold(index.toInt(), max(current.groupStart, source.start + (index - source.phase) / source.frequency))
    }
}

/// The original trim bounds, integrated from each effect's own origin; ranged
/// effects keep their phase on both sides of a hole in the look.
class VideoEffectSequenceClock(clip: TimelineClip) {
    val lower = clip.start; val upper = clip.end
    private val cadence: VideoEffectCadenceClock
    private val scoped: List<Pair<Double, VideoEffectCadenceClock>>
    init {
        val effects = (clip.videoEffects ?: emptyList()).filter { it.enabled && (it.kind == VideoEffectID.superEight || it.kind == VideoEffectID.undercranking) }
        val extended = clip.copy(pauses = null, start = min(clip.start, effects.minOfOrNull { it.start } ?: clip.start), end = max(clip.end, effects.maxOfOrNull { it.end } ?: clip.end))
        val rates = extended.playbackSpans
        cadence = VideoEffectCadenceClock(effects, rates)
        val windows = clip.effectWindows?.filterKeys { id -> effects.any { it.id == id } } ?: emptyMap()
        scoped = if (windows.isEmpty()) emptyList() else {
            val edges = windows.values.flatten().flatMap { listOf(it.start, it.end) }.filter { it > clip.start && it < clip.end }.toSortedSet().toList()
            val bounds = listOf(clip.start) + edges + listOf(clip.end)
            bounds.zipWithNext().map { (a, b) ->
                val visible = effects.filter { e -> windows[e.id]?.any { (a + b) / 2 >= it.start && (a + b) / 2 < it.end } ?: true }
                b to VideoEffectCadenceClock(visible, rates)
            }
        }
    }
    fun hold(at: Double): VideoEffectCadenceClock.Hold? {
        if (scoped.isEmpty()) return cadence.hold(at)
        var low = 0; var high = scoped.size
        while (low < high) { val mid = (low + high) / 2; if (scoped[mid].first <= at) low = mid + 1 else high = mid }
        return if (low < scoped.size) scoped[low].second.hold(at) else null
    }
}

/// The source frames to show for one output frame, and how much they smear.
data class VideoEffectSamplePlan(val times: List<Double>, val smear: Double, val firstAtOrAfter: Boolean = false, val before: Double = Double.POSITIVE_INFINITY) {
    companion object {
        /// Whether a clip's pictures need a plan at all.
        fun needed(clip: TimelineClip) = clip.frozenFrame != null || clip.videoEffects?.any { it.enabled && (it.kind == VideoEffectID.stepPrinting || it.kind == VideoEffectID.undercranking || it.kind == VideoEffectID.superEight) } == true

        fun make(clip: TimelineClip, sourceTime: Double, sourceFPS: Double, clock: VideoEffectSequenceClock, frames: VideoEffectFrameIndex? = null): VideoEffectSamplePlan? {
            clip.frozenFrame?.let { return VideoEffectSamplePlan(listOf(it.sourceTime), 0.0) }
            val effects = (clip.videoEffects ?: emptyList()).filter { it.enabled && sourceTime >= it.start && sourceTime < it.end }
            var sample = sourceTime; var changed = false
            clock.hold(sourceTime)?.let { sample = it.boundary; changed = true }
            val step = effects.firstOrNull { it.kind == VideoEffectID.stepPrinting }
            if (step != null) {
                val count = max(2, step.number("step", sourceTime).toInt())
                if (frames != null) return VideoEffectSamplePlan(frames.block(sample, step.start, count, clock.lower, clock.upper, changed), step.number("smear", sourceTime), true, clock.upper)
                // Without a frame index: nearest sampling at the nominal rate.
                val selected = if (changed) ceil(max(0.0, sample) * max(1.0, sourceFPS) - 0.0001) / max(1.0, sourceFPS) else sample
                val frame = floor(max(0.0, selected - step.start) * sourceFPS + 0.00001).toInt()
                val first = frame / count * count
                val last = max(clock.lower, clock.upper - 1.0 / sourceFPS)
                return VideoEffectSamplePlan((0 until count).map { min(last, max(clock.lower, step.start + (first + it - 0.5) / sourceFPS)) }, step.number("smear", sourceTime), true, clock.upper)
            }
            return if (changed) VideoEffectSamplePlan(listOf(min(clock.upper - 0.00001, max(clock.lower, sample))), 0.0, true, clock.upper) else null
        }
    }
    /// The weight each later frame is mixed in with (CIDissolve, normalised): the
    /// first frame starts at 1 - smear + smear/count, each next adds smear/count.
    val mixes: List<Double> get() {
        if (times.size <= 1 || smear <= 0) return emptyList()
        val weight = smear / times.size; var accumulated = 1 - smear + weight
        return times.drop(1).map { accumulated += weight; weight / accumulated }
    }
}
