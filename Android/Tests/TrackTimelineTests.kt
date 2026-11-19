package yourec

import java.util.UUID
import kotlin.math.abs

private fun t(seconds: Double) = EditTime.seconds(seconds)

fun trackTimelineTests() {
    val source = SourceMedia(filename = "source.mp4", duration = 4.0)
    val a = TimelineClip(sourceID = source.id, start = 0.0, end = 2.0)
    val b = TimelineClip(sourceID = source.id, start = 2.0, end = 4.0)
    val sequential = EditState(listOf(a, b))
    val baseline = sequential.timeline
    baseline.validate(listOf(source))
    require(baseline == EditState(listOf(a, b)).timeline, "Conversion gives stable AV/link identities")
    require(baseline.duration == t(4.0), "Independent timeline preserves complete source duration")
    val first = baseline.selection(a.id)
    val splitElsewhere = TimelineEditing.split(sequential, 3.0, first.ids).timeline
    require(splitElsewhere.video.map { it.start } == listOf(EditTime.zero, t(2.0), t(3.0)) && splitElsewhere.audio.map { it.start } == listOf(EditTime.zero, t(2.0), t(3.0))
        && splitElsewhere.video.first() == baseline.video.first() && splitElsewhere.duration == baseline.duration,
        "A selection in the first clip still allows one synchronized cut in the second")
    require(TimelineEditing.split(sequential, 4.0, first.ids) == sequential, "An old selection cannot create an empty clip at the project end")
    for ((before, after) in listOf(0.5 to 0.0, 2.0 to 0.0, 3.25 to 1.25, 4.0 to 2.0)) {
        val cut = baseline.copy()
        require(cut.remove(first, t(before)) == t(after), "Ripple deletion follows surviving content, or lands at the cut when current content is removed")
    }
    val endCut = baseline.copy()
    require(endCut.remove(baseline.selection(b.id), t(0.75)) == t(0.75), "A later deletion leaves the playhead untouched")
    val overlaidCut = baseline.copy()
    overlaidCut.video += baseline.video[0].let { it.copy(content = it.content.copy(id = UUID.randomUUID()), start = t(0.25), layer = 99) }
    require(overlaidCut.remove(first, t(1.0)) == EditTime.zero, "Deleted linked footage lands at its cut even under a stationary detached overlay")
    require(first.video == setOf(a.id) && first.audio.size == 1, "Linked selection includes exactly its picture and sound")
    val ripple = baseline.copy()
    ripple.remove(first)
    require(ripple.duration == t(2.0) && ripple.video[0].start == EditTime.zero && ripple.audio[0].start == EditTime.zero, "Linked deletion closes gap in both components")
    val separate = baseline.copy()
    separate.unlink(first)
    val splitSound = TimelineEditing.split(EditState(separate.copy()), 1.0, setOf(separate.audio[0].id)).timeline
    require(splitSound.video == separate.video && splitSound.audio.map { it.start } == listOf(EditTime.zero, t(1.0), t(2.0)),
        "An explicitly selected detached audio part at the playhead still splits independently")
    require(separate.video == baseline.video && separate.audio.map { it.mapping } == baseline.audio.map { it.mapping } && separate.audio.map { it.start } == baseline.audio.map { it.start },
        "Unlink changes no timing, samples or settings")
    val audioOnly = separate.copy()
    require(audioOnly.remove(separate.selection(baseline.audio[0].id), t(3.0)) == t(3.0), "Detached audio deletion keeps the absolute playhead")
    require(audioOnly.video == baseline.video && audioOnly.audio == listOf(baseline.audio[1]) && audioOnly.duration == t(4.0), "Deleting unlinked audio leaves silence without moving any video")
    val videoOnly = separate.copy()
    require(videoOnly.remove(separate.selection(a.id), t(1.0)) == t(1.0), "Detached video deletion leaves the playhead in the resulting gap")
    require(videoOnly.audio == separate.audio && videoOnly.video == listOf(baseline.video[1]) && videoOnly.duration == t(4.0), "Deleting unlinked video leaves picture gap without moving sound")
    require(videoOnly.gap(t(1.0)) == null && videoOnly.compile().first().video.isEmpty(), "Sound under a picture gap is not an empty gap")
    val moving = separate.copy()
    moving.move(moving.selection(a.id), t(2.5))
    require(moving.audio == separate.audio && moving.video[0].start == t(2.5), "Moving detached video leaves audio immutable")
    require(moving.compile().any { it.video.size == 2 && it.video.last() == a.id }, "Overlap compiler orders newly moved video in foreground")
    moving.move(moving.selection(a.id), t(-30.0))
    require(moving.video[0].start == EditTime.zero, "Move clamps whole group at project start")
    val linkedMove = baseline.copy()
    linkedMove.move(first, t(1.0))
    require(linkedMove.video[0].start == linkedMove.audio[0].start, "Moving linked AV preserves sync")
    val bed = baseline.copy()
    bed.unlink(bed.selection(b.id))
    val detached = bed.audio[1]
    bed.remove(first)
    require(detached in bed.audio, "Deleting a linked clip never cuts or shifts detached audio")
    val marks = baseline.copy()
    marks.addBookmark(t(1.0))
    require(marks.snapTarget(1.02, 60.0) == 1.0, "Stationary source marks are snap targets")
    require(marks.snapTarget(1.02, 60.0, first.ids) == null, "Moving footage never snaps to its own attached bookmark")
    marks.split(t(1.0), marks.selection(a.id))
    require(marks.duration == baseline.duration && marks.video.size == 3 && marks.audio.size == 3 && marks.links.size == 3, "Split preserves all source and creates independent linked pairs")
    require(marks.resolvedBookmarks.size == 1 && marks.resolvedBookmarks[0].time == t(1.0), "Bookmark at a split is not duplicated")
    val right = marks.video.first { it.start == t(1.0) }
    marks.move(marks.selection(right.id), t(4.0))
    require(marks.resolvedBookmarks[0].time == t(5.0), "Source bookmark follows its occurrence after split and move")
    marks.validate(listOf(source))
    val (scoped, scope) = baseline.scoped(baseline.entireSelection(EditInterval(0.5, 3.5)))
    require(scoped.remove(scope, t(3.75)) == t(0.75), "Range deletion preserves the source frame after scoping splits")
    require(abs(scoped.duration.seconds - 1) < 0.00001, "Range deletion splits linked AV and closes the exact interval")
    require(scoped.video.map { it.content.start } == listOf(0.0, 3.5) && scoped.video.map { it.content.end } == listOf(0.5, 4.0), "Range deletion preserves exact outer source pieces")
    require(baseline == EditState(listOf(a, b)).timeline, "Scoping a draft never changes the timeline it came from")
    val insertion = baseline.copy()
    insertion.insertTime(t(1.0), t(1.0))
    require(insertion.duration == t(5.0) && insertion.video.size == 3, "Insert time preserves crossing pieces and shifts right side")
    insertion.validate(listOf(source))
    val viewport = TimelineViewport(402.0, 960.0, 0.01)
    require(viewport.fits && viewport.x(0.0, 0.0) >= 0 && viewport.x(960.0, 0.0) <= 402 && viewport.minimumScale < 0.4, "Whole-project fit shows both ends of a sixteen-minute project")
    val capture = source.copy(events = listOf(CaptureEvent(time = 1.2, kind = "bookmark", detail = "Bookmark"), CaptureEvent(time = 4.1, kind = "bookmark", detail = "After recovery end")))
    val captureMarks = baseline.copy()
    captureMarks.includeCaptureBookmarks(capture); captureMarks.includeCaptureBookmarks(capture)
    require(captureMarks.resolvedBookmarks.size == 1 && captureMarks.resolvedBookmarks[0].time == t(1.2), "Capture bookmarks import once and exclude unrecovered footage")
    val restored = ProjectCoding.timeline(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(marks)))))
    require(restored == marks, "Independent positions, links and bookmarks round-trip exactly")
    for (rate in listOf(24.0, 25.0, 30.0, 50.0, 60.0, 24000.0 / 1001, 30000.0 / 1001, 60000.0 / 1001)) {
        require(abs(t(1 / rate).seconds - 1 / rate) < 1e-10, "Canonical clock exactly represents standard frame durations")
    }
    require(t(-0.5 / EditTime.scale).ticks == -1L && t(0.5 / EditTime.scale).ticks == 1L && t(Double.NaN) == EditTime.zero, "The clock rounds half away from zero, as on iPhone")

    trimTests(source, sequential)
    linkTests(source, baseline)
    moveCompanionTests()
    laneTests(source, baseline)
    fadeTests(baseline)
    multiSelectionTests()
    navigationTests(baseline)
}

