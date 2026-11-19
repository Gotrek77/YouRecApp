package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/// The edit clock is exact for 48 kHz sound and 24/25/30/50/60 fps,
/// including 24000/1001, 30000/1001 and 60000/1001. Source PTS remains separate.
@JvmInline
value class EditTime private constructor(val ticks: Long) : Comparable<EditTime> {
    companion object {
        const val scale = 240_000L
        val zero = EditTime(0)
        fun ticks(value: Long) = EditTime(value)
        fun seconds(value: Double) = EditTime(if (value.isFinite()) roundAway(value.coerceIn(-604_800.0, 604_800.0) * scale) else 0)
        // The same rounding as the iPhone app, so both agree on every boundary.
        private fun roundAway(value: Double): Long = if (value < 0) -floor(-value + 0.5).toLong() else floor(value + 0.5).toLong()
    }
    val seconds: Double get() = ticks.toDouble() / scale
    override fun compareTo(other: EditTime) = ticks.compareTo(other.ticks)
    operator fun plus(other: EditTime) = EditTime(ticks + other.ticks)
    operator fun minus(other: EditTime) = EditTime(ticks - other.ticks)
}
fun maxOf(a: EditTime, b: EditTime) = if (a >= b) a else b
fun minOf(a: EditTime, b: EditTime) = if (a <= b) a else b

data class EditInterval(val start: EditTime, val end: EditTime) {
    constructor(start: Double, end: Double) : this(EditTime.seconds(start), EditTime.seconds(end))
    val duration: EditTime get() = end - start
    val isEmpty: Boolean get() = end <= start
    operator fun contains(time: EditTime) = start <= time && time < end
    fun intersection(other: EditInterval): EditInterval? {
        val result = EditInterval(maxOf(start, other.start), minOf(end, other.end))
        return if (result.isEmpty) null else result
    }
}

/// Each run maps an exact local interval to original source timestamps. A hold
/// has equal source endpoints and is silent; no duplicate dialogue is inserted.
data class PlaybackRun(val duration: EditTime, val sourceStart: Double, val sourceEnd: Double, val silent: Boolean = false) {
    val isHold: Boolean get() = sourceStart == sourceEnd
    fun source(offset: EditTime): Double {
        if (duration <= EditTime.zero) return sourceStart
        return sourceStart + (sourceEnd - sourceStart) * (offset.seconds / duration.seconds).coerceIn(0.0, 1.0)
    }
}
data class SourcePlaybackMap(val runs: List<PlaybackRun>) {
    constructor(clip: TimelineClip) : this(listOf(PlaybackRun(EditTime.seconds(clip.duration), clip.start, clip.end)).filter { it.duration > EditTime.zero })
    val duration: EditTime get() = EditTime.ticks(runs.sumOf { it.duration.ticks })
    fun source(local: EditTime): Double? {
        var cursor = EditTime.zero
        for (run in runs) {
            if (local < cursor + run.duration) return run.source(maxOf(EditTime.zero, local - cursor))
            cursor += run.duration
        }
        return if (local == duration) runs.lastOrNull()?.sourceEnd else null
    }
    fun local(sourceTime: Double): EditTime? {
        var cursor = EditTime.zero
        for (run in runs) {
            if (!run.isHold && sourceTime >= run.sourceStart && sourceTime < run.sourceEnd) {
                return cursor + EditTime.seconds(run.duration.seconds * (sourceTime - run.sourceStart) / (run.sourceEnd - run.sourceStart))
            }
            cursor += run.duration
        }
        return null
    }
    fun sliced(interval: EditInterval): SourcePlaybackMap {
        var cursor = EditTime.zero
        val result = ArrayList<PlaybackRun>()
        for (run in runs) {
            val end = cursor + run.duration
            EditInterval(cursor, end).intersection(interval)?.let { part ->
                result.add(PlaybackRun(part.duration, run.source(part.start - cursor), run.source(part.end - cursor), run.silent))
            }
            cursor = end
        }
        return SourcePlaybackMap(result)
    }
}

data class VideoTimelineItem(val occurrenceID: UUID, val start: EditTime, val content: TimelineClip, val layer: Int = 0,
                             val localOrigin: EditTime = EditTime.zero, val mapping: SourcePlaybackMap = SourcePlaybackMap(content)) {
    val id: UUID get() = content.id
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)
}

/// A split inside a fade inherits its slope and its level at the cut.
data class AudioEdgeFade(val seconds: Double, val elapsed: Double = 0.0) {
    val valid: Boolean get() = seconds > 0 && seconds <= 604800 && elapsed >= 0 && elapsed < seconds
    fun remaining(length: Double) = minOf(length, seconds - elapsed)
    fun trimming(amount: EditTime): AudioEdgeFade? {
        val part = copy(elapsed = (EditTime.seconds(elapsed) + amount).seconds)
        return if (part.elapsed < seconds) part else null
    }
    fun gain(distanceFromEdge: Double) = ((elapsed + distanceFromEdge) / seconds).coerceIn(0.0, 1.0)
}
enum class AudioFadeEdge(val title: String) {
    FadeIn("Fade in"), FadeOut("Fade out");
    // Select just one item if several share an edge. The lowest timeline
    // audio number wins the tie, and is named alongside the control.
    fun index(audio: List<AudioTimelineItem>, selected: Set<UUID>): Int? {
        var best: Int? = null
        for (i in audio.indices) {
            if (audio[i].id !in selected) continue
            val current = best
            if (current == null || (if (this == FadeIn) audio[i].start < audio[current].start else audio[i].end > audio[current].end)) best = i
        }
        return best
    }
}

data class AudioTimelineItem(val id: UUID = UUID.randomUUID(), val occurrenceID: UUID, val sourceID: UUID, val start: EditTime,
                             val mapping: SourcePlaybackMap, val localOrigin: EditTime = EditTime.zero, val followsVideo: UUID? = null,
                             val volumeDB: Double = 0.0, val muted: Boolean = false, val fadeIn: AudioEdgeFade? = null, val fadeOut: AudioEdgeFade? = null) {
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)
    val gain: Double get() = if (muted) 0.0 else 10.0.pow(volumeDB / 20)
    fun fadeSeconds(edge: AudioFadeEdge) = (if (edge == AudioFadeEdge.FadeIn) fadeIn else fadeOut)?.remaining(mapping.duration.seconds) ?: 0.0
    fun settingFade(edge: AudioFadeEdge, seconds: Double): AudioTimelineItem {
        val length = minOf(mapping.duration, maxOf(EditTime.zero, EditTime.seconds(seconds))).seconds
        val fade = if (length > 0) AudioEdgeFade(length) else null
        return if (edge == AudioFadeEdge.FadeIn) copy(fadeIn = fade) else copy(fadeOut = fade)
    }
    fun sliced(part: EditInterval, id: UUID) = copy(id = id, start = start + part.start, localOrigin = localOrigin + part.start,
        mapping = mapping.sliced(part), fadeIn = fadeIn?.trimming(part.start), fadeOut = fadeOut?.trimming(mapping.duration - part.end))
    /// The level at one local time, including both edge fades.
    fun gain(local: Double): Double {
        var level = gain
        fadeIn?.let { level *= it.gain(local) }
        fadeOut?.let { level *= it.gain(mapping.duration.seconds - local) }
        return level
    }
}

