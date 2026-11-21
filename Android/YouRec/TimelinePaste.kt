package yourec

import java.util.UUID

/// What Copy or Cut took: a small timeline of its own, starting at zero.
/// `insertsTime` is true when it carries picture with its own sound, which
/// opens time in the picture sequence instead of layering over it.
class TimelineClipboard(val timeline: TrackTimeline, val insertsTime: Boolean) {
    val isEmpty: Boolean get() = timeline.video.isEmpty() && timeline.audio.isEmpty()
}

/// A clipboard destination on the exact edit clock. At coincident cuts, the
/// user can choose the picture sequence or the independent sound sequence.
data class TimelinePasteTarget(val kind: Kind, val time: EditTime, val anchor: Anchor) {
    enum class Kind { Picture, Sound, Project }
    sealed class Anchor {
        data class Between(val left: UUID, val right: UUID) : Anchor()
        object Start : Anchor()
        object End : Anchor()
        object Gap : Anchor()
    }

    fun isAt(playhead: EditTime): Boolean {
        // Next change lands on the first presentation frame after a subframe
        // edit. Pin the original exact cut even when reached by transport.
        val frame = EditTime.scale / 30
        val display = (time.ticks + frame - 1) / frame * frame
        return Math.abs(time.ticks - playhead.ticks) < 24 || playhead.ticks == display
    }

    fun label(timeline: TrackTimeline): String = when (anchor) {
        Anchor.Start -> "Start"
        Anchor.End -> "End"
        Anchor.Gap -> "Gap"
        is Anchor.Between -> {
            val ids = if (kind == Kind.Picture) timeline.video.map { it.id } else timeline.audio.map { it.id }
            val prefix = if (kind == Kind.Picture) "V" else "A"
            fun number(id: UUID) = ids.indexOf(id).let { if (it < 0) prefix else prefix + (it + 1) }
            (if (kind == Kind.Picture) "Video" else "Audio") + " · " + number(anchor.left) + " | " + number(anchor.right)
        }
    }
}

/// Free picture time may contain independent audio. Use it before opening
/// more time, and ripple at the next picture only when the batch overflows.
sealed class VideoInsertionSite {
    object Start : VideoInsertionSite()
    object End : VideoInsertionSite()
    data class Gap(val after: EditTime) : VideoInsertionSite()

    class Placement(val start: EditTime, val rippleAt: EditTime, val ripple: EditTime)
    fun placement(timeline: TrackTimeline, length: EditTime): Placement {
        val coverage = timeline.videoCoverage
        val first = coverage.firstOrNull() ?: return Placement(EditTime.zero, EditTime.zero, EditTime.zero)
        val last = coverage.last()
        return when (this) {
            Start -> Placement(maxOf(EditTime.zero, first.start - length), first.start, maxOf(EditTime.zero, length - first.start))
            End -> Placement(last.end, last.end, EditTime.zero)
            is Gap -> {
                val left = coverage.lastOrNull { it.start < after } ?: return Start.placement(timeline, length)
                val right = coverage.firstOrNull { it.start > left.end } ?: return Placement(left.end, left.end, EditTime.zero)
                Placement(left.end, right.start, maxOf(EditTime.zero, length - (right.start - left.end)))
            }
        }
    }
}

/// Copy, Cut and Paste on the track timeline, as the iPhone app does them.
object TimelinePaste {
    /// Picture cuts in playback order. A cut is a join only where the left
    /// picture really ends as the right one starts.
    fun pictureJoins(timeline: TrackTimeline): List<Pair<UUID, UUID>> {
        val pictures = timeline.chronologicalVideo
        val endings = pictures.groupBy { it.end }
        val joins = ArrayList<Pair<UUID, UUID>>()
        for (index in 1 until pictures.size) {
            val right = pictures[index]
            val ending = endings[right.start] ?: emptyList()
            val left = ending.filter { it.layer <= right.layer }.maxWithOrNull(TrackTimeline.backToFront)
                ?: ending.minWithOrNull(TrackTimeline.backToFront) ?: pictures[index - 1]
            if (left.end == right.start) joins.add(left.id to right.id)
        }
        return joins
    }

