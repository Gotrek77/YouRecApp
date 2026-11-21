package yourec

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class SubtitleError(message: String) : Exception(message)

/// Times are estimates, anchored in the original sound. Each audio item owns
/// its words by value: splits, moves, copies and Undo cannot share mutable text.
data class SubtitleWord(val id: UUID = UUID.randomUUID(), val phraseID: UUID = UUID.randomUUID(), val text: String, val start: Double, val end: Double)

enum class SubtitleFont(val raw: String, val title: String) {
    System("system", "System"), Modern("modern", "Modern"), Classic("classic", "Classic"), Mono("mono", "Mono"), Condensed("condensed", "Narrow")
}

/// How subtitles look and how many words make a caption. `appearance` and
/// `motion` (title designs) are the iPhone app's and refused by name here.
data class SubtitleSettings(val id: UUID = UUID.randomUUID(), val maxWords: Int = 6, val style: TextStyle = TextStyle.Box,
                            val color: TextColor = TextColor.White, val size: Double = 0.052, val y: Double = 0.86,
                            val language: String = "Auto", val timingFingerprint: String = "", val x: Double? = null, val font: SubtitleFont? = null) {
    val centerX: Double get() = x ?: 0.5
    val resolvedFont: SubtitleFont get() = font ?: SubtitleFont.System
    val valid: Boolean get() = maxWords in 1..16 && size.isFinite() && size in 0.0001..0.30 && y.isFinite() && y in 0.0..1.0 && centerX.isFinite() && centerX in 0.0..1.0
}

/// One caption on screen, in project seconds.
data class SubtitleCue(val id: String, val audioID: UUID, val wordIDs: List<UUID>, val text: String, val start: Double, val end: Double) {
    fun overlay(settings: SubtitleSettings) = TextOverlay(text = text, start = start, end = end, x = settings.centerX, y = settings.y,
        size = settings.size.coerceAtLeast(0.02), style = settings.style, color = settings.color)
}

