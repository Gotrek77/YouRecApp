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
    val halfway = plan.transition(t(3.5))!!
    require(halfway.first?.picture?.item == edit.timeline.chronologicalVideo[0].id && halfway.second?.picture?.item == edit.timeline.chronologicalVideo[1].id
        && Math.abs(halfway.progress - 0.5) < 1e-6 && plan.layers(t(3.5)).size == 2 && plan.transition(t(2.5)) == null && plan.layers(t(4.5)).size == 1,
        "A dissolve mixes both partners, half way at its middle")
    val fadePlan = RenderPlan(faded)
    val gap = fadePlan.transition(t(4.0))!!
    require(Math.abs(gap.progress - 0.5) < 1e-3 && gap.settings.style == TransitionStyle.FadeColor && fadePlan.transition(t(3.75))!!.first != null && fadePlan.transition(t(3.0)) == null,
        "Fade through black reaches its colour at the cut")
    val endPlan = RenderPlan(ends)
    val opening = endPlan.transition(EditTime.zero)!!
    require(opening.first == null && opening.second != null && opening.settings.style == TransitionStyle.Dissolve && opening.progress == 0.0 && endPlan.transition(t(1.0)) == null,
        "An opening fade is a dissolve from its colour")

    // Pixels, as the iPhone checks them (y up, 160 x 90).
    val red = { _: Double, _: Double -> floatArrayOf(1f, 0f, 0f) }; val blue = { _: Double, _: Double -> floatArrayOf(0f, 0f, 1f) }
    fun px(settings: ClipTransition, progress: Double, x: Double, y: Double, first: (Double, Double) -> FloatArray = red, second: (Double, Double) -> FloatArray = blue) =
        TransitionMix.pixel(settings, progress, x, y, 160.0, 90.0, first, second)
    val pair = dissolve.copy(style = TransitionStyle.Split, divider = 0.35)
    require(px(pair, 0.5, 20.0, 40.0)[0] > 0.94f && px(pair, 0.5, 100.0, 40.0)[2] > 0.94f, "Split screen puts outgoing left and incoming right")
    val vertical = pair.copy(axis = TransitionAxis.Vertical)
    require(px(vertical, 0.5, 50.0, 80.0)[0] > 0.94f && px(vertical, 0.5, 50.0, 20.0)[2] > 0.94f, "Vertical split uses outgoing top and incoming bottom")
    require(px(fade, 0.5, 40.0, 40.0).all { it < 0.01f }, "Fade through black midpoint is black")
    val green = px(fade.copy(fadeColor = TransitionColor(0.1, 0.8, 0.2)), 0.5, 40.0, 40.0)
    require(green[1] > 0.7f && green[0] < 0.2f && green[2] < 0.32f, "Colour fade midpoint uses the selected colour")
    val wipe = ClipTransition(style = TransitionStyle.Wipe, duration = 1.0, hardEdge = true, wipeAngle = 45.0)
    require(px(wipe, 0.5, 20.0, 80.0)[2] > 0.94f && px(wipe, 0.5, 140.0, 10.0)[0] > 0.94f, "Hard wipe follows its configured diagonal")
    val brightness = { x: Double, _: Double -> if (x < 80) floatArrayOf(0.1f, 0.1f, 0.1f) else floatArrayOf(3f, 3f, 3f) }
    val hitchcock = ClipTransition(style = TransitionStyle.Hitchcock, duration = 1.0)
    require(px(hitchcock, 0.5, 20.0, 40.0, brightness)[2] > 0.94f && px(hitchcock, 0.5, 120.0, 40.0, brightness)[0] > 0.94f, "Hitchcock reveals shadows before highlights")
    require(px(hitchcock, 0.99, 120.0, 40.0, brightness)[0] < 0.02f, "HDR highlights reveal before the final frame, without a last-frame pop")
    val highlights = hitchcock.copy(highlightsFirst = true)
    require(px(highlights, 0.5, 120.0, 40.0, brightness)[0] < 0.02f && px(highlights, 0.5, 20.0, 40.0, brightness)[2] < 0.4f, "Hitchcock reverses to highlights first")
    val flashes = ClipTransition(style = TransitionStyle.FlashCut, duration = 1.0)
    for (frame in 0 until 30) require((px(flashes, frame / 30.0, 40.0, 40.0)[2] > 0.94f) == (frame in TransitionMix.flashFrames(flashes, 1.0)), "Flash Cut uses exact single frames of the next scene ($frame)")

    val constant = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) { java.util.Arrays.fill(into, offset, offset + frames * 2, 0.25f) } }
    val mix = AudioMix(edit.timeline) { constant }
    val out = FloatArray(7 * 48000 * 2); mix.render(0, out, 7 * 48000)
    require((3 * 48000 + 100 until 4 * 48000 - 100 step 480).all { Math.abs(out[it * 2] - 0.25f) < 0.002 }, "Sound crossfades through a dissolve at constant level")
    require(Math.abs(out[(3 * 48000 + 24000) * 2] - 0.25f) < 0.002 && Math.abs(out[48000 * 2] - 0.25f) < 1e-6, "Each side carries half at the middle; outside the transition the level is unchanged")

    val progress = TransitionTiming(dissolve)
    require(progress.progress(0.0) == 0.0 && progress.progress(0.5) == 0.5 && progress.progress(1.0) == 1.0, "Progress runs across the transition")
    require(TransitionStyle.values().all { ClipTransition(style = it).unsupported == null }, "Every iPhone transition style is drawn")

    // Holds, stills and sound timing, from the iPhone app's expanded checks.
    val pair2 = Project("Expanded"); pair2.addRecording(a); pair2.addRecording(b)
    val base = pair2.edit
    fun candidate(settings: ClipTransition, boundary: Int = 1) = TransitionLayout.setting(base, settings, boundary)
    var opening2 = base.copy(); opening2.timeline.unlink(opening2.timeline.selection(opening2.timeline.video[0].id))
    val starts = opening2.timeline.video.map { it.start }; val soundStarts = opening2.timeline.audio.map { it.start }; val originalDuration = opening2.duration
    for (offset in listOf(-0.5, -0.2, 0.0, -0.5)) {
        opening2 = TransitionLayout.setting(opening2, ClipTransition(duration = 1.0, audioOffset = offset, audioBlend = 0.0), 0)
        require(opening2.timeline.video.map { it.start } == starts && opening2.timeline.audio.map { it.start } == soundStarts && opening2.duration == originalDuration,
            "Re-editing an opening J-cut stays anchored without gaps or detached audio drift")
    }
    val hold = ClipTransition(duration = 1.0, hold = 2.0)
    val held = candidate(hold); val heldJoin = TransitionLayout.joins(held.timeline)[1]
    require(Math.abs(held.duration - 5) < 0.00001 && heldJoin.timing.progress(1.0) == 0.5 && heldJoin.timing.progress(2.0) == 0.5, "Midpoint hold adds to the moving overlap and retains the 50/50 mix")
    val plateau = candidate(hold.copy(style = TransitionStyle.FadeColor, fadeColor = TransitionColor(0.2, 0.7, 0.4)))
    require(plateau.duration == 10.0, "Solid colour hold adds time without consuming footage (${plateau.duration})")
    val freeze = ClipTransition(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5)
    val still = candidate(freeze)
    require(still.duration == 10.0 && TransitionLayout.joins(still.timeline)[1].videoRange == 4.0..6.5, "Still holds the final frame before dissolving into the next scene")
    require(candidate(ClipTransition(style = TransitionStyle.Still, duration = 1.0), 0).duration == 9.0, "Opening still holds black before source footage")
    require(candidate(freeze, 2).duration == 10.5, "Closing still includes the final fade to black")
    val frozen = RenderPlan(still).transition(t(5.0))!!
    require(frozen.first?.picture?.item == still.timeline.chronologicalVideo[0].id && frozen.second == null && Math.abs(frozen.first!!.picture.sourceTime - 4.0) < 0.01 && frozen.progress == 0.0,
        "During a still the outgoing picture's last frame is held")
    for (style in TransitionStyle.values()) for ((offset, blend) in listOf(-1.0 to 0.5, 1.5 to 0.5)) {
        val p = candidate(ClipTransition(style = style, duration = 1.0, audioOffset = offset, audioBlend = blend))
        val j = TransitionLayout.joins(p.timeline)[1]
        require(j.audioIn == j.audioOut, "Every style permits a continuous custom audio crossfade ($style)")
        if (offset < 0) require(j.audioIn.endInclusive < j.videoRange.start, "J-cut finishes its audio blend before the picture transition ($style)")
        else require(j.audioOut.start > j.videoRange.endInclusive, "L-cut retains outgoing sound after the picture change ($style)")
        p.timeline.validate(pair2.sources)
    }
}