data class TimelineLink(val id: UUID = UUID.randomUUID(), val members: Set<UUID>)
data class TimelineSelection(val video: Set<UUID> = emptySet(), val audio: Set<UUID> = emptySet(), val range: EditInterval? = null) {
    val ids: Set<UUID> get() = video + audio
    val isEmpty: Boolean get() = video.isEmpty() && audio.isEmpty()
    val title: String get() = if (video.isEmpty()) "Audio" else if (audio.isEmpty()) "Video" else "Video + audio"
    val countTitle: String get() = listOfNotNull(
        if (video.isEmpty()) null else "${video.size} video${if (video.size == 1) "" else "s"}",
        if (audio.isEmpty()) null else "${audio.size} audio part${if (audio.size == 1) "" else "s"}").joinToString(" + ")
}

enum class TimelineMoveScope { Selected, Left, Right, Both }
data class TimelineMoveChoices(val selected: TimelineSelection = TimelineSelection(), val left: TimelineSelection = TimelineSelection(),
                               val right: TimelineSelection = TimelineSelection(), val both: TimelineSelection = TimelineSelection()) {
    operator fun get(scope: TimelineMoveScope) = when (scope) {
        TimelineMoveScope.Selected -> selected; TimelineMoveScope.Left -> left
        TimelineMoveScope.Right -> right; TimelineMoveScope.Both -> both
    }
    fun available(scope: TimelineMoveScope) = when (scope) {
        TimelineMoveScope.Selected -> true
        TimelineMoveScope.Left -> left != selected
        TimelineMoveScope.Right -> right != selected
        TimelineMoveScope.Both -> both != left && both != right
    }
}

class TimelineLinkError(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        TooFew("Tap a part to select it, then long-press other video or audio parts to add them. Long-press again to remove a part from the selection."),
        NonContiguous("Non-contiguous parts selected. Each video or audio sequence must meet edge to edge, without gaps or overlaps. Move the parts together with Snap, then try Link again."),
        NoIntersection("The selected video and audio do not overlap in time. Move them to a shared interval before linking."),
        ConfirmationRequired("Video and audio cover different times. Link only their intersection?")
    }
}
data class TimelineLinkPlan(val selection: TimelineSelection, val video: EditInterval?, val audio: EditInterval?) {
    val needsIntersection: Boolean get() = video != null && audio != null && video != audio
    val intersection: EditInterval? get() = if (video != null && audio != null) video.intersection(audio) else null
}

data class TimelineMoveSnap(val target: EditTime, val edge: EditTime)
data class TimelineMoveResult(val delta: EditTime, val snap: TimelineMoveSnap? = null)

sealed class BookmarkAnchor {
    data class Source(val occurrence: UUID, val source: UUID, val time: Double) : BookmarkAnchor()
    data class Local(val occurrence: UUID, val offset: EditTime) : BookmarkAnchor()
    data class Project(val time: EditTime) : BookmarkAnchor()
}
data class TimelineBookmark(val id: UUID = UUID.randomUUID(), val label: String = "", val anchor: BookmarkAnchor)
data class ResolvedBookmark(val bookmark: TimelineBookmark, val time: EditTime) { val id: UUID get() = bookmark.id }

data class CompiledTimelineInterval(val interval: EditInterval, val video: List<UUID>, val audio: List<UUID>)