object SubtitleTiming {
    /// Words with their punctuation; languages without spaces are split by the platform's word rules.
    fun tokens(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getWordInstance(Locale.ROOT); iterator.setText(value)
        val starts = ArrayList<Int>()
        var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (value.substring(start, end).any { it.isLetterOrDigit() }) starts.add(start)
            start = end; end = iterator.next()
        }
        if (starts.isEmpty()) return listOf(value)
        starts[0] = 0
        return starts.indices.map { i -> value.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else value.length).trim() }.filter { it.isNotEmpty() }
    }
    fun joined(words: List<String>) = words.joinToString(" ")
    private fun weight(text: String) = max(1, text.count { it.isLetter() }).toDouble().pow(0.65)
    private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /// Divide voiced time, never the intervening silence. Allocate each word to
    /// one speech island before interpolating within that island.
    fun estimate(text: String, speech: List<ClosedFloatingPointRange<Double>>): List<SubtitleWord> {
        val words = tokens(text); val spans = speech.filter { it.endInclusive > it.start }
        if (words.isEmpty() || spans.isEmpty()) return emptyList()
        val totalTime = spans.sumOf { it.endInclusive - it.start }
        val weights = words.map(::weight); val totalWeight = weights.sum()
        val grouped = List(spans.size) { ArrayList<Int>() }; var consumed = 0.0
        for (i in words.indices) {
            val midpoint = (consumed + weights[i] / 2) / totalWeight * totalTime
            var cursor = 0.0; var island = spans.size - 1
            for (j in spans.indices) { cursor += spans[j].endInclusive - spans[j].start; if (midpoint < cursor) { island = j; break } }
            grouped[island].add(i); consumed += weights[i]
        }
        return spans.indices.flatMap { j ->
            val sum = grouped[j].sumOf { weights[it] }
            var cursor = spans[j].start
            grouped[j].map { i ->
                val end = min(spans[j].endInclusive, cursor + (spans[j].endInclusive - spans[j].start) * weights[i] / max(sum, 0.001))
                SubtitleWord(text = words[i], start = cursor, end = end).also { cursor = end }
            }
        }
    }
    fun group(input: List<SubtitleWord>, maxWords: Int): List<SubtitleWord> {
        val words = input.sortedBy { it.start }.toMutableList()
        var group = UUID.randomUUID(); var count = 0
        for (i in words.indices) {
            if (i > 0 && (count >= maxWords || words[i].start - words[i - 1].end > 0.32 || words[i - 1].text.lastOrNull()?.let { it in ".!?。！？" } == true)) { group = UUID.randomUUID(); count = 0 }
            words[i] = words[i].copy(phraseID = group); count++
        }
        return words
    }
    /// LCS keeps unchanged words anchored. Replacements share only the freed
    /// interval between neighbouring surviving words. Full rewrites retain the
    /// old phrase interval. Punctuation-only edits leave all timings intact.
    fun corrected(oldInput: List<SubtitleWord>, text: String): List<SubtitleWord> {
        val old = oldInput.sortedBy { it.start }; val next = tokens(text)
        val first = old.firstOrNull() ?: return emptyList(); val last = old.last()
        if (next.isEmpty()) return emptyList()
        if (old.size > 128 || next.size > 128)
            return estimate(joined(next.take(128)), listOf(first.start..last.end)).map { it.copy(phraseID = first.phraseID) }
        val a = old.map { normalized(it.text) }; val b = next.map(::normalized)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else max(dp[i + 1][j], dp[i][j + 1])
        val anchors = ArrayList<Pair<Int, Int>>(); var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { anchors.add(i to j); i++; j++ } else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        val result = ArrayList<SubtitleWord>(); var priorOld = -1; var priorNew = -1
        for ((oi, ni) in anchors + (old.size to next.size)) {
            if (ni > priorNew + 1) {
                var start = if (priorOld < 0) first.start else old[priorOld].end
                var end = if (oi == old.size) last.end else old[oi].start
                // Insertion between adjacent words has no empty interval. Borrow
                // a small part of the following/preceding word within the phrase.
                if (end - start < 0.04) {
                    if (oi < old.size) end = min(old[oi].end, start + (old[oi].end - old[oi].start) * 0.45)
                    else result.lastOrNull()?.let { previous ->
                        start = max(previous.start, previous.end - (previous.end - previous.start) * 0.45)
                        result[result.size - 1] = previous.copy(end = start)
                    }
                }
                result += estimate(joined(next.subList(priorNew + 1, ni)), listOf(start..max(start, end))).map { it.copy(phraseID = first.phraseID) }
            }
            if (oi < old.size) {
                val w = old[oi]; val begin = max(w.start, result.lastOrNull()?.end ?: w.start)
                if (w.end > begin) result.add(w.copy(text = next[ni], start = begin))
            }
            priorOld = oi; priorNew = ni
        }
        return result
    }
}