    fun targets(timeline: TrackTimeline): List<TimelinePasteTarget> {
        val starts = timeline.video.associateBy { it.id }
        val cuts = ArrayList<TimelinePasteTarget>()
        for ((left, right) in pictureJoins(timeline))
            cuts.add(TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, starts[right]!!.start, TimelinePasteTarget.Anchor.Between(left, right)))
        val pictureIDs = timeline.video.map { it.id }.toSet()
        val linked = timeline.links.filter { link -> link.members.any { it in pictureIDs } }.flatMap { it.members }.toSet()
        val independent = timeline.audio.filter { it.id !in linked }
        val soundStarts = independent.groupBy { it.start }; val soundEnds = independent.groupBy { it.end }
        for (time in soundEnds.keys.sorted()) {
            val left = soundEnds[time] ?: continue; val right = soundStarts[time] ?: continue
            for ((a, b) in TrackTimeline.neighbors(left.map { it.id to it.occurrenceID }, right.map { it.id to it.occurrenceID }))
                cuts.add(TimelinePasteTarget(TimelinePasteTarget.Kind.Sound, time, TimelinePasteTarget.Anchor.Between(a, b)))
        }
        if (timeline.video.isEmpty() && timeline.audio.isEmpty()) return cuts
        val coverage = timeline.videoCoverage
        if (coverage.isNotEmpty()) {
            cuts.add(TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, coverage.first().start, TimelinePasteTarget.Anchor.Start))
            cuts.add(TimelinePasteTarget(TimelinePasteTarget.Kind.Picture, coverage.last().end, TimelinePasteTarget.Anchor.End))
        }
        // A score can extend beyond the last picture, or precede the first.
        // Both the picture edge and the actual project edge are useful targets.
        val outer = if (timeline.video.isEmpty()) TimelinePasteTarget.Kind.Sound else TimelinePasteTarget.Kind.Project
        if (coverage.firstOrNull()?.start != EditTime.zero) cuts.add(TimelinePasteTarget(outer, EditTime.zero, TimelinePasteTarget.Anchor.Start))
        if (coverage.lastOrNull()?.end != timeline.duration) cuts.add(TimelinePasteTarget(outer, timeline.duration, TimelinePasteTarget.Anchor.End))
        return cuts
    }

    /// Snapshot only the chosen media. Collapse empty space between unrelated
    /// selections while retaining real overlaps and offsets inside linked groups.
    fun fragment(timeline: TrackTimeline, selected: TimelineSelection, keepGaps: Boolean): TimelineClipboard {
        val fragment = timeline.copy()
        fragment.video = fragment.video.filter { it.id in selected.video }
        fragment.audio = fragment.audio.filter { it.id in selected.audio }
        fragment.placements = placementsKeeping(timeline, selected.video)
        fragment.pruneRelations()
        val visible = fragment.resolvedBookmarks.map { it.id }.toSet()
        fragment.bookmarks = fragment.bookmarks.filter { it.anchor !is BookmarkAnchor.Project && it.id in visible }
        val spans = fragment.video.associate { it.id to it.interval } + fragment.audio.associate { it.id to it.interval }
        val occupied = ArrayList(spans.values)
        for (group in fragment.links) {
            val ranges = group.members.mapNotNull { spans[it] }
            if (ranges.isNotEmpty()) occupied.add(EditInterval(ranges.minOf { it.start }, ranges.maxOf { it.end }))
        }
        var islands = TrackTimeline.union(occupied)
        if (keepGaps && islands.isNotEmpty()) islands = listOf(EditInterval(islands.first().start, islands.last().end))
        if (islands.isNotEmpty()) {
            var island = 0; var offset = EditTime.zero - islands[0].start
            val starts = HashMap<UUID, EditTime>()
            for ((id, range) in spans.entries.sortedBy { it.value.start }) {
                while (island + 1 < islands.size && range.start >= islands[island + 1].start) {
                    offset = offset - (islands[island + 1].start - islands[island].end)
                    island += 1
                }
                starts[id] = range.start + offset
            }
            fragment.video = fragment.video.map { it.copy(start = starts[it.id]!!) }
            fragment.audio = fragment.audio.map { it.copy(start = starts[it.id]!!) }
            fragment.pruneRelations()
        }
        val pictures = fragment.video.map { it.id }.toSet(); val sounds = fragment.audio.map { it.id }.toSet()
        val inserts = fragment.links.any { link -> link.members.any { it in pictures } && link.members.any { it in sounds } }
        return TimelineClipboard(fragment, inserts)
    }

    private fun placementsKeeping(timeline: TrackTimeline, ids: Set<UUID>): List<VideoPlacementRegion> {
        val kept = timeline.placements.filter { ids.containsAll(it.participants) }.toMutableList()
        for (region in timeline.placements) {
            val shared = region.participants.intersect(ids)
            if (shared.size < 2 || kept.any { it.participants == shared }) continue
            kept.add(VideoPlacementRegion(participants = shared, videos = region.videos.filterKeys { it in shared }))
        }
        return kept
    }

    fun copied(timeline: TrackTimeline, targets: TimelineSelection): TimelineClipboard {
        val (scoped, selection) = timeline.scoped(targets)
        return fragment(scoped, selection, targets.range != null)
    }

    /// Capture the very fragments that are lifted (a range split creates new IDs).
    /// Returns the clipboard and the playhead after the cut; the timeline is changed in place.
    fun cut(timeline: TrackTimeline, targets: TimelineSelection, preserving: EditTime): Pair<TimelineClipboard, EditTime>? {
        val (scoped, selection) = timeline.scoped(targets)
        val clip = fragment(scoped, selection, targets.range != null)
        if (clip.isEmpty) return null
        scoped.placements = placementsKeeping(scoped, scoped.video.map { it.id }.toSet() - selection.video)
        val position = scoped.remove(selection, preserving) ?: preserving
        val copiedMarks = clip.timeline.bookmarks.map { it.id }.toSet()
        val remaining = scoped.resolvedBookmarks.map { it.id }.toSet()
        scoped.bookmarks = scoped.bookmarks.filter { it.id !in copiedMarks || it.id in remaining }
        timeline.video = scoped.video; timeline.audio = scoped.audio; timeline.links = scoped.links
        timeline.bookmarks = scoped.bookmarks; timeline.placements = scoped.placements
        return clip to position
    }

    private fun prepare(timeline: TrackTimeline, clipboard: TimelineClipboard, point: EditTime, target: TimelinePasteTarget?): EditTime {
        val length = clipboard.timeline.duration
        if (target == null) {
            val position = maxOf(EditTime.zero, minOf(timeline.duration, point))
            return if (clipboard.insertsTime) insertPictureTime(timeline, length, position) else position
        }
        val hasPicture = clipboard.timeline.video.isNotEmpty()
        var destination = target.time
        when (val anchor = target.anchor) {
            TimelinePasteTarget.Anchor.Start, TimelinePasteTarget.Anchor.Gap -> {
                if (hasPicture) {
                    val site = if (anchor == TimelinePasteTarget.Anchor.Gap) VideoInsertionSite.Gap(target.time) else VideoInsertionSite.Start
                    val placement = site.placement(timeline, length)
                    destination = if (target.kind == TimelinePasteTarget.Kind.Project) EditTime.zero else placement.start
                    if (placement.ripple > EditTime.zero) {
                        if (anchor == TimelinePasteTarget.Anchor.Gap) timeline.insertTime(placement.ripple, placement.rippleAt, atPictureGap = true)
                        else timeline.move(TimelineSelection(video = timeline.video.map { it.id }.toSet()), placement.ripple)
                    }
                } else if (target.kind == TimelinePasteTarget.Kind.Sound) {
                    val leading = timeline.audio.minOfOrNull { it.start } ?: EditTime.zero
                    timeline.move(timeline.entireSelection(), maxOf(EditTime.zero, length - leading))
                }
            }
            TimelinePasteTarget.Anchor.End -> { }
            is TimelinePasteTarget.Anchor.Between -> {
                if (hasPicture) {
                    destination = insertPictureTime(timeline, length, destination, if (target.kind == TimelinePasteTarget.Kind.Picture) anchor.right else null)
                } else if (target.kind == TimelinePasteTarget.Kind.Sound) {
                    val chain = timeline.moveChoices(timeline.selection(anchor.right)).right.audio
                    val tail = timeline.audio.filter { it.id in chain && it.start >= destination }.map { it.id }.toSet()
                    timeline.split(destination, TimelineSelection(audio = tail))
                    timeline.audio = timeline.audio.map { if (it.id in tail) it.copy(start = it.start + length) else it }
                }
            }
        }
        return destination
    }

    /// Open the picture sequence and its production sound, leaving independent music continuous.
    private fun insertPictureTime(timeline: TrackTimeline, length: EditTime, point: EditTime, pictureOnRight: UUID? = null): EditTime {
        // Opening a transition apart would leave it without handles: it becomes a straight cut first.
        val crossing = timeline.generated.filter { it.interval.start < point && point < it.interval.end }.map { it.owner }.toSet() +
            timeline.transitions.filter { pictureOnRight != null && it.incoming == pictureOnRight && it.outgoing != null }.map { it.id }
        for (id in crossing) {
            val state = EditState(timeline.copy())
            val join = TransitionLayout.joins(state.timeline).firstOrNull { it.transition?.id == id } ?: continue
            val restored = TransitionLayout.restoringStraightCut(state, join.index).timeline
            timeline.video = restored.video; timeline.audio = restored.audio; timeline.links = restored.links
            timeline.transitions = restored.transitions; timeline.generated = restored.generated; timeline.placements = restored.placements
        }
        val position = pictureOnRight?.let { id -> timeline.video.firstOrNull { it.id == id }?.start } ?: point
        timeline.split(position, TimelineSelection(video = timeline.video.map { it.id }.toSet()))
        val tail = timeline.expanded(TimelineSelection(video = timeline.video.filter { it.start >= position }.map { it.id }.toSet()))
        timeline.video = timeline.video.map { if (it.id in tail.video) it.copy(start = it.start + length) else it }
        timeline.audio = timeline.audio.map { if (it.id in tail.audio) it.copy(start = it.start + length) else it }
        timeline.bookmarks = timeline.bookmarks.map { mark ->
            val anchor = mark.anchor
            if (anchor is BookmarkAnchor.Project && anchor.time >= position) mark.copy(anchor = BookmarkAnchor.Project(anchor.time + length)) else mark
        }
        return position
    }

    /// Add the clipboard with fresh identities, so each paste stays editable on
    /// its own. Returns what was added; the timeline is changed in place.
    fun paste(timeline: TrackTimeline, clipboard: TimelineClipboard, point: EditTime, target: TimelinePasteTarget? = null): TimelineSelection {
        if (clipboard.isEmpty) return TimelineSelection()
        val addition = clipboard.timeline
        val identifiers = HashMap<UUID, UUID>()
        fun fresh(old: UUID) = identifiers.getOrPut(old) { UUID.randomUUID() }
        val position = prepare(timeline, clipboard, point, target)
        val front = (timeline.video.maxOfOrNull { it.layer } ?: 0) + 1
        val layers = addition.video.map { it.layer }.toSortedSet().withIndex().associate { it.value to front + it.index }
        val video = addition.video.map { item ->
            val content = item.content
            item.copy(occurrenceID = fresh(item.occurrenceID), start = item.start + position, layer = layers[item.layer]!!,
                content = content.copy(id = fresh(content.id), speed = content.speed?.let { it.copy(id = fresh(it.id)) },
                    crop = content.crop?.let { it.copy(id = fresh(it.id)) }, mirror = content.mirror?.let { it.copy(id = fresh(it.id)) },
                    color = content.color?.let { it.copy(id = fresh(it.id)) }, texts = content.texts?.map { it.copy(id = fresh(it.id)) },
                    images = content.images?.map { it.copy(id = fresh(it.id)) }, pauses = content.pauses?.let { it.copy(id = fresh(it.id)) }))
        }
        val audio = addition.audio.map {
            it.copy(id = fresh(it.id), occurrenceID = fresh(it.occurrenceID), followsVideo = it.followsVideo?.let(::fresh), start = it.start + position,
                censors = it.censors.map { region -> region.copy(id = fresh(region.id)) },
                enhancements = it.enhancements.map { region -> region.copy(id = fresh(region.id)) },
                subtitleWords = it.subtitleWords.map { word -> word.copy(id = fresh(word.id), phraseID = fresh(word.phraseID)) })
        }
        val links = addition.links.map { TimelineLink(members = it.members.map(::fresh).toSet()) }
        val placements = addition.placements.map { region ->
            VideoPlacementRegion(participants = region.participants.map(::fresh).toSet(), videos = region.videos.mapKeys { fresh(it.key) })
        }
        val transitions = addition.transitions.map { relation ->
            relation.copy(settings = relation.settings.copy(id = fresh(relation.id), nextClipID = relation.incoming?.let(::fresh)),
                outgoing = relation.outgoing?.let(::fresh), incoming = relation.incoming?.let(::fresh), origin = relation.origin + position)
        }
        val marks = addition.bookmarks.map { mark ->
            val anchor = when (val a = mark.anchor) {
                is BookmarkAnchor.Source -> BookmarkAnchor.Source(fresh(a.occurrence), a.source, a.time)
                is BookmarkAnchor.Local -> BookmarkAnchor.Local(fresh(a.occurrence), a.offset)
                is BookmarkAnchor.Project -> BookmarkAnchor.Project(a.time + position)
            }
            mark.copy(id = UUID.randomUUID(), anchor = anchor)
        }
        timeline.video = (timeline.video + video).withIndex().sortedWith { a, b ->
            if (a.value.start == b.value.start) a.index.compareTo(b.index) else a.value.start.compareTo(b.value.start) }.map { it.value }
        timeline.audio = timeline.audio + audio; timeline.links = timeline.links + links
        timeline.placements = timeline.placements + placements; timeline.bookmarks = timeline.bookmarks + marks
        timeline.transitions = timeline.transitions + transitions
        timeline.pruneRelations()
        return TimelineSelection(video.map { it.id }.toSet(), audio.map { it.id }.toSet())
    }
}

/// The editor's Copy, Cut and Paste, on whole edit states.
object TimelineClipboardEditing {
    fun copying(selection: TimelineSelection, state: EditState): TimelineClipboard? =
        TimelinePaste.copied(state.timeline, selection).takeIf { !it.isEmpty }
    /// The new edit, the clipboard and where the playhead goes.
    fun cutting(selection: TimelineSelection, state: EditState, preserving: EditTime): Triple<EditState, TimelineClipboard, EditTime>? {
        val result = state.copy()
        val (clipboard, position) = TimelinePaste.cut(result.timeline, selection, preserving) ?: return null
        return Triple(result, clipboard, position)
    }
    /// The new edit and what was added, or null if nothing could be pasted.
    fun pasting(clipboard: TimelineClipboard, state: EditState, at: EditTime, target: TimelinePasteTarget?): Pair<EditState, TimelineSelection>? {
        val result = state.copy()
        val added = TimelinePaste.paste(result.timeline, clipboard, at, target)
        return if (added.isEmpty) null else result to added
    }
    /// Where a paste can go at the playhead. More than one means the user chooses.
    fun targets(state: EditState, playhead: EditTime) = TimelinePaste.targets(state.timeline).filter { it.isAt(playhead) }
}