private fun trimTests(source: SourceMedia, sequential: EditState) {
    val sources = listOf(source)
    val firstID = sequential.timeline.video[0].id; val secondID = sequential.timeline.video[1].id
    val (shorter, position) = TimelineEditing.trimming(firstID, false, -0.5, sequential, sources, t(3.0))
    require(shorter.duration == 3.5 && shorter.timeline.video[0].content.end == 1.5 && shorter.timeline.video[1].start == t(1.5) && position == t(2.5),
        "Trimming a linked clip's end closes the gap and keeps the playhead on its frame")
    require(shorter.timeline.audio.map { it.start } == shorter.timeline.video.map { it.start }, "A trim keeps linked sound with its picture")
    shorter.timeline.validate(sources)
    val (again, _) = TimelineEditing.trimming(firstID, false, 0.5, shorter, sources)
    require(again.timeline.video[0].content.end == 2.0 && again.timeline.video[0].mapping == sequential.timeline.video[0].mapping && again.timeline.audio[0].mapping == sequential.timeline.audio[0].mapping,
        "Extending reveals exactly the trimmed material as one continuous run")
    require(again.timeline.video[1].start == t(1.5) && again.timeline.compile().any { it.video.size == 2 }, "Revealing into an occupied interval creates an editable overlap")
    again.timeline.validate(sources)
    val (head, _) = TimelineEditing.trimming(secondID, true, 0.75, sequential, sources)
    require(head.timeline.video[1].content.start == 2.75 && head.timeline.video[1].start == t(2.0) && head.duration == 3.25, "Trimming a clip's start removes its opening and closes the gap")
    val (limited, _) = TimelineEditing.trimming(secondID, false, 9.0, sequential, sources)
    require(limited == sequential, "A take cannot be extended past its recorded end")
    val (wholeAway, _) = TimelineEditing.trimming(firstID, true, 99.0, sequential, sources)
    require(abs(wholeAway.timeline.video[0].mapping.duration.seconds - TimelineEditing.frame) < 1e-5, "A trim always leaves at least one frame")
    val inner = TimelineEditing.removing(1.0, 1.0 + TimelineEditing.frame / 2, sequential).first
    require(inner == sequential, "A range shorter than a frame deletes nothing")
    val joined = TimelineEditing.removing(1.9, 3.0, sequential).first
    require(joined.timeline.video.map { it.content.end } == listOf(1.9, 4.0) && joined.timeline.video[1].content.start == 3.0, "A range edge further than a frame from a cut stays where it was marked")
    val aligned = TimelineEditing.removing(2.01, 3.0, sequential).first
    require(aligned.timeline.video.size == 2 && aligned.timeline.video[0].content.end == 2.0, "A range edge within a frame of a cut uses that cut, leaving no sliver")
    val third = TimelineClip(sourceID = source.id, start = 1.0, end = 2.0)
    val inserted = TimelineEditing.inserting(listOf(third), sequential, 1.0)
    require(inserted.duration == 5.0 && inserted.timeline.video.size == 4 && inserted.timeline.video.none { it.id == third.id }, "An inserted take opens its own length and gets a new identity")
    inserted.timeline.validate(sources)
    val (deleted, landed) = TimelineEditing.deleting(inserted.timeline.selection(inserted.timeline.video[1].id), inserted, t(4.0))
    require(deleted.duration == 4.0 && deleted.timeline.video.map { it.content.start } == listOf(0.0, 1.0, 2.0), "Deleting the inserted take closes its gap")
    require(landed == t(3.0), "Deletion carries the playhead with the surviving frame")
}

