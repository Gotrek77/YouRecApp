package yourec

import java.util.UUID

private fun t(seconds: Double) = EditTime.seconds(seconds)

/// Ported from the iPhone app's timelineClipboardChecks. Transitions,
/// subtitles and audio enhancements are not ported and are left out.
fun clipboardTests() {
    val source = SourceMedia(filename = "clipboard.mov", duration = 10.0)
    val clips = (0 until 5).map { TimelineClip(sourceID = source.id, start = it * 2.0, end = it * 2.0 + 2) }
    val base = EditState(clips).timeline
    fun boundary(timeline: TrackTimeline, second: Double, kind: TimelinePasteTarget.Kind = TimelinePasteTarget.Kind.Picture) =
        TimelinePaste.targets(timeline).firstOrNull { it.kind == kind && it.isAt(t(second)) }
    fun order(timeline: TrackTimeline) = timeline.chronologicalVideo.map { (it.content.start / 2).toInt() }
    val indices = clips.indices.toList()
    for (mask in 1 until (1 shl clips.size)) {
        val chosen = indices.filter { mask and (1 shl it) != 0 }; val kept = indices.filter { it !in chosen }
        val scope = base.selection(chosen.map { clips[it].id }.toSet())
        val copy = TimelinePaste.copied(base, scope)
        require(copy.timeline.duration == t(chosen.size * 2.0), "Disjoint scenes must pack in playback order")
        for (slot in 0..clips.size) {
            val result = base.copy()
            val added = TimelinePaste.paste(result, copy, t(slot * 2.0), boundary(result, slot * 2.0))
            require(order(result) == indices.take(slot) + chosen + indices.drop(slot) && added.ids.none { it in base.allIDs },
                "Copy into slot $slot of mask $mask must keep the originals")
            result.validate(listOf(source))
        }
        val lifted = base.copy()
        val cut = TimelinePaste.cut(lifted, scope, EditTime.zero) ?: error("Cut unexpectedly empty")
        require(order(lifted) == kept, "Cut removes only the selected scenes")
        for (slot in 0..kept.size) {
            val result = lifted.copy()
            TimelinePaste.paste(result, cut.first, t(slot * 2.0), boundary(result, slot * 2.0))
            require(order(result) == kept.take(slot) + chosen + kept.drop(slot) && result.duration == base.duration,
                "Cut and paste must reconstruct a complete sequence, including an empty destination (mask $mask, slot $slot)")
            result.validate(listOf(source))
        }
    }

    base.addBookmark(t(8.75))
    base.setFade(AudioFadeEdge.FadeIn, 0.25, TimelineSelection(audio = setOf(base.audio[4].id)))
    val title = TextOverlay(text = "Last scene", start = 8.0, end = 9.0)
    base.video = base.video.mapIndexed { i, item -> if (i == 4) item.copy(content = item.content.copy(texts = listOf(title))) else item }
    val late = TimelinePaste.copied(base, base.selection(clips[4].id))
    val layered = base.copy()
    val firstPaste = TimelinePaste.paste(layered, late, EditTime.zero, boundary(layered, 0.0))
    val secondPaste = TimelinePaste.paste(layered, late, layered.duration, boundary(layered, layered.duration.seconds))
    require(firstPaste.ids.none { it in secondPaste.ids } && layered.resolvedBookmarks.map { it.time }.sorted() == listOf(t(0.75), t(10.75), t(12.75)),
        "Each paste has its own media, mark, and edit identities")
    val audio = layered.audio.first { it.id in firstPaste.audio }; val video = layered.video.first { it.id in firstPaste.video }
    require(audio.fadeIn == base.audio[4].fadeIn && video.content.texts?.first()?.text == "Last scene" && video.content.texts?.first()?.id != title.id,
        "Pasted titles and fades remain editable independently")

    val range = base.copy()
    val piece = TimelinePaste.cut(range, base.entireSelection(EditInterval(t(1.0), t(3.0))), t(8.0))!!
    require(piece.second == t(6.0) && range.duration == t(8.0) && piece.first.timeline.duration == t(2.0),
        "Cut and the clipboard must use the same exact range fragments")
    val project = Project("Clipboard history"); project.sources = listOf(source)
    project.history = listOf(EditState(clips)); project.commit(EditState(base.copy())); project.commit(EditState(range.copy()))
    TimelinePaste.paste(range, piece.first, t(0.5)); project.commit(EditState(range.copy()))
    val reopened = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(project.edit)))))
    require(reopened == project.edit, "A pasted edit round-trips through the document")
    project.undo(); require(project.edit.timeline.duration == t(8.0), "Undo Paste")
    project.undo(); require(project.edit.timeline == base, "Undo Cut restores the source and its edits")
    project.redo(); project.redo(); require(project.edit.timeline == range, "Redo Cut and Paste")

    val detached = base.copy(); detached.unlink(detached.entireSelection())
    val sound = TimelinePaste.copied(detached, detached.selection(detached.audio[4].id))
    val atPicture = detached.copy(); val atSound = detached.copy()
    TimelinePaste.paste(atPicture, sound, t(2.0), boundary(detached, 2.0))
    TimelinePaste.paste(atSound, sound, t(2.0), boundary(detached, 2.0, TimelinePasteTarget.Kind.Sound))
    require(atPicture.video == detached.video && atPicture.audio.take(5) == detached.audio && atSound.video == detached.video && atSound.audio[1].start == t(4.0),
        "Choose layering on picture or inserting into the sound sequence")
    val mix = base.copy()
    val score = mix.insertAudio(source, EditTime.zero)
    mix.split(t(1.0), TimelineSelection(audio = setOf(score)))
    TimelinePaste.paste(mix, late, t(1.0), boundary(mix, 1.0, TimelinePasteTarget.Kind.Sound))
    require(mix.video.any { it.occurrenceID == base.video[0].occurrenceID && it.start == t(3.0) } && mix.audio.first { it.id == score }.start == EditTime.zero,
        "Paste picture at a sound cut preserves detached music and splits dialogue in sync")

    // The dissolve cases of the iPhone checks.
    val dissolve = ClipTransition(duration = 0.5)
    val blend = TransitionLayout.setting(EditState(base.copy()), dissolve, 2)
    val join = TransitionLayout.joins(blend.timeline).first { it.transition?.id == dissolve.id }
    val separated = blend.timeline.copy()
    TimelinePaste.paste(separated, late, t(join.time), boundary(separated, join.time))
    require(separated.transitions.isEmpty() && separated.duration == t(12.0), "Pasting picture into a dissolve recovers both source handles (${separated.duration.seconds})")
    val both = TimelinePaste.copied(blend.timeline, blend.timeline.selection(setOf(clips[1].id, clips[2].id)))
    val copiedBlend = base.copy(); TimelinePaste.paste(copiedBlend, both, copiedBlend.duration)
    require(copiedBlend.transitions.size == 1 && copiedBlend.transitions[0].id != dissolve.id && both.timeline.duration == t(3.5),
        "An internal transition travels with both selected partners and keeps the real overlap")
    copiedBlend.validate(listOf(source))
    val inside = blend.timeline.copy(); TimelinePaste.paste(inside, late, t(join.time - 0.1))
    require(inside.transitions.isEmpty() && inside.duration == t(12.0), "A mid-transition scene paste retains all source handles (${inside.duration.seconds})")
    inside.validate(listOf(source))

    val spaced = base.copy()
    spaced.move(spaced.selection(clips.takeLast(2).map { it.id }.toSet()), t(2.0))
    val marked = TimelinePaste.copied(spaced, spaced.entireSelection(EditInterval(t(4.0), t(10.0))))
    require(marked.timeline.duration == t(6.0) && marked.timeline.chronologicalVideo.map { it.start } == listOf(EditTime.zero, t(4.0)),
        "A marked range retains the intentional gap between included clips")
    val bed = base.copy()
    val scoreID = bed.insertAudio(source, EditTime.zero)
    val beforeMusic = bed.audio.first { it.id == scoreID }
    TimelinePaste.paste(bed, late, t(1.5))
    require(bed.audio.first { it.id == scoreID } == beforeMusic, "A mid-shot scene paste must leave detached music continuous")

    val arrangement = base.copy()
    val pip = VideoTimelineItem(UUID.randomUUID(), t(8.5), TimelineClip(sourceID = source.id, start = 0.0, end = 1.0), layer = 5)
    val third = VideoTimelineItem(UUID.randomUUID(), t(8.6), TimelineClip(sourceID = source.id, start = 0.0, end = 0.4), layer = 6)
    arrangement.video = arrangement.video + listOf(pip, third)
    val window = VideoPlacement(width = 0.4, order = 2)
    arrangement.placements = listOf(VideoPlacementRegion(participants = setOf(clips[4].id, pip.id, third.id),
        videos = mapOf(clips[4].id to VideoPlacement(), pip.id to window, third.id to VideoPlacement())))
    val parts = TimelinePaste.copied(arrangement, arrangement.selection(setOf(clips[4].id, pip.id)))
    require(parts.timeline.video.first { it.id == pip.id }.start == t(0.5) && parts.timeline.placements.firstOrNull()?.videos?.get(pip.id) == window,
        "Overlapping picks retain offset and custom framing")
    require(TimelinePaste.targets(EditState(emptyList()).timeline).isEmpty() && TimelinePaste.cut(base.copy(), TimelineSelection(), EditTime.zero) == null,
        "Nothing to paste into an empty edit; nothing to cut without a selection")
}