/// Canonical independent-item edit. There is no inferred magnetic track: ripple
/// is an explicit edit command and a link records selection membership only.
/// Items are immutable values; a timeline is copied before it is changed.
class TrackTimeline(var video: List<VideoTimelineItem> = emptyList(), var audio: List<AudioTimelineItem> = emptyList(),
                    var links: List<TimelineLink> = emptyList(), var bookmarks: List<TimelineBookmark> = emptyList()) {
    // Transient provenance follows tool drafts; it is not rendering data.
    var captureBase: Int? = null

    override fun equals(other: Any?) = other is TrackTimeline && video == other.video && audio == other.audio && links == other.links && bookmarks == other.bookmarks
    override fun hashCode() = video.hashCode() * 31 + audio.hashCode()
    fun copy() = TrackTimeline(video, audio, links, bookmarks).also { it.captureBase = captureBase }

    val duration: EditTime get() = maxOf(video.maxOfOrNull { it.end } ?: EditTime.zero, audio.maxOfOrNull { it.end } ?: EditTime.zero)
    val allIDs: Set<UUID> get() = (video.map { it.id } + audio.map { it.id }).toSet()

    companion object {
        /// Capture IDs give deterministic counterpart IDs, so every Undo snapshot agrees.
        fun counterpart(id: UUID) = UUID(id.mostSignificantBits, id.leastSignificantBits xor 0x80)

        fun of(clips: List<TimelineClip>, pictureOnlySources: Set<UUID> = emptySet()): TrackTimeline {
            val video = ArrayList<VideoTimelineItem>(); val audio = ArrayList<AudioTimelineItem>(); val links = ArrayList<TimelineLink>()
            var position = 0.0
            for ((index, clip) in clips.withIndex()) {
                val start = EditTime.seconds(position)
                position += clip.duration
                video.add(VideoTimelineItem(clip.id, start, clip, index))
                if (clip.sourceID in pictureOnlySources) continue
                val audioID = counterpart(clip.id)
                audio.add(AudioTimelineItem(audioID, clip.id, clip.sourceID, start, SourcePlaybackMap(clip), followsVideo = clip.id))
                links.add(TimelineLink(clip.id, setOf(clip.id, audioID)))
            }
            return TrackTimeline(video, audio, links)
        }

        /// A cut can coincide across several layers. Match both halves of the
        /// same occurrence before assigning the remaining neighbors in lane order.
        fun neighbors(outgoing: List<Pair<UUID, UUID>>, incoming: List<Pair<UUID, UUID>>): List<Pair<UUID, UUID>> {
            val available = incoming.toMutableList(); val unmatched = ArrayList<Pair<UUID, UUID>>(); val pairs = ArrayList<Pair<UUID, UUID>>()
            for (part in outgoing) {
                val i = available.indexOfFirst { it.second == part.second }
                if (i >= 0) pairs.add(part.first to available.removeAt(i).first) else unmatched.add(part)
            }
            for ((a, b) in unmatched.zip(available)) pairs.add(a.first to b.first)
            return pairs
        }

        val backToFront = Comparator<VideoTimelineItem> { a, b -> if (a.layer == b.layer) a.id.text.compareTo(b.id.text) else a.layer.compareTo(b.layer) }

        fun union(ranges: List<EditInterval>): List<EditInterval> {
            val result = ArrayList<EditInterval>()
            for (range in ranges.sortedBy { it.start }) {
                if (range.isEmpty) continue
                val last = result.lastOrNull()
                if (last != null && range.start <= last.end) result[result.size - 1] = EditInterval(last.start, maxOf(last.end, range.end))
                else result.add(range)
            }
            return result
        }
    }

    fun expanded(selection: TimelineSelection): TimelineSelection {
        val ids = selection.ids
        var pictures = selection.video; var sounds = selection.audio
        for (group in links) {
            if (group.members.none { it in ids }) continue
            pictures = pictures + video.filter { it.id in group.members }.map { it.id }
            sounds = sounds + audio.filter { it.id in group.members }.map { it.id }
        }
        return selection.copy(video = pictures, audio = sounds)
    }
    fun selection(of: UUID, linked: Boolean = true, range: EditInterval? = null): TimelineSelection {
        val members = if (linked) links.firstOrNull { of in it.members }?.members ?: setOf(of) else setOf(of)
        return TimelineSelection(video.filter { it.id in members }.map { it.id }.toSet(), audio.filter { it.id in members }.map { it.id }.toSet(), range)
    }
    fun items(usingSources: Set<UUID>) = TimelineSelection(video.filter { it.content.sourceID in usingSources }.map { it.id }.toSet(),
                                                           audio.filter { it.sourceID in usingSources }.map { it.id }.toSet())
    fun entireSelection(range: EditInterval? = null) = TimelineSelection(video.map { it.id }.toSet(), audio.map { it.id }.toSet(), range)
    fun selection(ids: Set<UUID>) = expanded(TimelineSelection(ids.intersect(video.map { it.id }.toSet()), ids.intersect(audio.map { it.id }.toSet())))
    fun toggling(id: UUID, selected: Set<UUID>): Set<UUID> {
        val group = selection(id).ids
        return if (selected.containsAll(group)) selected - group else selected + group
    }
    fun isSingleLink(selection: TimelineSelection) = links.any { it.members == selection.ids }
    fun interval(selection: TimelineSelection): EditInterval? {
        selection.range?.let { return it }
        val intervals = video.filter { it.id in selection.video }.map { it.interval } + audio.filter { it.id in selection.audio }.map { it.interval }
        if (intervals.isEmpty()) return null
        return EditInterval(intervals.minOf { it.start }, intervals.maxOf { it.end })
    }
    fun unlink(selection: TimelineSelection) {
        val ids = selection.ids
        val members = links.filter { group -> group.members.any { it in ids } }.flatMap { it.members }.toSet()
        audio = audio.map { if (it.id in members) it.copy(followsVideo = null) else it }
        links = links.filter { group -> group.members.none { it in ids } }
    }
    /// Establish horizontal continuity first, then compare the two resulting
    /// spans. Different source files remain separate render items; the
    /// link glues their selection and movement without flattening any media.
    fun linkPlan(requested: TimelineSelection): TimelineLinkPlan {
        val selection = expanded(requested)
        if (selection.ids.size < 2 || selection.range != null) throw TimelineLinkError(TimelineLinkError.Kind.TooFew)
        fun chain(parts: List<EditInterval>): EditInterval? {
            val sorted = parts.sortedBy { it.start }
            if (sorted.isEmpty()) return null
            for ((a, b) in sorted.zip(sorted.drop(1))) if (a.end != b.start) throw TimelineLinkError(TimelineLinkError.Kind.NonContiguous)
            return EditInterval(sorted.first().start, sorted.maxOf { it.end })
        }
        val plan = TimelineLinkPlan(selection, chain(video.filter { it.id in selection.video }.map { it.interval }),
                                    chain(audio.filter { it.id in selection.audio }.map { it.interval }))
        if (plan.needsIntersection && plan.intersection == null) throw TimelineLinkError(TimelineLinkError.Kind.NoIntersection)
        return plan
    }
    /// Pure draft: cancellation cannot split footage or create a history entry.
    fun joining(selection: TimelineSelection, intersectionOnly: Boolean = false): Pair<TrackTimeline, TimelineSelection> {
        val plan = linkPlan(selection)
        if (plan.needsIntersection && !intersectionOnly) throw TimelineLinkError(TimelineLinkError.Kind.ConfirmationRequired)
        val target = if (plan.needsIntersection) plan.selection.copy(range = plan.intersection) else plan.selection
        val (timeline, scope) = scoped(target)
        val members = scope.ids
        timeline.links = timeline.links.mapNotNull { group ->
            val remaining = group.members - members
            if (remaining.size >= 2) group.copy(members = remaining) else null
        } + TimelineLink(members = members)
        // Preserve existing sample-aligned followers. Re-linking a matching
        // original pair also restores its source-time relationship.
        timeline.audio = timeline.audio.map { sound ->
            if (sound.id !in scope.audio || sound.followsVideo != null) return@map sound
            val picture = timeline.video.firstOrNull { it.id in scope.video && it.occurrenceID == sound.occurrenceID
                && it.content.sourceID == sound.sourceID && it.start == sound.start && it.mapping == sound.mapping }
            if (picture != null) sound.copy(followsVideo = picture.id) else sound
        }
        timeline.pruneRelations()
        return timeline to scope
    }

    /// Resolve companions on the scoped, undragged timeline once per Move session.
    /// Picture cuts and independent sound cuts at the same time are different joins.
    fun moveChoices(selected: TimelineSelection): TimelineMoveChoices {
        val seed = expanded(selected)
        val pictures = video.map { it.id }.toSet(); val sounds = audio.map { it.id }.toSet()
        val linkedToPicture = links.filter { group -> group.members.any { it in pictures } }.flatMap { it.members }.toSet()
        val groupByMember = HashMap<UUID, TimelineLink>()
        for (group in links) for (member in group.members) groupByMember[member] = group
        val previous = HashMap<UUID, UUID>(); val next = HashMap<UUID, UUID>()
        val starts = HashMap<Pair<Boolean, EditTime>, MutableList<Pair<UUID, UUID>>>(); val ends = HashMap<Pair<Boolean, EditTime>, MutableList<Pair<UUID, UUID>>>()
        for (picture in video.sortedWith(backToFront)) {
            starts.getOrPut(false to picture.start) { ArrayList() }.add(picture.id to picture.occurrenceID)
            ends.getOrPut(false to picture.end) { ArrayList() }.add(picture.id to picture.occurrenceID)
        }
        // Independent sound has its own joins. A soundtrack can span many picture gaps.
        for (sound in audio) {
            if (sound.id in linkedToPicture) continue
            starts.getOrPut(true to sound.start) { ArrayList() }.add(sound.id to sound.occurrenceID)
            ends.getOrPut(true to sound.end) { ArrayList() }.add(sound.id to sound.occurrenceID)
        }
        for ((cut, outgoing) in ends) for ((a, b) in neighbors(outgoing, starts[cut] ?: emptyList())) { previous[b] = a; next[a] = b }
        fun following(neighbors: Map<UUID, UUID>): TimelineSelection {
            val members = seed.ids.toMutableSet(); val pending = ArrayList(members); val visitedLinks = HashSet<UUID>()
            while (pending.isNotEmpty()) {
                val id = pending.removeAt(pending.size - 1)
                val group = groupByMember[id]
                if (group != null && visitedLinks.add(group.id)) for (member in group.members) if (members.add(member)) pending.add(member)
                neighbors[id]?.let { if (members.add(it)) pending.add(it) }
            }
            return TimelineSelection(members.intersect(pictures), members.intersect(sounds))
        }
        val left = following(previous); val right = following(next)
        return TimelineMoveChoices(seed, left, right, TimelineSelection(left.video + right.video, left.audio + right.audio))
    }

    /// Shift all selected members by one clamped offset; never clamp individually
    /// because doing so would destroy their existing synchronization/offset.
    fun move(requested: TimelineSelection, delta: EditTime) {
        val selection = expanded(requested)
        val range = interval(TimelineSelection(selection.video, selection.audio)) ?: return
        val amount = maxOf(EditTime.zero - range.start, delta)
        if (amount == EditTime.zero) return
        val previous = video
        video = video.map { if (it.id in selection.video) it.copy(start = it.start + amount) else it }
        promoteNewOverlaps(selection.video, previous)
        audio = audio.map { if (it.id in selection.audio) it.copy(start = it.start + amount) else it }
        pruneRelations()
    }
    /// A newly dropped overlay comes forward. Adjusting an existing overlap
    /// must not silently swap the picture merely to fix its timing.
    private fun promoteNewOverlaps(selected: Set<UUID>, previous: List<VideoTimelineItem>) {
        val old = previous.associate { it.id to it.interval }
        val stationary = video.filter { it.id !in selected }
        if (!video.any { item -> item.id in selected && stationary.any { other ->
                item.interval.intersection(other.interval) != null && old[item.id]?.intersection(old[other.id] ?: other.interval) == null } }) return
        val front = (video.maxOfOrNull { it.layer } ?: 0) + 1
        val rank = video.filter { it.id in selected }.sortedWith(backToFront).withIndex().associate { it.value.id to it.index }
        video = video.map { item -> rank[item.id]?.let { item.copy(layer = front + it) } ?: item }
    }
    fun setVolume(db: Double, muted: Boolean, selection: TimelineSelection) {
        audio = audio.map { if (it.id in selection.audio) it.copy(volumeDB = db.coerceIn(-60.0, 12.0), muted = muted) else it }
    }
    fun setFade(edge: AudioFadeEdge, seconds: Double, selection: TimelineSelection) {
        val index = edge.index(audio, selection.audio) ?: return
        audio = audio.mapIndexed { i, item -> if (i == index) item.settingFade(edge, seconds) else item }
    }

    /// Return the new position of the content under the playhead. Use the same
    /// resolved shifts as deletion (not the selected duration: overlaps and
    /// detached tracks may leave some or all of that time in place).
    fun remove(selection: TimelineSelection, preserving: EditTime? = null): EditTime? {
        if (selection.isEmpty) return preserving
        val oldVideo = video; val oldAudio = audio; val oldLinks = links; val oldDuration = duration
        val ids = selection.ids
        // Only complete linked groups qualify. A range is split before this call.
        val complete = oldLinks.filter { group -> group.members.any { it in selection.video } && group.members.any { it in selection.audio } && ids.containsAll(group.members) }
        val removedVideo = complete.flatMap { it.members }.toSet().intersect(selection.video)
        val closing = union(oldVideo.filter { it.id in removedVideo }.map { it.interval })
        val audioIDs = audio.map { it.id }.toSet(); val videoIDs = video.map { it.id }.toSet()
        val downstreamGroups = oldLinks.filter { group -> group.members.none { it in ids } && group.members.any { it in audioIDs } && group.members.any { it in videoIDs } }
        val downstreamIDs = downstreamGroups.flatMap { it.members }.toSet()
        val remainingVideo = oldVideo.filter { it.id !in selection.video && it.id in downstreamIDs }
        // P and N are remaining linked picture edges. This closes only the newly vacated space.
        val closedGaps = closing.mapNotNull { range ->
            val before = remainingVideo.filter { it.start < range.start }.maxOfOrNull { it.end } ?: range.start
            val after = remainingVideo.filter { it.start >= range.start }.minOfOrNull { it.start } ?: range.end
            val start = maxOf(range.start, before); val end = minOf(range.end, after)
            if (end > start) EditInterval(start, end) else null
        }
        video = video.filter { it.id !in selection.video }
        audio = audio.filter { it.id !in selection.audio }
        for (group in downstreamGroups) {
            val anchor = oldVideo.filter { it.id in group.members }.minOfOrNull { it.start }
                ?: audio.filter { it.id in group.members }.minOfOrNull { it.start } ?: EditTime.zero
            val amount = EditTime.ticks(closedGaps.filter { it.start <= anchor }.sumOf { it.duration.ticks })
            video = video.map { if (it.id in group.members) it.copy(start = it.start - amount) else it }
            audio = audio.map { if (it.id in group.members) it.copy(start = it.start - amount) else it }
        }
        pruneRelations()
        val position = preserving ?: return null
        val removedTime = EditTime.ticks(closedGaps.sumOf { minOf(it.duration, maxOf(EditTime.zero, position - it.start)).ticks })
        if (closedGaps.any { position in it }) return minOf(duration, maxOf(EditTime.zero, position - removedTime))
        fun holds(interval: EditInterval) = position in interval || (position == oldDuration && interval.end == position)
        // Preserve the visible occurrence, even if an unrelated layer rippled.
        // Audio is the anchor only when there was no picture here to begin with.
        var mapped: EditTime? = null
        val picture = oldVideo.filter { holds(it.interval) }.maxByOrNull { it.layer }
        if (picture != null) {
            video.firstOrNull { it.id == picture.id }?.let { mapped = position + it.start - picture.start }
        } else {
            val sound = oldAudio.firstOrNull { holds(it.interval) }
            if (sound != null) audio.firstOrNull { it.id == sound.id }?.let { mapped = position + it.start - sound.start }
        }
        // Inside deleted footage, land at its resulting cut. With no ripple
        // (unlinked picture/sound), the existing gap and clock stay unchanged.
        return minOf(duration, maxOf(EditTime.zero, mapped ?: (position - removedTime)))
    }
    fun pruneRelations() {
        val ids = allIDs
        links = links.mapNotNull { link -> val members = link.members.intersect(ids); if (members.size >= 2) link.copy(members = members) else null }
        audio = audio.map { sound ->
            val id = sound.followsVideo
            if (id != null && !links.any { id in it.members && sound.id in it.members }) sound.copy(followsVideo = null) else sound
        }
    }

    val resolvedBookmarks: List<ResolvedBookmark> get() = bookmarks.mapNotNull { bookmark ->
        val time: EditTime? = when (val anchor = bookmark.anchor) {
            is BookmarkAnchor.Project -> anchor.time
            is BookmarkAnchor.Source ->
                video.filter { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source }.firstNotNullOfOrNull { item -> item.mapping.local(anchor.time)?.let { item.start + it } }
                    ?: audio.filter { it.occurrenceID == anchor.occurrence && it.sourceID == anchor.source }.firstNotNullOfOrNull { item -> item.mapping.local(anchor.time)?.let { item.start + it } }
            is BookmarkAnchor.Local ->
                video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }?.let { it.start + anchor.offset - it.localOrigin }
        }
        time?.let { ResolvedBookmark(bookmark, it) }
    }.sortedWith { a, b -> if (a.time == b.time) a.id.text.compareTo(b.id.text) else a.time.compareTo(b.time) }

    /// Every interval in which the same pictures and sounds are active.
    fun compile(): List<CompiledTimelineInterval> {
        val edges = sortedSetOf(EditTime.zero, duration)
        for (item in video) { edges.add(item.start); edges.add(item.end) }
        for (item in audio) { edges.add(item.start); edges.add(item.end) }
        val marks = edges.filter { it >= EditTime.zero && it <= duration }
        return marks.zip(marks.drop(1)).mapNotNull { (a, b) ->
            if (b <= a) null else CompiledTimelineInterval(EditInterval(a, b),
                video.filter { a in it.interval }.sortedWith(backToFront).map { it.id }, audio.filter { a in it.interval }.map { it.id })
        }
    }

    /// Split identities once in the draft; dragging or changing inspector values
    /// must reuse that draft instead of regenerating fragments every frame.
    fun split(time: EditTime, requested: TimelineSelection): Map<UUID, UUID> {
        val selection = expanded(requested)
        val children = HashMap<UUID, UUID>()
        val pictures = ArrayList<VideoTimelineItem>()
        for (item in video) {
            if (item.id !in selection.video || time <= item.start || time >= item.end) { pictures.add(item); continue }
            val local = time - item.start
            val source = item.mapping.source(local)
            if (local.ticks < 1 || (item.end - time).ticks < 1 || source == null) { pictures.add(item); continue }
            val left = item.copy(content = item.content.copy(end = source), mapping = item.mapping.sliced(EditInterval(EditTime.zero, local)))
            val right = item.copy(content = item.content.copy(id = UUID.randomUUID(), start = source), start = time,
                                  localOrigin = item.localOrigin + local, mapping = item.mapping.sliced(EditInterval(local, item.mapping.duration)))
            children[item.id] = right.id
            pictures.add(left); pictures.add(right)
        }
        video = pictures
        val sounds = ArrayList<AudioTimelineItem>()
        for (item in audio) {
            if (item.id !in selection.audio || time <= item.start || time >= item.end) { sounds.add(item); continue }
            val local = time - item.start; val newID = UUID.randomUUID()
            sounds.add(item.sliced(EditInterval(EditTime.zero, local), item.id))
            sounds.add(item.sliced(EditInterval(local, item.mapping.duration), newID))
            children[item.id] = newID
        }
        audio = sounds.map { sound ->
            val child = sound.followsVideo?.let { children[it] }
            if (child != null && sound.start >= time) sound.copy(followsVideo = child) else sound
        }
        val ids = selection.ids
        val newLinks = ArrayList<TimelineLink>()
        for (group in links) {
            // Splitting a different lane must not dissolve a horizontal join.
            if (group.members.none { it in ids }) { newLinks.add(group); continue }
            val left = HashSet<UUID>(); val right = HashSet<UUID>()
            for (id in group.members) {
                val child = children[id]
                if (child != null) { left.add(id); right.add(child) } else {
                    val start = video.firstOrNull { it.id == id }?.start ?: audio.firstOrNull { it.id == id }?.start ?: EditTime.zero
                    if (start >= time) right.add(id) else left.add(id)
                }
            }
            if (left.size >= 2) newLinks.add(TimelineLink(group.id, left))
            if (right.size >= 2) newLinks.add(TimelineLink(if (left.isEmpty()) group.id else UUID.randomUUID(), right))
        }
        links = newLinks
        pruneRelations()
        return children
    }
    /// Cut a marked range out of its items, and select exactly the pieces inside it.
    fun scoped(requested: TimelineSelection): Pair<TrackTimeline, TimelineSelection> {
        val selection = expanded(requested)
        val range = selection.range ?: return copy() to selection
        val result = copy()
        var pictures = selection.video; var sounds = selection.audio
        for (edge in listOf(range.end, range.start)) {
            val children = result.split(edge, TimelineSelection(pictures, sounds))
            pictures = pictures + children.filter { it.key in pictures }.values
            sounds = sounds + children.filter { it.key in sounds }.values
        }
        return result to TimelineSelection(
            result.video.filter { it.id in pictures && it.start >= range.start && it.end <= range.end }.map { it.id }.toSet(),
            result.audio.filter { it.id in sounds && it.start >= range.start && it.end <= range.end }.map { it.id }.toSet())
    }
    fun insertTime(length: EditTime, time: EditTime, atPictureGap: Boolean = false) {
        if (length <= EditTime.zero) return
        // A soundtrack running under a picture gap continues through the edit.
        // Linked sound follows the pictures being moved, including split edits.
        split(time, if (atPictureGap) TimelineSelection(video.filter { it.start >= time }.map { it.id }.toSet()) else entireSelection())
        video = video.map { if (it.start >= time) it.copy(start = it.start + length) else it }
        audio = audio.map { if (it.start >= time) it.copy(start = it.start + length) else it }
        bookmarks = bookmarks.map { mark ->
            val anchor = mark.anchor
            if (anchor is BookmarkAnchor.Project && anchor.time >= time) mark.copy(anchor = BookmarkAnchor.Project(anchor.time + length)) else mark
        }
        pruneRelations()
    }
    fun addBookmark(time: EditTime, preferred: UUID? = null, label: String = "") {
        val active = video.filter { time in it.interval }
        val audioSelected = audio.any { it.id == preferred }
        val item = if (audioSelected) null else active.firstOrNull { it.id == preferred } ?: active.maxByOrNull { it.layer }
        val pictureTime = item?.mapping?.source(time - item.start)
        val anchor: BookmarkAnchor
        if (item != null && pictureTime != null) anchor = BookmarkAnchor.Source(item.occurrenceID, item.content.sourceID, pictureTime)
        else {
            val sound = audio.firstOrNull { it.id == preferred && time in it.interval } ?: audio.firstOrNull { time in it.interval }
            val soundTime = sound?.mapping?.source(time - sound.start)
            anchor = if (sound != null && soundTime != null) BookmarkAnchor.Source(sound.occurrenceID, sound.sourceID, soundTime) else BookmarkAnchor.Project(time)
        }
        bookmarks = bookmarks + TimelineBookmark(label = label, anchor = anchor)
    }
    /// Audio overlays do not ripple the picture or any existing sound.
    fun insertAudio(source: SourceMedia, position: EditTime): UUID {
        val item = AudioTimelineItem(occurrenceID = UUID.randomUUID(), sourceID = source.id, start = maxOf(EditTime.zero, position),
            mapping = SourcePlaybackMap(listOf(PlaybackRun(EditTime.seconds(source.duration), 0.0, source.duration))))
        audio = audio + item
        return item.id
    }
    fun includeCaptureBookmarks(source: SourceMedia) {
        val item = video.firstOrNull { it.content.sourceID == source.id } ?: return
        for (event in source.events ?: emptyList()) {
            if (event.kind != "bookmark" || event.time < 0 || event.time >= source.duration || bookmarks.any { it.id == event.id }) continue
            bookmarks = bookmarks + TimelineBookmark(event.id, anchor = BookmarkAnchor.Source(item.occurrenceID, source.id, event.time))
        }
    }

    fun validate(sources: List<SourceMedia>) {
        fun require(ok: Boolean) { if (!ok) throw ProjectError.InvalidDocument() }
        val ids = video.map { it.id } + audio.map { it.id }
        require(ids.toSet().size == ids.size && duration >= EditTime.zero && duration.seconds <= 604800)
        val sourceDurations = HashMap<UUID, Double>()
        for (source in sources) sourceDurations.putIfAbsent(source.id, source.duration)
        fun valid(map: SourcePlaybackMap, sourceID: UUID): Boolean {
            val length = sourceDurations[sourceID] ?: return false
            return map.runs.isNotEmpty() && map.runs.all { it.duration > EditTime.zero && it.sourceStart.isFinite() && it.sourceEnd.isFinite()
                && it.sourceStart >= 0 && it.sourceEnd >= it.sourceStart && it.sourceEnd <= length + 0.001 }
        }
        require(video.all { it.start >= EditTime.zero && valid(it.mapping, it.content.sourceID) })
        require(audio.all { it.start >= EditTime.zero && valid(it.mapping, it.sourceID) && it.volumeDB.isFinite() && it.volumeDB in -60.0..12.0
            && it.fadeIn?.valid != false && it.fadeOut?.valid != false })
        val linked = HashSet<UUID>()
        for (link in links) {
            require(link.members.size >= 2 && ids.containsAll(link.members) && link.members.none { it in linked })
            linked.addAll(link.members)
        }
        require(links.map { it.id }.toSet().size == links.size && bookmarks.map { it.id }.toSet().size == bookmarks.size)
        for (bookmark in bookmarks) {
            require(bookmark.label.length <= 500)
            when (val anchor = bookmark.anchor) {
                is BookmarkAnchor.Source -> { val length = sourceDurations[anchor.source]; require(length != null && anchor.time.isFinite() && anchor.time >= 0 && anchor.time <= length + 0.001) }
                is BookmarkAnchor.Local -> require(anchor.offset >= EditTime.zero && anchor.offset.seconds <= 604800)
                is BookmarkAnchor.Project -> require(anchor.time >= EditTime.zero && anchor.time.seconds <= 604800)
            }
        }
        for (item in audio) {
            val videoID = item.followsVideo ?: continue
            val picture = video.firstOrNull { it.id == videoID }
            require(picture != null && links.any { videoID in it.members && item.id in it.members }
                && picture.start == item.start && picture.mapping.duration == item.mapping.duration)
        }
    }

    /// Stable time order for sequence-facing controls. Layer order remains
    /// independent: moving a picture must not change which overlap is in front.
    val chronologicalVideo: List<VideoTimelineItem> get() = video.withIndex().sortedWith { a, b ->
        if (a.value.start == b.value.start) a.index.compareTo(b.index) else a.value.start.compareTo(b.value.start) }.map { it.value }
    val projectedClips: List<TimelineClip> get() = chronologicalVideo.map { it.content }
    val videoCoverage: List<EditInterval> get() = union(video.map { it.interval })

    fun synchronizeFollowers() {
        audio = audio.map { sound ->
            val item = sound.followsVideo?.let { id -> video.firstOrNull { it.id == id } } ?: return@map sound
            sound.copy(start = item.start, mapping = item.mapping)
        }
    }

    private fun snapPoints(excluding: Set<UUID>, extra: List<Double>): List<EditTime> {
        val stationaryMarks = resolvedBookmarks.filter { resolved ->
            val owner: UUID? = when (val anchor = resolved.bookmark.anchor) {
                is BookmarkAnchor.Project -> null
                is BookmarkAnchor.Source ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
                        ?: audio.firstOrNull { it.occurrenceID == anchor.occurrence && it.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
                is BookmarkAnchor.Local ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }?.id
            }
            owner == null || owner !in excluding
        }
        val edges = video.filter { it.id !in excluding }.flatMap { listOf(it.start, it.end) } + audio.filter { it.id !in excluding }.flatMap { listOf(it.start, it.end) } +
            stationaryMarks.map { it.time } + extra.map { EditTime.seconds(it) } + EditTime.zero
        return edges.toSortedSet().toList()
    }
    fun snapTarget(time: Double, scale: Double, excluding: Set<UUID> = emptySet(), extra: List<Double> = emptyList()): Double? {
        val target = snapPoints(excluding, extra).minByOrNull { abs(it.seconds - time) } ?: return null
        return if (abs(target.seconds - time) * scale <= 8) target.seconds else null
    }
    fun snap(time: Double, scale: Double, excluding: Set<UUID> = emptySet(), extra: List<Double> = emptyList()) = snapTarget(time, scale, excluding, extra) ?: time
    fun snappedMove(selection: TimelineSelection, requested: EditTime, scale: Double, latch: TimelineMoveSnap? = null, extra: List<Double> = emptyList()): TimelineMoveResult {
        val selected = expanded(selection)
        val intervals = video.filter { it.id in selected.video }.map { it.interval } + audio.filter { it.id in selected.audio }.map { it.interval }
        if (intervals.isEmpty()) return TimelineMoveResult(EditTime.zero)
        val minimum = EditTime.zero - intervals.minOf { it.start }; val delta = maxOf(minimum, requested)
        val edges = intervals.flatMap { listOf(it.start, it.end) }.toSortedSet().toList()
        val targets = snapPoints(selected.ids, extra)
        val pixels = maxOf(0.000001, scale)
        var best: TimelineMoveSnap? = null; var distance = Double.POSITIVE_INFINITY
        for (edge in edges) {
            val position = edge + delta
            var lower = 0; var upper = targets.size
            while (lower < upper) { val mid = (lower + upper) / 2; if (targets[mid] < position) lower = mid + 1 else upper = mid }
            for (index in listOf(lower - 1, lower)) {
                val target = targets.getOrNull(index) ?: continue
                if (target - edge < minimum) continue
                val gap = abs((edge + delta - target).seconds) * pixels
                if (gap <= 10 && gap < distance) { best = TimelineMoveSnap(target, edge); distance = gap }
            }
        }
        if (latch != null && latch.edge in edges && latch.target in targets && latch.target - latch.edge >= minimum) {
            val held = abs((latch.edge + delta - latch.target).seconds) * pixels
            // Hysteresis must not pull past another cut under the finger.
            if (held <= 18 && (best == null || held <= distance + 1)) return TimelineMoveResult(latch.target - latch.edge, latch)
        }
        return TimelineMoveResult(best?.let { it.target - it.edge } ?: delta, best)
    }

    fun gap(time: EditTime): EditInterval? {
        val occupied = union(video.map { it.interval } + audio.map { it.interval })
        if (time < EditTime.zero || time >= duration || occupied.any { time in it }) return null
        val start = occupied.filter { it.end <= time }.maxOfOrNull { it.end } ?: EditTime.zero
        val end = occupied.filter { it.start > time }.minOfOrNull { it.start } ?: duration
        return if (end > start) EditInterval(start, end) else null
    }
    fun closeGap(time: EditTime) {
        val gap = gap(time) ?: return
        video = video.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it }
        audio = audio.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it }
        bookmarks = bookmarks.map { mark ->
            val anchor = mark.anchor
            if (anchor is BookmarkAnchor.Project && anchor.time >= gap.start) mark.copy(anchor = BookmarkAnchor.Project(maxOf(gap.start, anchor.time - gap.duration))) else mark
        }
        pruneRelations()
    }
    fun syncOffset(audioID: UUID): EditTime? {
        val sound = audio.firstOrNull { it.id == audioID } ?: return null
        val sourceTime = sound.mapping.runs.firstOrNull()?.sourceStart ?: return null
        val picture = video.firstOrNull { it.occurrenceID == sound.occurrenceID && it.mapping.local(sourceTime) != null } ?: return null
        return sound.start - picture.start - (picture.mapping.local(sourceTime) ?: return null)
    }
    fun restoreSync(audioID: UUID) {
        val offset = syncOffset(audioID) ?: return
        audio = audio.map { if (it.id == audioID) it.copy(start = maxOf(EditTime.zero, it.start - offset)) else it }
        pruneRelations()
    }
    /// Reveal previously trimmed original material. Other items keep their
    /// positions: revealing into an occupied interval creates an editable overlap.
    fun extend(selection: TimelineSelection, left: Boolean, requested: EditTime, sources: List<SourceMedia>) {
        val target = expanded(selection)
        var amount = requested
        fun followsTarget(sound: AudioTimelineItem) = sound.followsVideo?.let { it in target.video } ?: false
        fun available(map: SourcePlaybackMap, start: EditTime, source: UUID): EditTime {
            val run = (if (left) map.runs.firstOrNull() else map.runs.lastOrNull()) ?: return EditTime.zero
            val length = sources.firstOrNull { it.id == source }?.duration
            if (run.isHold || length == null) return EditTime.zero
            val rate = (run.sourceEnd - run.sourceStart) / run.duration.seconds
            return if (left) minOf(start, EditTime.seconds(run.sourceStart / rate)) else EditTime.seconds(maxOf(0.0, length - run.sourceEnd) / rate)
        }
        for (item in video) if (item.id in target.video) amount = minOf(amount, available(item.mapping, item.start, item.content.sourceID))
        for (item in audio) if (item.id in target.audio && !followsTarget(item)) amount = minOf(amount, available(item.mapping, item.start, item.sourceID))
        if (amount <= EditTime.zero) return
        fun revealed(map: SourcePlaybackMap): SourcePlaybackMap {
            val run = if (left) map.runs.first() else map.runs.last()
            val change = amount.seconds * (run.sourceEnd - run.sourceStart) / run.duration.seconds
            val added = PlaybackRun(amount, if (left) maxOf(0.0, run.sourceStart - change) else run.sourceEnd, if (left) run.sourceStart else run.sourceEnd + change)
            // One continuous take stays one run, exactly as before it was trimmed.
            val merged = if (left) added.copy(duration = added.duration + run.duration, sourceEnd = run.sourceEnd)
                         else run.copy(duration = run.duration + added.duration, sourceEnd = added.sourceEnd)
            return SourcePlaybackMap(if (left) listOf(merged) + map.runs.drop(1) else map.runs.dropLast(1) + merged)
        }
        val previous = video
        video = video.map { item ->
            if (item.id !in target.video) return@map item
            val map = revealed(item.mapping)
            item.copy(mapping = map, start = if (left) item.start - amount else item.start, localOrigin = if (left) item.localOrigin - amount else item.localOrigin,
                      content = item.content.copy(start = map.runs.first().sourceStart, end = map.runs.last().sourceEnd))
        }
        audio = audio.map { item ->
            if (item.id !in target.audio) return@map item
            var sound = item
            if (left) sound.fadeIn?.let { sound = sound.copy(fadeIn = it.copy(elapsed = maxOf(0.0, it.elapsed - amount.seconds))) }
            else sound.fadeOut?.let { sound = sound.copy(fadeOut = it.copy(elapsed = maxOf(0.0, it.elapsed - amount.seconds))) }
            if (!followsTarget(sound)) sound = sound.copy(mapping = revealed(sound.mapping))
            if (left) sound = sound.copy(start = sound.start - amount, localOrigin = sound.localOrigin - amount)
            sound
        }
        synchronizeFollowers()
        promoteNewOverlaps(target.video, previous)
        pruneRelations()
    }
}