private fun linkTests(source: SourceMedia, baseline: TrackTimeline) {
    val a = baseline.video[0]; val sound = baseline.audio[0]
    val loose = baseline.copy(); loose.unlink(loose.selection(a.id))
    require(loose.links.size == 1 && loose.audio[0].followsVideo == null && loose.selection(a.id).audio.isEmpty(), "Unlink separates one picture from its sound")
    val (joined, scope) = loose.joining(TimelineSelection(setOf(a.id), setOf(sound.id)))
    require(joined.links.size == 2 && joined.audio[0].followsVideo == a.id && scope.ids == setOf(a.id, sound.id), "Linking a matching original pair restores its source relationship")
    joined.validate(listOf(source))
    require(loose.audio[0].followsVideo == null, "Linking is a pure draft")
    fun rejected(timeline: TrackTimeline, scope: TimelineSelection, expected: TimelineLinkError.Kind) {
        try { timeline.joining(scope); require(false, "Link must be refused: $expected") } catch (e: TimelineLinkError) { require(e.kind == expected, "Link refusal explains itself: $expected") }
    }
    rejected(loose, TimelineSelection(setOf(a.id)), TimelineLinkError.Kind.TooFew)
    val apart = loose.copy(); apart.move(TimelineSelection(audio = setOf(sound.id)), t(10.0))
    rejected(apart, TimelineSelection(setOf(a.id), setOf(sound.id)), TimelineLinkError.Kind.NoIntersection)
    val shifted = loose.copy(); shifted.move(TimelineSelection(audio = setOf(sound.id)), t(0.5))
    rejected(shifted, TimelineSelection(setOf(a.id), setOf(sound.id)), TimelineLinkError.Kind.ConfirmationRequired)
    val (shared, part) = shifted.joining(TimelineSelection(setOf(a.id), setOf(sound.id)), intersectionOnly = true)
    require(shared.interval(part) == EditInterval(0.5, 2.0) && shared.video.size == 3 && part.video.size == 1 && part.audio.size == 1, "Linking different spans glues only their intersection")
    shared.validate(listOf(source))
    val gapped = baseline.copy(); gapped.unlink(gapped.entireSelection()); gapped.move(TimelineSelection(setOf(baseline.video[1].id)), t(1.0))
    rejected(gapped, TimelineSelection(gapped.video.map { it.id }.toSet()), TimelineLinkError.Kind.NonContiguous)
    val toggled = baseline.toggling(a.id, emptySet())
    require(toggled == setOf(a.id, sound.id) && baseline.toggling(a.id, toggled).isEmpty(), "Long-press adds or removes a whole linked part")
}

