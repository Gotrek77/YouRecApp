package yourec

private fun t(seconds: Double) = EditTime.seconds(seconds)

/// Ported from the iPhone app's transitionTests (timeline part), for the
/// dissolves and colour fades this version draws.
fun transitionTests() {
    val a = SourceMedia(filename = "source.mp4", duration = 4.0); val b = SourceMedia(filename = "portrait.mp4", duration = 4.0)
    val project = Project("Transitions"); project.addRecording(a); project.addRecording(b)
    val original = project.edit
    val dissolve = ClipTransition(duration = 1.0)
    val edit = TransitionLayout.setting(original, dissolve, 1)
    val joins = TransitionLayout.joins(edit.timeline)
    require(edit.duration == 7.0 && edit.timeline.chronologicalVideo[1].start == t(3.0) && joins[1].time == 3.5, "Overlap shifts the second clip and join by the correct amounts")
    require(edit.clips.map { it.start } == original.clips.map { it.start } && edit.clips.map { it.end } == original.clips.map { it.end }, "Adding an overlap never changes source trims")
    require(TimelineEditing.split(edit, 3.25) == edit, "Splitting inside an overlap is refused without modifying footage")
    val split = TimelineEditing.split(edit, 1.0)
    require(split.clips.size == 3 && split.duration == 7.0 && split.timeline.transitions.single().outgoing == split.timeline.chronologicalVideo[1].id,
        "Splits preserve the outgoing join and duration")
    val removed = TransitionLayout.setting(edit, null, 1)
    require(removed.timeline.transitions.isEmpty() && removed.timeline.video.map { it.start } == edit.timeline.video.map { it.start }, "Removing a transition retains explicit clip positions")
    require(TransitionLayout.restoringStraightCut(edit, 1).duration == original.duration, "Explicit straight-cut restoration recovers sequence duration")
    project.commit(edit); project.undo()
    val reopened = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(edit)))))
    require(reopened == edit, "Transition parameters survive reopening")
    project.redo()
    require(project.schemaVersion >= 6, "Older builds reject transition projects instead of silently changing their running time")
    project.validate()
    require(edit.timeline.audio.all { it.start == edit.timeline.video.first { v -> v.id == it.followsVideo }.start }, "Linked sound moves with its picture into the overlap")

    val slow = original.copy()
    slow.timeline.replaceContents(slow.timeline.video.associate { it.id to it.content.copy(speed = SpeedSettings(rate = 0.25)) })
    val longEdit = TransitionLayout.setting(slow, ClipTransition(duration = 12.0), 1)
    require(TransitionLayout.joins(longEdit.timeline)[1].maximum == 16.0 && longEdit.duration == 20.0, "Transitions extend beyond ten seconds up to available footage")
    require(TransitionLayout.restoringStraightCut(longEdit, 1).duration == 32.0, "Removing a long overlap restores all footage")
    val trimmed = edit.copy()
    trimmed.timeline.replaceContents(mapOf(trimmed.timeline.chronologicalVideo[0].id to trimmed.timeline.chronologicalVideo[0].content.copy(end = 0.5)))
    require(trimmed.timeline.transitions.isEmpty(), "A trim that removes required transition handles removes the invalid relation")
    val faster = edit.copy()
    faster.timeline.replaceContents(mapOf(faster.timeline.chronologicalVideo[0].id to faster.timeline.chronologicalVideo[0].content.copy(speed = SpeedSettings(rate = 2.0))))
    require(faster.timeline.transitions.size == 1 && Math.abs(faster.duration - 5.0) < 1e-6 && faster.timeline.chronologicalVideo[1].start == t(1.0),
        "A faster outgoing clip keeps its transition and pulls the next clip in (${faster.duration})")

    val fade = dissolve.copy(style = TransitionStyle.FadeColor)
    val faded = TransitionLayout.setting(original, fade, 1)
    val fadeJoin = TransitionLayout.joins(faded.timeline)[1]
    require(faded.duration == 8.0 && fadeJoin.range.start == 3.5 && fadeJoin.range.endInclusive == 4.5, "Fade through black retains timeline length")
    var ends = TransitionLayout.setting(original, ClipTransition(duration = 1.0), 0)
    ends = TransitionLayout.setting(ends, ClipTransition(duration = 1.0), 2)
    val endJoins = TransitionLayout.joins(ends.timeline)
    require(ends.duration == 8.0 && endJoins[0].range == 0.0..1.0 && endJoins[2].range == 7.0..8.0, "Black opening and closing retain the whole project")

    val third = SourceMedia(filename = "third.mp4", duration = 4.0)
    val three = Project("Chain"); three.addRecording(a); three.addRecording(b); three.addRecording(third)
    var chain = TransitionLayout.setting(three.edit, ClipTransition(duration = 3.0), 1)
    chain = TransitionLayout.setting(chain, ClipTransition(duration = 3.0), 2)
    val chained = TransitionLayout.joins(chain.timeline)
    val middle = chain.timeline.chronologicalVideo[1].id
    val incoming = chain.timeline.transitions.first { it.incoming == middle }.timing; val outgoing = chain.timeline.transitions.first { it.outgoing == middle }.timing
    require(incoming.rightCost + outgoing.leftCost <= 4.000001, "Adjacent transitions never require three simultaneous clips")
    require(chained[1].range.endInclusive <= chained[2].range.start + 0.000001, "Transition windows never cross")
    require(TimelineEditing.rangeSelection(3.2, 5.0, edit) == null, "A selection inside an overlap cannot silently modify excess footage")

    val pasted = TimelinePaste.copied(edit.timeline, edit.timeline.entireSelection())
    require(pasted.timeline.transitions.size == 1 && pasted.timeline.duration == t(7.0), "A transition travels with both selected partners and keeps the real overlap")
    val twice = edit.timeline.copy(); TimelinePaste.paste(twice, pasted, twice.duration)
    twice.validate(project.sources)
    require(twice.transitions.size == 2 && twice.transitions.map { it.id }.toSet().size == 2 && twice.duration == t(14.0), "A pasted transition has its own identity")
    val moved = edit.timeline.copy(); moved.move(moved.entireSelection(), t(2.0))
    require(moved.transitions.single().origin == edit.timeline.transitions.single().origin + t(2.0), "Moving both partners moves their transition")
    val closed = edit.timeline.copy(); closed.remove(closed.selection(closed.chronologicalVideo[0].id))
    require(closed.transitions.isEmpty() && closed.duration == t(4.0), "Deleting a partner removes the transition and its overlap")

    // What preview and export draw and play.
    val plan = RenderPlan(edit)
    val middle2 = plan.layers(t(3.5))
    require(middle2.size == 2 && Math.abs(middle2[1].alpha - 0.5f) < 1e-6 && middle2[0].alpha == 1f && plan.layers(t(2.5)).size == 1 && plan.layers(t(4.5)).single().alpha == 1f,
        "A dissolve draws the incoming picture over the outgoing one, half way at its middle")
    val fadePlan = RenderPlan(faded)
    require(Math.abs(fadePlan.wash(t(4.0))!!.alpha - 1f) < 1e-3 && Math.abs(fadePlan.wash(t(3.75))!!.alpha - 0.5f) < 1e-3 && fadePlan.wash(t(3.0)) == null
        && fadePlan.layers(t(3.75)).single().picture.item == faded.timeline.chronologicalVideo[0].id, "Fade through black reaches its colour at the cut")
    val endPlan = RenderPlan(ends)
    require(Math.abs(endPlan.wash(EditTime.zero)!!.alpha - 1f) < 1e-6 && endPlan.wash(t(1.0)) == null && Math.abs(endPlan.wash(t(7.999))!!.alpha - 1f) < 1e-3, "Opening and closing fades start and end on their colour")
    val constant = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) { java.util.Arrays.fill(into, offset, offset + frames * 2, 0.25f) } }
    val mix = AudioMix(edit.timeline) { constant }
    val out = FloatArray(7 * 48000 * 2); mix.render(0, out, 7 * 48000)
    require((3 * 48000 + 100 until 4 * 48000 - 100 step 480).all { Math.abs(out[it * 2] - 0.25f) < 0.002 }, "Sound crossfades through a dissolve at constant level")
    require(Math.abs(out[(3 * 48000 + 24000) * 2] - 0.25f) < 0.002 && Math.abs(out[48000 * 2] - 0.25f) < 1e-6, "Each side carries half at the middle; outside the transition the level is unchanged")

    val progress = TransitionTiming(dissolve)
    require(progress.progress(0.0) == 0.0 && progress.progress(0.5) == 0.5 && progress.progress(1.0) == 1.0, "Progress runs across the transition")
    require(ClipTransition(style = TransitionStyle.Wipe).unsupported == "Wipe transitions" && dissolve.unsupported == null && fade.copy(fadeColor = TransitionColor(0.1, 0.8, 0.2)).unsupported == null,
        "Unported styles are named; dissolves and colour fades are drawn")
}