/// Speech islands from 20 ms levels, merged into sections of at most 12 s for
/// the model, as on iPhone (SubtitleSpeech.swift).
data class SubtitleSpeechChunk(val range: ClosedFloatingPointRange<Double>, val speech: List<ClosedFloatingPointRange<Double>>)
object SubtitleSpeechPlan {
    /// An energy gate identifies timing anchors, not speakers. Qwen identifies
    /// speech. The adaptive floor is capped so a continuously spoken passage
    /// does not classify its quieter syllables as background.
    fun chunks(levels: FloatArray, duration: Double, step: Double = 0.02): List<SubtitleSpeechChunk> {
        if (levels.isEmpty() || duration <= 0) return emptyList()
        val db = DoubleArray(levels.size) { 20 * kotlin.math.log10(max(1e-9, levels[it].toDouble())) }
        val sorted = db.sorted(); val floor = sorted[min(sorted.size - 1, sorted.size / 5)]
        val threshold = max(-52.0, min(-35.0, floor + 9))
        val islands = ArrayList<ClosedFloatingPointRange<Double>>(); var begin: Int? = null; var last = 0
        for (i in db.indices) {
            if (db[i] > threshold) { if (begin == null) begin = i; last = i }
            val start = begin
            if (start != null && (i - last > 8 || i == db.size - 1)) {
                if (last - start >= 2) {
                    val a = max(0.0, start * step - 0.08); val b = min(duration, (last + 1) * step + 0.1)
                    val prior = islands.lastOrNull()
                    if (prior != null && a <= prior.endInclusive) islands[islands.size - 1] = prior.start..b
                    else if (b > a) islands.add(a..b)
                }
                begin = null
            }
        }
        // Split long unbroken speech near the quietest 100 ms in a bounded window.
        val bounded = ArrayList<ClosedFloatingPointRange<Double>>()
        for (island in islands) {
            var a = island.start
            while (island.endInclusive - a > 12) {
                val lower = min(db.size - 1, ((a + 8) / step).toInt()); val upper = min(db.size - 1, ((a + 12) / step).toInt())
                var best = lower; var energy = Double.POSITIVE_INFINITY
                for (i in lower..upper) {
                    var value = 0.0; for (k in max(0, i - 2)..min(db.size - 1, i + 2)) value += db[k]
                    if (value < energy) { energy = value; best = i }
                }
                val b = max(a + 8, best * step)
                bounded.add(a..b); a = b
            }
            if (island.endInclusive > a) bounded.add(a..island.endInclusive)
        }
        val result = ArrayList<SubtitleSpeechChunk>()
        for (island in bounded) {
            val previous = result.lastOrNull()
            if (previous != null && island.endInclusive - previous.range.start <= 12 &&
                (island.start - previous.range.endInclusive < 0.7 || (island.start - previous.range.endInclusive < 1.3 && previous.range.endInclusive - previous.range.start < 3)))
                result[result.size - 1] = SubtitleSpeechChunk(previous.range.start..island.endInclusive, previous.speech + listOf(island))
            else result.add(SubtitleSpeechChunk(island, listOf(island)))
        }
        return result
    }
}