private fun moveCompanionTests() {
    val source = SourceMedia(filename = "long.mp4", duration = 10.0)
    val clips = (0 until 4).map { TimelineClip(sourceID = source.id, start = it * 2.0, end = it * 2.0 + 2) }
    val timeline = TrackTimeline.of(clips)
    timeline.move(timeline.selection(setOf(clips[3].id)), t(1.0))   // a gap before the last clip
    val choices = timeline.moveChoices(timeline.selection(setOf(clips[1].id)))
    fun pictures(vararg indices: Int) = indices.map { clips[it].id }.toSet()
    require(choices.selected.video == pictures(1) && choices.left.video == pictures(0, 1) && choices.right.video == pictures(1, 2) && choices.both.video == pictures(0, 1, 2),
        "Each side follows cuts up to the first gap")
    require(choices.both.audio.size == 3, "Linked sound travels with its picture")
    require(choices.available(TimelineMoveScope.Left) && choices.available(TimelineMoveScope.Right) && choices.available(TimelineMoveScope.Both), "All scopes are offered in the middle of a sequence")
    val edge = timeline.moveChoices(timeline.selection(setOf(clips[3].id)))
    require(!edge.available(TimelineMoveScope.Left) && !edge.available(TimelineMoveScope.Right) && !edge.available(TimelineMoveScope.Both), "An isolated clip has no companions")
    val result = timeline.snappedMove(timeline.selection(setOf(clips[3].id)), t(-0.9), 60.0)
    require(result.delta == t(-1.0) && result.snap == TimelineMoveSnap(t(6.0), t(7.0)), "A dragged clip snaps its edge to the neighbouring cut")
    val held = timeline.snappedMove(timeline.selection(setOf(clips[3].id)), t(-0.75), 60.0, result.snap)
    require(held.delta == t(-1.0), "A latched snap holds through small finger movement")
    require(timeline.snappedMove(timeline.selection(setOf(clips[3].id)), t(-0.4), 60.0, result.snap).snap == null, "The latch releases once the finger moves away")
    require(timeline.snappedMove(timeline.selection(setOf(clips[0].id)), t(-5.0), 60.0).delta == EditTime.zero, "A move cannot pass the project start")
}