/// One viewport calculation for pinch, fit, presets and hit testing. At fit,
/// both ends are visible and the playhead moves independently of the viewport.
class TimelineViewport(val width: Double, val duration: Double, val requestedScale: Double, val centersPlayhead: Boolean = true) {
    companion object { const val edgeInset = 48.0 }
    val minimumScale: Double get() = maxOf(0.000001, (width - 2 * edgeInset) / maxOf(1.0, duration))
    val scale: Double get() = maxOf(minimumScale, minOf(720.0, requestedScale))
    val fits: Boolean get() = duration * scale <= width - 2 * edgeInset + 0.5
    val inset: Double get() = if (fits || !centersPlayhead) edgeInset else width / 2
    val contentWidth: Double get() = maxOf(width, duration * scale + 2 * inset)
    fun x(time: Double, offset: Double) = time * scale + inset - offset
    fun time(x: Double, offset: Double) = ((x + offset - inset) / scale).coerceIn(0.0, duration)
}

// Shared by drawing, hit testing, accessibility and edit-invariant tests.
data class TimelineLane(val id: UUID, val interval: EditInterval, val source: UUID, val mapping: SourcePlaybackMap, val row: Int, val number: Int, val audio: Boolean)

fun timelineLanes(timeline: TrackTimeline, moving: TimelineSelection? = null): List<TimelineLane> {
    val result = ArrayList<TimelineLane>()
    val videoNumbers = timeline.video.withIndex().associate { it.value.id to it.index + 1 }
    val audioNumbers = timeline.audio.withIndex().associate { it.value.id to it.index + 1 }
    val pictures = timeline.video.sortedWith(TrackTimeline.backToFront.reversed())
    val videoLayers = timeline.video.associate { it.id to it.layer }
    val linkedLayers = HashMap<UUID, Int>()
    for (group in timeline.links) {
        val layer = group.members.mapNotNull { videoLayers[it] }.maxOrNull() ?: continue
        for (id in group.members) linkedLayers[id] = layer
    }
    val sounds = timeline.audio.sortedWith { a, b ->
        val aLayer = linkedLayers[a.id] ?: -1; val bLayer = linkedLayers[b.id] ?: -1
        if (aLayer != bLayer) bLayer.compareTo(aLayer) else if (a.start == b.start) a.id.text.compareTo(b.id.text) else a.start.compareTo(b.start)
    }
    fun pack(items: List<TimelineLane>, base: Int): Int {
        val chronological = items.sortedBy { it.interval.start }
        if (chronological.zip(chronological.drop(1)).none { (a, b) -> a.interval.end > b.interval.start }) {
            // The common case: every part can occupy the same row. Keep the
            // original draw/hit-test order without testing every preceding part.
            for (item in items) result.add(item.copy(row = base))
            return if (items.isEmpty()) 0 else 1
        }
        val rows = ArrayList<MutableList<EditInterval>>()
        val placed = ArrayList<Pair<EditInterval, Int>>()
        for (item in items) {
            // Overlays retain their front/back order; sound simply takes the first free row.
            val minimum = if (item.audio) 0 else (placed.filter { it.first.intersection(item.interval) != null }.maxOfOrNull { it.second } ?: -1) + 1
            var row = rows.indices.firstOrNull { index -> index >= minimum && rows[index].none { it.intersection(item.interval) != null } } ?: rows.size
            if (row == rows.size) rows.add(ArrayList())
            rows[row].add(item.interval); placed.add(item.interval to row)
            result.add(item.copy(row = base + row))
        }
        return rows.size
    }
    val video = pictures.map { TimelineLane(it.id, it.interval, it.content.sourceID, it.mapping, 0, videoNumbers[it.id]!!, false) }
    val audio = sounds.map { TimelineLane(it.id, it.interval, it.sourceID, it.mapping, 0, audioNumbers[it.id]!!, true) }
    var base = 0
    for (items in listOf(video, audio)) {
        if (items.firstOrNull()?.audio == true) base = maxOf(1, base)
        if (moving != null) {
            // Give the moving group dedicated rows until Done/Cancel. Both the
            // group and stationary cards keep their vertical positions as edges
            // cross. Empty space is intentional, like lifting a clip for placing.
            val ids = moving.ids
            base += pack(items.filter { it.id in ids }, base)
            base += pack(items.filter { it.id !in ids }, base)
        } else base += pack(items, base)
    }
    return result
}

