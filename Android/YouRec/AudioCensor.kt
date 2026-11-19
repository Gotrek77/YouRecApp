package yourec

import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class CensorWaveform { Sine, Triangle, Square; val raw: String get() = name.lowercase() }
enum class CensorEnvelope { Short, Soft, Pulse; val raw: String get() = name.lowercase() }

/// The tone that replaces a censored sound, as the iPhone app makes it.
data class AudioCensorSettings(val frequency: Double = 1000.0, val levelDB: Double = -18.0,
                               val waveform: CensorWaveform = CensorWaveform.Sine, val envelope: CensorEnvelope = CensorEnvelope.Short) {
    val valid: Boolean get() = frequency.isFinite() && frequency in 100.0..4000.0 && levelDB.isFinite() && levelDB in -36.0..-6.0
    val amplitude: Double get() = Math.pow(10.0, levelDB / 20)

    /// Phase is derived from time, not call order: seeking and exporting
    /// produce the same samples. Triangle harmonics stop below Nyquist; a
    /// polynomial band-limited step smooths the square wave's discontinuities.
    fun oscillator(time: Double, sampleRate: Double): Double {
        val phase = 2 * PI * ((time * frequency) % 1.0)
        if (waveform == CensorWaveform.Sine) return sin(phase)
        if (waveform == CensorWaveform.Square) {
            val raw = (time * frequency) % 1.0
            val t = if (raw < 0) raw + 1 else raw; val dt = minOf(0.49, frequency / sampleRate)
            fun blep(t: Double): Double {
                if (t < dt) { val x = t / dt; return x + x - x * x - 1 }
                if (t > 1 - dt) { val x = (t - 1) / dt; return x * x + x + x + 1 }
                return 0.0
            }
            return (if (t < 0.5) 1.0 else -1.0) + blep(t) - blep((t + 0.5) % 1.0)
        }
        val maximum = minOf(17, Math.floor(sampleRate * 0.45 / frequency).toInt())
        var value = 0.0; var scale = 0.0
        var harmonic = 1
        while (harmonic <= maxOf(1, maximum)) {
            val n = harmonic.toDouble()
            val coefficient = (if ((harmonic / 2) % 2 == 0) 1.0 else -1.0) / (n * n)
            value += coefficient * sin(n * phase); scale += abs(coefficient)
            harmonic += 2
        }
        return value / maxOf(1.0, scale)
    }
    fun envelopeValue(time: Double, duration: Double): Double {
        if (time < 0 || time >= duration) return 0.0
        val fade = minOf(duration / 2, if (envelope == CensorEnvelope.Soft) 0.06 else 0.008)
        fun smooth(value: Double): Double { val x = value.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }
        var result = smooth(time / maxOf(0.00001, fade)) * smooth((duration - time) / maxOf(0.00001, fade))
        if (envelope == CensorEnvelope.Pulse) {
            val t = time % 0.25
            result *= smooth(t / 0.008) * smooth((0.18 - t) / 0.008)
        }
        return result
    }
}

/// A censored part of one sound item, in its local time.
data class AudioCensorRegion(val id: UUID = UUID.randomUUID(), val interval: EditInterval, val phaseOrigin: EditTime,
                             val settings: AudioCensorSettings, val envelopeInterval: EditInterval? = null) {
    fun sliced(part: EditInterval): AudioCensorRegion? {
        val start = maxOf(interval.start, part.start); val end = minOf(interval.end, part.end)
        if (end <= start) return null
        val envelope = envelopeInterval ?: interval
        return copy(interval = EditInterval(start - part.start, end - part.start), envelopeInterval = EditInterval(envelope.start - part.start, envelope.end - part.start),
                    phaseOrigin = phaseOrigin - part.start)
    }
    fun shifted(amount: EditTime) = copy(interval = EditInterval(interval.start + amount, interval.end + amount), phaseOrigin = phaseOrigin + amount,
        envelopeInterval = envelopeInterval?.let { EditInterval(it.start + amount, it.end + amount) })
}

/// Absolute render clock. The audible generator is owned by one item for each
/// shared region; overlapping censored tracks are all silenced but cannot stack
/// identical beeps and unexpectedly multiply their level.
class AudioCensorSpan(val start: Double, val end: Double, val phaseOrigin: Double, val envelopeStart: Double, val envelopeEnd: Double,
                      val settings: AudioCensorSettings, val emitsTone: Boolean) {
    fun originalGain(time: Double): Double {
        if (time >= start && time < end) return 0.0
        return (if (time < start) (start - time) / 0.005 else (time - end) / 0.005).coerceIn(0.0, 1.0)
    }
    fun tone(time: Double, sampleRate: Double): Double {
        if (!emitsTone || time < start || time >= end) return 0.0
        return settings.amplitude * settings.envelopeValue(time - envelopeStart, envelopeEnd - envelopeStart) * settings.oscillator(time - phaseOrigin, sampleRate)
    }
}