private fun laneTests(source: SourceMedia, baseline: TrackTimeline) {
    val lanes = timelineLanes(baseline)
    require(lanes.size == 4 && lanes.filter { !it.audio }.all { it.row == 0 } && lanes.filter { it.audio }.all { it.row == 1 }, "A plain sequence uses one picture row above one sound row")
    val overlap = baseline.copy(); overlap.unlink(overlap.entireSelection())
    overlap.move(TimelineSelection(setOf(baseline.video[1].id)), t(-1.0))
    val rows = timelineLanes(overlap).filter { !it.audio }.associate { it.id to it.row }
    require(rows[baseline.video[1].id] == 0 && rows[baseline.video[0].id] == 1, "The front picture of an overlap takes the top row")
    for (group in timelineLanes(overlap).groupBy { it.row }.values) {
        for (x in group) for (y in group) require(x.id == y.id || x.interval.intersection(y.interval) == null, "Cards in one row never overlap")
    }
    val lifted = timelineLanes(baseline, baseline.selection(baseline.video[1].id)).filter { !it.audio }.associate { it.id to it.row }
    require(lifted[baseline.video[1].id] == 0 && lifted[baseline.video[0].id] == 1, "The moving group is lifted onto its own row")
}

private fun fadeTests(baseline: TrackTimeline) {
    val timeline = baseline.copy()
    val all = timeline.entireSelection()
    timeline.setFade(AudioFadeEdge.FadeIn, 1.0, all); timeline.setFade(AudioFadeEdge.FadeOut, 0.5, all)
    require(timeline.audio[0].fadeIn == AudioEdgeFade(1.0) && timeline.audio[0].fadeOut == null && timeline.audio[1].fadeOut == AudioEdgeFade(0.5) && timeline.audio[1].fadeIn == null,
        "With several audio clips selected only the earliest start and latest end receive fades")
    require(timeline.audio[0].gain(0.25) == 0.25 && timeline.audio[0].gain(1.5) == 1.0 && abs(timeline.audio[1].gain(1.75) - 0.5) < 1e-9, "A fade is linear from its edge")
    timeline.split(t(0.5), all)
    val halves = timeline.audio.filter { it.sourceID == baseline.audio[0].sourceID }.sortedBy { it.start }.take(2)
    require(halves[0].gain(0.5 - 1e-9) > 0.499 && abs(halves[1].gain(0.0) - 0.5) < 1e-9 && halves[1].fadeSeconds(AudioFadeEdge.FadeIn) == 0.5,
        "A split inside a fade inherits its slope and its level at the cut")
    timeline.setVolume(40.0, false, timeline.entireSelection())
    require(timeline.audio.all { it.volumeDB == 12.0 }, "Volume is limited to +12 dB")
    timeline.setVolume(-6.0, true, timeline.entireSelection())
    require(timeline.audio.all { it.gain == 0.0 }, "Mute silences without losing the level")
}

private fun navigationTests(baseline: TrackTimeline) {
    require(TimelineNavigation.stops(baseline) == listOf(0.0, 2.0, 4.0), "Cut navigation visits each edge once")
    require(TimelineNavigation.jump(baseline, 2.0, true) == 4.0 && TimelineNavigation.jump(baseline, 2.0, false) == 0.0 && TimelineNavigation.jump(baseline, 4.0, true) == null,
        "Previous and next move past the cut under the playhead")
}