/// Subtitles on a timeline: captions from each sound's words, and the edits.
object SubtitleEditing {
    fun cues(timeline: TrackTimeline): List<SubtitleCue> {
        if (timeline.subtitles == null) return emptyList()
        val cues = ArrayList<SubtitleCue>()
        for (item in timeline.audio) {
            if (item.muted) continue
            var offset = 0.0
            for ((runIndex, run) in item.mapping.runs.withIndex()) {
                val runOffset = offset; offset += run.duration.seconds
                if (run.silent || run.isHold) continue
                val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                val words = item.subtitleWords.filter { it.end > run.sourceStart && it.start < run.sourceEnd }.sortedBy { it.start }
                val group = ArrayList<SubtitleWord>()
                fun emit() {
                    val first = group.firstOrNull() ?: return; val last = group.last()
                    val start = item.start.seconds + runOffset + (max(run.sourceStart, first.start) - run.sourceStart) * scale
                    val end = item.start.seconds + runOffset + (min(run.sourceEnd, last.end) - run.sourceStart) * scale
                    if (end > start) cues.add(SubtitleCue("${item.id.text}-$runIndex-${first.id.text}", item.id, group.map { it.id }, SubtitleTiming.joined(group.map { it.text }), start, end))
                }
                for (word in words) {
                    val last = group.lastOrNull()
                    if (last != null && (last.phraseID != word.phraseID || word.start - last.end > 0.4)) { emit(); group.clear() }
                    group.add(word)
                }
                emit()
            }
        }
        return cues.sortedWith(compareBy<SubtitleCue> { it.start }.thenBy { it.id })
    }
    /// Structural sound and picture edits, not looks or gain: regrouping stays
    /// available until the timing it was transcribed against changes.
    fun timingFingerprint(timeline: TrackTimeline): String {
        fun item(id: UUID, source: UUID, start: EditTime, mapping: SourcePlaybackMap, muted: Boolean) =
            jsonObject("id" to id.text, "mapping" to ProjectCoding.encode(mapping), "muted" to muted, "source" to source.text, "start" to ProjectCoding.encode(start))
        val items = timeline.audio.map { item(it.id, it.sourceID, it.start, it.mapping, it.muted) } + timeline.video.map { item(it.id, it.content.sourceID, it.start, it.mapping, false) }
        return MessageDigest.getInstance("SHA-256").digest(JSON.write(items)).joinToString("") { String.format("%02x", it) }
    }
    fun groupingAvailable(timeline: TrackTimeline) = timeline.subtitles?.timingFingerprint == timingFingerprint(timeline)
    fun remove(timeline: TrackTimeline) { timeline.subtitles = null; timeline.audio = timeline.audio.map { it.copy(subtitleWords = emptyList()) } }
    fun regroup(timeline: TrackTimeline, count: Int) {
        if (!groupingAvailable(timeline) || count !in 1..16) return
        timeline.subtitles = timeline.subtitles?.copy(maxWords = count)
        timeline.audio = timeline.audio.map { it.copy(subtitleWords = SubtitleTiming.group(it.subtitleWords, count)) }
    }
    fun correct(timeline: TrackTimeline, cue: SubtitleCue, text: String) {
        val ids = cue.wordIDs.toSet()
        timeline.audio = timeline.audio.map { item ->
            if (item.id != cue.audioID) item
            else item.copy(subtitleWords = (item.subtitleWords.filter { it.id !in ids } + SubtitleTiming.corrected(item.subtitleWords.filter { it.id in ids }, text)).sortedBy { it.start })
        }
    }
    /// Attach estimated project times to the strongest eligible speech track.
    /// Overlapping speakers are best effort; never duplicate a word onto both
    /// tracks. Each source time remains independent of project moves.
    fun install(timeline: TrackTimeline, words: List<SubtitleWord>, range: ClosedFloatingPointRange<Double>, language: String) {
        val previous = cues(timeline)
        var settings = (timeline.subtitles ?: SubtitleSettings()).copy(language = language)
        val coversExisting = previous.all { it.start >= range.start - 0.001 && it.end <= range.endInclusive + 0.001 }
        if (timeline.subtitles == null || groupingAvailable(timeline) || coversExisting) settings = settings.copy(timingFingerprint = timingFingerprint(timeline))
        timeline.subtitles = settings
        timeline.audio = timeline.audio.map { item ->
            item.copy(subtitleWords = item.subtitleWords.filter { word ->
                val local = item.mapping.local((word.start + word.end) / 2) ?: return@filter false
                (item.start.seconds + local.seconds) !in range
            })
        }
        val newIDs = words.map { it.id }.toSet()
        val audio = timeline.audio.toMutableList()
        for (word in words) {
            val midpoint = EditTime.seconds((word.start + word.end) / 2)
            val candidates = audio.indices.filter { !audio[it].muted && midpoint in audio[it].interval }.sortedWith { x, y ->
                if (audio[x].volumeDB == audio[y].volumeDB) audio[x].id.text.compareTo(audio[y].id.text) else audio[y].volumeDB.compareTo(audio[x].volumeDB)
            }
            for (i in candidates) {
                val item = audio[i]; var cursor = item.start.seconds; var attached = false
                for (run in item.mapping.runs) {
                    val at = cursor; cursor += run.duration.seconds
                    if (run.silent || run.isHold || midpoint.seconds < at || midpoint.seconds >= at + run.duration.seconds) continue
                    val a = max(at, word.start); val b = min(at + run.duration.seconds, word.end)
                    val value = word.copy(start = run.source(EditTime.seconds(a - at)), end = run.source(EditTime.seconds(b - at)))
                    if (value.end > value.start) { audio[i] = item.copy(subtitleWords = item.subtitleWords + value); attached = true }
                    break
                }
                if (attached) break
            }
        }
        // Re-transcribing a selection must not regroup corrected captions outside it.
        timeline.audio = audio.map { item ->
            val preserved = item.subtitleWords.filter { it.id !in newIDs }
            val added = SubtitleTiming.group(item.subtitleWords.filter { it.id in newIDs }, settings.maxWords)
            item.copy(subtitleWords = (preserved + added).sortedBy { it.start })
        }
    }
}