/// Adjacency in the displayed lanes, after Move row packing: which cards of a
/// linked group meet edge to edge, and where picture and sound share a border.
class TimelineLinkLayout(lanes: List<TimelineLane>, links: List<TimelineLink>) {
    data class Edges(val previous: UUID? = null, val previousDuration: EditTime = EditTime.zero, val next: UUID? = null,
                     val joinsAbove: Boolean = false, val joinsBelow: Boolean = false, val memberCount: Int = 0, val outlinedGroup: Boolean = false)
    data class Bridge(val group: UUID, val upperRow: Int, val interval: EditInterval)
    val edges = HashMap<UUID, Edges>()
    val bridges = ArrayList<Bridge>()

    init {
        val groups = HashMap<UUID, UUID>()
        for (link in links) {
            if (link.members.size < 2) continue
            for (member in link.members) { groups[member] = link.id; edges[member] = Edges(memberCount = link.members.size) }
        }
        val rows = lanes.groupBy { it.row }.mapValues { row -> row.value.sortedBy { it.interval.start } }
        val horizontalGroups = HashSet<UUID>()
        for (index in rows.keys.sorted()) {
            val row = rows[index]!!
            for ((left, right) in row.zip(row.drop(1))) {
                val group = groups[left.id]
                if (left.interval.end != right.interval.start || group == null || group != groups[right.id]) continue
                horizontalGroups.add(group)
                edges[left.id] = edges[left.id]!!.copy(next = right.id)
                edges[right.id] = edges[right.id]!!.copy(previous = left.id, previousDuration = left.interval.duration)
            }
            // Only adjacent picture and sound lanes can share a physical border.
            val lower = rows[index + 1]
            if (row.firstOrNull()?.audio != false || lower == null || lower.firstOrNull()?.audio != true) continue
            var i = 0; var j = 0
            val coverage = HashMap<UUID, EditTime>()
            while (i < row.size && j < lower.size) {
                val picture = row[i]; val sound = lower[j]
                val group = groups[picture.id]
                val common = if (group != null && group == groups[sound.id]) picture.interval.intersection(sound.interval) else null
                if (group != null && common != null) {
                    coverage[picture.id] = (coverage[picture.id] ?: EditTime.zero) + common.duration
                    coverage[sound.id] = (coverage[sound.id] ?: EditTime.zero) + common.duration
                    val last = bridges.lastOrNull()
                    if (last != null && last.group == group && last.upperRow == index && last.interval.end == common.start) {
                        bridges[bridges.size - 1] = last.copy(interval = EditInterval(last.interval.start, common.end))
                    } else bridges.add(Bridge(group, index, common))
                }
                val pictureEnd = picture.interval.end; val soundEnd = sound.interval.end
                if (pictureEnd <= soundEnd) i++
                if (soundEnd <= pictureEnd) j++
            }
            for (lane in row) if (coverage[lane.id] == lane.interval.duration) edges[lane.id] = edges[lane.id]!!.copy(joinsBelow = true)
            for (lane in lower) if (coverage[lane.id] == lane.interval.duration) edges[lane.id] = edges[lane.id]!!.copy(joinsAbove = true)
        }
        for (link in links) if (link.id in horizontalGroups) for (member in link.members) edges[member]?.let { edges[member] = it.copy(outlinedGroup = true) }
    }
}
