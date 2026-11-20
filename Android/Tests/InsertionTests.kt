package yourec

private fun t(seconds: Double) = EditTime.seconds(seconds)
private fun reopen(project: Project): Project {
    val files = HashMap<String, ByteArray>()
    val head = project.manifest { name, bytes -> files[name] = bytes }
    return Project.readManifest(head) { files[it]!! }
}

/// Ported from the iPhone app's Record here and video insertion site checks.
fun insertionTests() {
    val a = SourceMedia(filename = "a.mov", duration = 10.0); val b = SourceMedia(filename = "b.mov", duration = 3.0); val c = SourceMedia(filename = "c.mov", duration = 2.0)
    var session = Project("Record here")
    session.addRecording(a); session.recordingInsertion = 4.0
    session.addRecording(b); session.addRecording(c)
    session.validate()
    require(session.history.size == 2 && session.edit.timeline.chronologicalVideo.map { it.content.sourceID } == listOf(a.id, b.id, c.id, a.id),
        "All recorded insertion segments form one ordered undo step")
    session = reopen(session)
    require(session.recordingInsertion != null, "An insertion in progress survives reopening")
    session.undo(); require(session.edit.clips.size == 1 && session.sources.size == 3, "Undoing Record here preserves every original recording")
    session.redo(); require(session.edit.outputSize == null, "Record insertion keeps the automatic canvas through reopen and redo")

    // Sites: a picture gap of 6 s after 4 s, under a 16 s soundtrack.
    val left = SourceMedia(filename = "left.mov", duration = 4.0); val right = SourceMedia(filename = "right.mov", duration = 2.0)
    val short = SourceMedia(filename = "short.mov", duration = 2.0); val long = SourceMedia(filename = "long.mov", duration = 8.0)
    val bed = SourceMedia(filename = "bed.m4a", duration = 16.0, audioOnly = true)
    val project = Project("Video insertion sites")
    project.addRecording(left); project.addRecording(right); project.sources = project.sources + listOf(short, long, bed)
    val gapped = project.edit
    gapped.timeline.insertTime(t(6.0), t(4.0)); gapped.timeline.insertAudio(bed, EditTime.zero)
    project.commit(gapped)
    val original = project.edit
    val gap = VideoInsertionSite.Gap(t(4.0))
    fun start(edit: EditState, source: SourceMedia) = edit.timeline.video.firstOrNull { it.content.sourceID == source.id }?.start?.seconds
    fun clip(source: SourceMedia) = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration)
    val filled = TimelineEditing.inserting(listOf(clip(short), clip(short)), original, 8.0, site = gap)
    require(filled.timeline.video.map { it.start.seconds }.sorted() == listOf(0.0, 4.0, 6.0, 10.0), "Batch fills from the left edge, keeping the rest of the gap and the right clip")
    require(filled.timeline.audio.first { it.sourceID == bed.id } == original.timeline.audio.first { it.sourceID == bed.id }, "Audio crossing a picture gap stays intact when inserts fit")
    val overflow = TimelineEditing.inserting(listOf(clip(long)), original, 8.0, site = gap)
    require(start(overflow, long) == 4.0 && start(overflow, right) == 12.0, "Only overflow ripples at the right edge")
    require(overflow.timeline.audio.filter { it.sourceID == bed.id } == original.timeline.audio.filter { it.sourceID == bed.id }, "Overflow keeps an independent soundtrack continuous")
    val appended = TimelineEditing.inserting(listOf(clip(short)), original, 16.0, site = VideoInsertionSite.End)
    require(start(appended, short) == 12.0 && appended.duration == 16.0, "Append meets the last picture before an audio-only tail")
    val leading = original.copy(); leading.timeline.insertTime(t(5.0), EditTime.zero)
    val prepended = TimelineEditing.inserting(listOf(clip(short)), leading, 0.0, site = VideoInsertionSite.Start)
    require(start(prepended, short) == 3.0 && start(prepended, left) == 5.0, "Prepend backfills the lead-in flush against the first picture")
    val longPrepend = TimelineEditing.inserting(listOf(clip(long)), leading, 0.0, site = VideoInsertionSite.Start)
    require(start(longPrepend, long) == 0.0 && start(longPrepend, left) == 8.0, "A long prepend shifts only its excess")

    val ta = SourceMedia(filename = "take-a.mov", duration = 2.0); val tb = SourceMedia(filename = "take-b.mov", duration = 4.0)
    for (site in listOf(VideoInsertionSite.Start, gap, VideoInsertionSite.End)) {
        var recorded = Project("Sites"); recorded.addRecording(left); recorded.addRecording(right); recorded.sources = recorded.sources + listOf(bed)
        recorded.commit(if (site == VideoInsertionSite.Start) leading else original)
        val base = recorded.edit
        recorded.beginRecordingInsertion(0.0, site)
        recorded.addRecording(ta)
        recorded = reopen(recorded)
        recorded.addRecording(tb); recorded.endRecordingInsertion()
        val span = site.placement(base.timeline, t(6.0))
        require(start(recorded.edit, ta) == span.start.seconds && start(recorded.edit, tb) == span.start.seconds + 2, "Recorded segments match an ordered import after reload ($site)")
        recorded.validate()
        recorded.undo(); require(recorded.edit.timeline.video == base.timeline.video && recorded.edit.timeline.audio == base.timeline.audio, "Record here remains a single Undo ($site)")
    }
}