private fun multiSelectionTests() {
    val source = SourceMedia(filename = "multi.mp4", duration = 12.0)
    val clips = (0 until 3).map { TimelineClip(sourceID = source.id, start = it * 4.0, end = it * 4.0 + 4) }
    val original = TrackTimeline.of(clips)
    val all = original.entireSelection()
    val (together, glued) = original.joining(all)
    require(together.links.size == 1 && together.links[0].members == all.ids, "Join horizontal chains before vertical linking")
    require(together.video == original.video && together.audio == original.audio, "Join preserves every sample map, source identity, setting and follower")
    together.validate(listOf(source))
    require(ProjectCoding.timeline(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(together))))) == together, "Multi-part link persists exactly")
    val detached = original.copy(); detached.unlink(all)
    val videos = TimelineSelection(video = all.video); val audios = TimelineSelection(audio = all.audio)
    val v = detached.joining(videos).first; val a = detached.joining(audios).first
    require(v.selection(clips[1].id).ids == all.video, "Video-only horizontal glue selects every joined section")
    require(a.selection(detached.audio[1].id).ids == all.audio, "Audio-only horizontal glue selects every joined section")
    val splitGroup = together.copy()
    splitGroup.split(EditTime.seconds(6.0), glued)
    require(splitGroup.links.size == 2 && splitGroup.links.all { it.members.size == 4 }, "Splitting a joined sequence separates the two sides, with AV sync preserved")
    splitGroup.validate(listOf(source))
    // Different internal split points, identical coverage: no unnecessary prompt.
    val partitioned = TrackTimeline.of(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 12.0)))
    partitioned.unlink(partitioned.entireSelection())
    partitioned.split(EditTime.seconds(4.0), TimelineSelection(video = setOf(partitioned.video[0].id)))
    partitioned.split(EditTime.seconds(8.0), TimelineSelection(video = partitioned.video.map { it.id }.toSet()))
    require(!partitioned.linkPlan(partitioned.entireSelection()).needsIntersection, "Equal coverage links without asking")
    val joinedPartition = partitioned.joining(partitioned.entireSelection()).first

    fun layout(timeline: TrackTimeline, moving: TimelineSelection? = null) = TimelineLinkLayout(timelineLanes(timeline, moving), timeline.links)
    val whole = EditInterval(0.0, 12.0)
    val gluedLayout = layout(together); val separate = layout(original); val pictureOnly = layout(v)
    require(gluedLayout.edges[clips[1].id]?.previous == clips[0].id && gluedLayout.edges[clips[1].id]?.next == clips[2].id
        && gluedLayout.edges[original.audio[1].id]?.next == original.audio[2].id, "Glued picture and sound share internal cuts")
    require(gluedLayout.edges.values.all { it.outlinedGroup } && separate.edges.values.none { it.outlinedGroup }, "The quiet group outline marks horizontal glue even without a selection")
    require(separate.edges[clips[1].id]?.previous == null && separate.bridges.size == 3 && gluedLayout.bridges.map { it.interval } == listOf(whole),
        "Only explicitly glued groups share outside borders")
    require(pictureOnly.bridges.isEmpty() && pictureOnly.edges[clips[1].id]?.next == clips[2].id && pictureOnly.edges[original.audio[1].id] == null
        && layout(detached).edges.isEmpty(), "Unlinked sound stays independent")
    val mixed = layout(joinedPartition)
    require(mixed.bridges.map { it.interval } == listOf(whole) && joinedPartition.video.all { mixed.edges[it.id]?.joinsBelow == true }
        && mixed.edges[joinedPartition.audio[0].id]?.joinsAbove == true, "Unequal partitions outline the same joined span")
    val gapped = together.copy()
    gapped.video = gapped.video.mapIndexed { i, item -> if (i == 2) item.copy(start = item.start + EditTime.ticks(1)) else item }
    require(layout(gapped).edges[clips[1].id]?.next == null && layout(gapped).edges[clips[2].id]?.joinsBelow == false, "A one-tick gap and unmatched tail never acquire a shared outline")
    val lifted = layout(together, TimelineSelection(video = setOf(clips[1].id)))
    require(lifted.edges[clips[1].id]?.previous == null && lifted.edges[clips[1].id]?.joinsBelow == false, "Lifting an item into a separate lane cannot join through an intervening row")
    val cut = layout(splitGroup)
    require(cut.bridges.size == 2 && cut.bridges.all { it.interval.end == EditTime.seconds(6.0) || it.interval.start == EditTime.seconds(6.0) },
        "Splitting a group exposes an ordinary cut between the two new groups")
    val soundOnly = layout(a)
    require(soundOnly.bridges.isEmpty() && soundOnly.edges[detached.audio[1].id]?.previous == detached.audio[0].id && soundOnly.edges[clips[1].id] == null,
        "Joined audio gets a seam without attaching the picture above")
    // A sequence without overlaps takes one row whatever its order in the document.
    val shuffled = TrackTimeline(original.video.reversed(), original.audio, original.links)
    require(timelineLanes(shuffled).filter { !it.audio }.all { it.row == 0 } && timelineLanes(shuffled).map { it.id }.take(3) == timelineLanes(original).map { it.id }.take(3),
        "Parts that never overlap share a row and keep their drawing order")
    val edge = TimelineViewport(400.0, 600.0, 60.0, centersPlayhead = false)
    require(edge.inset == TimelineViewport.edgeInset && TimelineViewport(400.0, 600.0, 60.0).inset == 200.0, "A viewport can keep the timeline start at its edge instead of the centre")
}