object AudioCensor {
    /// Censor the marked range of the selected sound, or remove censoring there with null settings.
    fun censor(timeline: TrackTimeline, settings: AudioCensorSettings?, selection: TimelineSelection, effectID: UUID = UUID.randomUUID(), removingOnly: UUID? = null) {
        timeline.audio = timeline.audio.map { item ->
            if (item.id !in selection.audio) return@map item
            val range = selection.range
            val target = if (range != null) {
                val s = maxOf(item.start, range.start); val e = minOf(item.end, range.end)
                if (e <= s) return@map item else EditInterval(s, e)
            } else item.interval
            val local = EditInterval(target.start - item.start, target.end - item.start)
            val regions = ArrayList<AudioCensorRegion>()
            for (region in item.censors) {
                if ((removingOnly != null && region.id != removingOnly) || region.interval.end <= local.start || region.interval.start >= local.end) { regions.add(region); continue }
                val envelope = region.envelopeInterval ?: region.interval
                if (region.interval.start < local.start) regions.add(region.copy(interval = EditInterval(region.interval.start, local.start), envelopeInterval = EditInterval(envelope.start, local.start)))
                if (region.interval.end > local.end) regions.add(region.copy(interval = EditInterval(local.end, region.interval.end), envelopeInterval = EditInterval(local.end, envelope.end)))
            }
            if (settings != null) {
                // All pieces of one operation share the event's clock, even
                // when a later clip begins halfway through the censor range.
                val envelope = range?.let { EditInterval(it.start - item.start, it.end - item.start) }
                regions.add(AudioCensorRegion(effectID, local, envelope?.start ?: local.start, settings, envelope))
            }
            item.copy(censors = regions.sortedBy { it.interval.start })
        }
    }

    fun spans(timeline: TrackTimeline, item: AudioTimelineItem): List<AudioCensorSpan> = item.censors.flatMap { region ->
        val start = item.start + region.interval.start; val end = item.start + region.interval.end
        val envelope = region.envelopeInterval ?: region.interval
        val order = timeline.audio.indexOfFirst { it.id == item.id }.let { if (it < 0) 0 else it }
        val others = timeline.audio.withIndex().filter { (index, other) ->
            !other.muted && (other.volumeDB > item.volumeDB || (other.volumeDB == item.volumeDB && index < order))
        }.flatMap { (_, other) ->
            other.censors.filter { it.id == region.id && it.settings == region.settings }.map { EditInterval(other.start + it.interval.start, other.start + it.interval.end) }
        }.mapNotNull { o -> val s = maxOf(o.start, start); val e = minOf(o.end, end); if (e > s) EditInterval(s, e) else null }
        val points = (listOf(start, end) + others.flatMap { listOf(it.start, it.end) }).toSortedSet().toList()
        points.zipWithNext().map { (a, b) ->
            AudioCensorSpan(a.seconds, b.seconds, (item.start + region.phaseOrigin).seconds, (item.start + envelope.start).seconds, (item.start + envelope.end).seconds,
                region.settings, others.none { a >= it.start && a < it.end })
        }
    }

    /// A follower's sound took a new playback map: keep each censored part on
    /// the same source sound, wherever it now plays.
    fun remapped(regions: List<AudioCensorRegion>, before: SourcePlaybackMap, after: SourcePlaybackMap): List<AudioCensorRegion> {
        val pieces = ArrayList<AudioCensorRegion>()
        for (region in regions) {
            val oldRuns = before.sliced(region.interval).runs
            var cursor = EditTime.zero
            for (run in after.runs) {
                for (old in oldRuns) {
                    if (old.isHold || run.isHold) continue
                    val a = maxOf(old.sourceStart, run.sourceStart); val b = minOf(old.sourceEnd, run.sourceEnd)
                    if (b <= a) continue
                    val span = run.sourceEnd - run.sourceStart
                    val interval = EditInterval(cursor + EditTime.seconds(run.duration.seconds * (a - run.sourceStart) / span), cursor + EditTime.seconds(run.duration.seconds * (b - run.sourceStart) / span))
                    if (!interval.isEmpty) pieces.add(region.copy(interval = interval, phaseOrigin = interval.start, envelopeInterval = null))
                }
                cursor += run.duration
            }
        }
        val merged = ArrayList<AudioCensorRegion>()
        for (region in pieces.sortedBy { it.interval.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.id == region.id && last.settings == region.settings && last.interval.end == region.interval.start)
                merged[merged.size - 1] = last.copy(interval = EditInterval(last.interval.start, region.interval.end))
            else merged.add(region)
        }
        return merged
    }
}
