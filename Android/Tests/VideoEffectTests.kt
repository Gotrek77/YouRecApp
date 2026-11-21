package yourec

import java.util.UUID
import kotlin.math.abs

/// The iPhone app's video effect model: catalogue, keyframes in source time,
/// order, validation, and the project form (including Swift's enum coding).
fun videoEffectTests() {
    require(VideoEffectCatalog.all.size == 24 && VideoEffectCatalog.all.map { it.id }.toSet() == VideoEffectID.values().toSet(), "Every effect kind has its descriptor")
    val tint = VideoEffectSettings(kind = VideoEffectID.silentTint)
    require(tint.parameters.first().id == "intensity" && tint.number("intensity") == 1.0 && tint.choice("tint") == "Amber" && tint.valid, "Defaults come from the catalogue, intensity first")
    require(VideoEffectSettings(kind = VideoEffectID.freezeFrame).parameters.none { it.id == "intensity" }, "Time effects have no intensity")
    require(VideoEffectSettings(kind = VideoEffectID.chromaKey).algorithm == 2 && VideoEffectSettings(kind = VideoEffectID.noir).algorithm == 1, "New keys use the temporal matte")

    // Keyframes: continuous values interpolate, choices hold, angles take the short way round.
    var exposure = VideoEffectSettings.defaults(VideoEffectID.colorCorrection).first { it.id == "exposure" }
    exposure = exposure.settingKeyframe(1.0, EffectValue.Number(0.0)).settingKeyframe(3.0, EffectValue.Number(2.0))
    require(exposure.value(2.0) == EffectValue.Number(1.0) && exposure.value(0.0) == EffectValue.Number(0.0) && exposure.value(9.0) == EffectValue.Number(2.0), "Sliders interpolate between keyframes")
    var ratio = VideoEffectSettings.defaults(VideoEffectID.widescreen).first { it.id == "ratio" }
    ratio = ratio.settingKeyframe(0.0, EffectValue.Choice("2.39:1")).settingKeyframe(2.0, EffectValue.Choice("1.85:1"))
    require(ratio.value(1.9) == EffectValue.Choice("2.39:1"), "Choices hold until the next keyframe")
    var angle = VideoEffectSettings.defaults(VideoEffectID.kaleidoscope).first { it.id == "splitAngle" }
    angle = angle.settingKeyframe(0.0, EffectValue.Number(170.0)).settingKeyframe(1.0, EffectValue.Number(-170.0))
    require(abs((angle.value(0.5) as EffectValue.Number).value - 180.0).let { it < 1e-9 || abs((angle.value(0.5) as EffectValue.Number).value + 180) < 1e-9 }, "Rotation takes the short way round")
    require(exposure.settingKeyframe(1.0, EffectValue.Number(99.0)) == exposure, "Values out of bounds are refused")

    // Order: Night fix first, families in order, Color Correction first among colors, Kaleidoscope last of camera.
    val ordered = VideoEffectSettings.ordered(listOf(VideoEffectID.kaleidoscope, VideoEffectID.noir, VideoEffectID.chromaKey, VideoEffectID.colorCorrection, VideoEffectID.nightFix, VideoEffectID.crashZoom)
        .map { VideoEffectSettings(kind = it) }).map { it.kind }
    require(ordered == listOf(VideoEffectID.nightFix, VideoEffectID.chromaKey, VideoEffectID.crashZoom, VideoEffectID.kaleidoscope, VideoEffectID.colorCorrection, VideoEffectID.noir), "Effects apply in the iPhone app's order ($ordered)")

    // Strobe: 8 Hz flips every 1/16 s.
    val strobe = VideoEffectSettings(kind = VideoEffectID.negativeStrobe, start = 0.0)
    require(!strobe.strobeShowsNegative(0.01, 30.0) && strobe.strobeShowsNegative(0.07, 30.0) && !strobe.strobeShowsNegative(0.13, 30.0), "Negative Strobe alternates at its rate")

    // Project form: Swift's enum coding, unknown kinds skipped, effects not yet drawn refused by name.
    val sepia = tint.setting("tint", EffectValue.Choice("Sepia"))
    val effect = sepia.copy(parameters = sepia.parameters.map { if (it.id == "contrast") it.settingKeyframe(2.0, EffectValue.Number(1.3)) else it })
    val encoded = String(JSON.write(ProjectCoding.encode(effect)))
    require(encoded.contains("{\"choice\":{\"_0\":\"Sepia\"}}") && encoded.contains("{\"slider\":{"), "Values and kinds use Swift's enum form ($encoded)")
    val unknown = mapOf("kind" to "futureEffect", "id" to UUID.randomUUID().toString())
    val decoded = ProjectCoding.videoEffects(listOf(JSON.parse(encoded.toByteArray()), unknown))
    require(decoded == listOf(effect), "A drawn effect round-trips; an unknown kind is skipped, as on iPhone")
    val halation = String(JSON.write(ProjectCoding.encode(VideoEffectSettings(kind = VideoEffectID.chromaKey))))
    requireFailure<ProjectError.UnsupportedTools>("An effect this build cannot draw is refused by name") { ProjectCoding.videoEffects(listOf(JSON.parse(halation.toByteArray()))) }
    try { ProjectCoding.videoEffects(listOf(JSON.parse(halation.toByteArray()))) } catch (e: ProjectError.UnsupportedTools) { require(e.message!!.contains("Chroma"), "The refusal names the effect") }
    effectRenderingTests()
    effectEditingTests()
    effectTimingTests()
    effectSubjectTests()
    effectPaletteTests()
    effectMatteTests()
    println("Video effects: ok")
}

/// What the renderer draws at one source time, and the uniforms it draws with.
fun effectRenderingTests() {
    val clipID = UUID.randomUUID()
    fun clip(vararg effects: VideoEffectSettings, windows: Map<UUID, List<SourceInterval>>? = null) =
        TimelineClip(clipID, UUID.randomUUID(), 0.0, 10.0, videoEffects = effects.toList(), effectWindows = windows)
    val noir = VideoEffectSettings(kind = VideoEffectID.noir, start = 1.0, end = 5.0)
    val kaleidoscope = VideoEffectSettings(kind = VideoEffectID.kaleidoscope)
    val correction = VideoEffectSettings(kind = VideoEffectID.colorCorrection)
    // Camera before Color; Color Correction first among colours, as on iPhone.
    require(VideoEffectRendering.active(clip(noir, kaleidoscope, correction), 2.0, 30.0).map { it.kind } == listOf(VideoEffectID.kaleidoscope, VideoEffectID.colorCorrection, VideoEffectID.noir), "Pixel order follows the iPhone app's stages")
    require(VideoEffectRendering.active(clip(noir), 0.5, 30.0).isEmpty() && VideoEffectRendering.active(clip(noir), 5.0, 30.0).isEmpty() && VideoEffectRendering.active(clip(noir), 1.0, 30.0).size == 1, "An effect shows within start..<end")
    require(VideoEffectRendering.active(clip(noir.copy(enabled = false)), 2.0, 30.0).isEmpty(), "A disabled effect is not drawn")
    require(VideoEffectRendering.active(clip(noir.setting("intensity", EffectValue.Number(0.0))), 2.0, 30.0).isEmpty(), "Zero intensity is not drawn")
    val windowed = clip(noir, windows = mapOf(noir.id to listOf(SourceInterval(3.0, 4.0))))
    require(VideoEffectRendering.active(windowed, 2.0, 30.0).isEmpty() && VideoEffectRendering.active(windowed, 3.5, 30.0).size == 1 && VideoEffectRendering.active(windowed, 4.0, 30.0).isEmpty(), "A ranged effect shows only in its windows")
    require(VideoEffectRendering.active(clip(VideoEffectSettings(kind = VideoEffectID.rgbOffset)), 1.0, 30.0).isEmpty(), "RGB Offset at rest draws nothing")
    require(VideoEffectRendering.active(clip(VideoEffectSettings(kind = VideoEffectID.rgbOffset).setting("redX", EffectValue.Number(0.2))), 1.0, 30.0).size == 1, "An offset channel is drawn")
    val strobe = VideoEffectSettings(kind = VideoEffectID.negativeStrobe)
    require(VideoEffectRendering.active(clip(strobe), 0.01, 30.0).isEmpty() && VideoEffectRendering.active(clip(strobe), 0.07, 30.0).size == 1, "Negative Strobe is drawn only on its negative half-cycles")

    // Uniforms: catalogue order without intensity; toggles 0/1; choices by index; colours linear.
    val tint = VideoEffectSettings(kind = VideoEffectID.silentTint).setting("tint", EffectValue.Choice("Sepia")).setting("intensity", EffectValue.Number(0.25))
    val options = (VideoEffectID.silentTint.descriptor.parameters.first { it.id == "tint" }.kind as EffectParameterKind.Picker).options
    val u = VideoEffectRendering.uniforms(tint.copy(start = 2.0), 3.5, 30.0)
    require(u.kind == 0 && u.p[0] == options.indexOf("Sepia").toFloat() && u.intensity == 0.25f && abs(u.time - 1.5f) < 1e-6 && u.frame == 105, "Silent Tint uniforms (${u.p[0]}, ${u.time}, ${u.frame})")
    val cc = VideoEffectRendering.uniforms(correction.setting("contrast", EffectValue.Number(1.4)).setting("hue", EffectValue.Number(-30.0)), 0.0, 30.0)
    require(cc.kind == 20 && cc.p[0] == 0f && cc.p[4] == 1.4f && cc.p[6 * 4] == 0.5f && cc.p[11 * 4] == -30f && cc.p[9 * 4] == 1f, "Color Correction parameters land in p[0...11]")
    val n = VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.noir).setting("grain", EffectValue.Toggle(true)), 0.0, 30.0)
    require(n.p[2 * 4] == 1f, "Toggles are 0 or 1")
    val isolation = VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.colorIsolation).setting("color", EffectValue.Color(EffectRGB(1.0, 0.5, 0.0))), 0.0, 30.0)
    require(isolation.p[4] == 1f && abs(isolation.p[5] - 0.21404114f) < 1e-6 && isolation.p[6] == 0f && isolation.p[7] == 1f, "Colours are linear RGB with w = 1")
    val k = VideoEffectRendering.uniforms(kaleidoscope, 0.0, 30.0)
    require(k.intensity == 1f && k.p[0] == (kaleidoscope.number("divisions")).toFloat(), "Camera effects have no intensity")
    require(VideoEffectRendering.frameIndex(1.0 / 30 * 7, 30.0) == 7, "Frame index rounds a frame time down exactly")
    // The grain tile: values centred on zero, monochrome in A.
    val lattice = VideoEffectRendering.lattice()
    require(lattice.size == VideoEffectRendering.latticeSize * VideoEffectRendering.latticeSize * 4, "Lattice size")
    val mean = (0 until lattice.size / 4).sumOf { (lattice[it * 4 + 3].toInt() and 255).toDouble() } / (lattice.size / 4)
    require(abs(mean - 127.5) < 1.0, "Grain is centred ($mean)")
    require(VideoEffectRendering.latticeOffset(1) != VideoEffectRendering.latticeOffset(2), "Each frame reads the grain elsewhere")
    // The iPhone shader's hashes on unsigned 32-bit arithmetic (values from an independent computation).
    require(VideoEffectRendering.hash32(123456).toLong() and 0xffffffffL == 3173061056L, "hash32")
    require(VideoEffectRendering.random01(4, 7, 3) == 0.7740641832351685 && VideoEffectRendering.random01(0, 12, 3) == 0.8658458590507507, "random01")
    for (sigma in listOf(0.3f, 1f, 2.5f, 7f, 15.9f)) {
        val w = VideoEffectRendering.gaussianWeights(sigma)
        val sum = w[0] + 2 * (0 until w[1].toInt()).sumOf { w[3 + it * 2].toDouble() }
        require(abs(sum - 1) < 1e-5 && w[1] <= 24, "Gaussian weights sum to one ($sigma: $sum)")
    }
    val weave = VideoEffectRendering.frameValues(VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.sixteenMM), 1.0, 30.0))
    val still = VideoEffectRendering.frameValues(VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.sixteenMM).setting("weave", EffectValue.Number(0.0)), 1.0, 30.0))
    require((weave[0] != 0f || weave[1] != 0f) && still[0] == 0f && still[1] == 0f && weave[2] == 1.006f, "Gate weave moves the picture by its amount")
}

/// The effects editor's changes: add, enable, controls with and without animation, keyframes, removal.
fun effectEditingTests() {
    var clip = TimelineClip(sourceID = UUID.randomUUID(), start = 2.0, end = 12.0)
    clip = VideoEffectEditing.adding(clip, VideoEffectID.noir)
    require(VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.let { it.start == 2.0 && it.end == 12.0 }, "A new effect covers its clip's source range, as on iPhone")
    require(VideoEffectEditing.adding(clip, VideoEffectID.noir) == clip && clip.videoEffects!!.single().kind == VideoEffectID.noir, "One effect of a kind per clip")
    clip = VideoEffectEditing.setting(clip, VideoEffectID.noir, "contrast", EffectValue.Number(2.0), 5.0, animate = false)
    val noir = VideoEffectEditing.effect(clip, VideoEffectID.noir)!!
    require(noir.number("contrast") == 2.0 && noir.parameters.all { it.keyframes.isEmpty() }, "A still control sets the value")
    require(VideoEffectEditing.setting(clip, VideoEffectID.noir, "contrast", EffectValue.Number(99.0), 5.0, false) == clip, "Out-of-range values are refused")
    // Animate: the old value is held at the clip's start, the new one keyed at the playhead.
    clip = VideoEffectEditing.setting(clip, VideoEffectID.noir, "contrast", EffectValue.Number(1.0), 6.0, animate = true)
    var contrast = VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.parameters.first { it.id == "contrast" }
    require(contrast.keyframes.map { it.time } == listOf(2.0, 6.0) && contrast.value(2.0) == EffectValue.Number(2.0) && contrast.value(4.0) == EffectValue.Number(1.5), "Animate keys the change ($contrast)")
    // With animation, a still change edits the key at the playhead.
    clip = VideoEffectEditing.setting(clip, VideoEffectID.noir, "contrast", EffectValue.Number(1.2), 6.0, animate = false)
    contrast = VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.parameters.first { it.id == "contrast" }
    require(contrast.keyframes.size == 2 && contrast.value(6.0) == EffectValue.Number(1.2), "A change never erases animation")
    clip = VideoEffectEditing.togglingKeyframe(clip, VideoEffectID.noir, "contrast", 6.0)
    require(VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.parameters.first { it.id == "contrast" }.keyframes.map { it.time } == listOf(2.0), "The diamond removes the key at the playhead")
    clip = VideoEffectEditing.togglingKeyframe(clip, VideoEffectID.noir, "contrast", 9.0)
    require(VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.parameters.first { it.id == "contrast" }.keyframes.map { it.time } == listOf(2.0, 9.0), "The diamond adds a key with the value shown")
    clip = VideoEffectEditing.enabling(clip, VideoEffectID.noir, false)
    require(!VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.enabled && VideoEffectRendering.active(clip, 5.0, 30.0).isEmpty(), "A disabled effect stays but is not drawn")
    clip = VideoEffectEditing.resetting(clip, VideoEffectID.noir)
    require(VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.parameters == VideoEffectSettings.defaults(VideoEffectID.noir), "Reset returns to the catalogue")
    val id = VideoEffectEditing.effect(clip, VideoEffectID.noir)!!.id; val title = UUID.randomUUID()
    clip = clip.copy(effectWindows = mapOf(id to listOf(SourceInterval(3.0, 4.0)), title to listOf(SourceInterval(5.0, 6.0))))
    clip = VideoEffectEditing.removing(clip, VideoEffectID.noir)
    require(clip.videoEffects == null && clip.effectWindows == mapOf(title to listOf(SourceInterval(5.0, 6.0))), "Removing an effect takes only its windows")
    // On the timeline: every selected clip changes; keys go only where the playhead is.
    val edit = EditState(TrackTimeline(listOf(VideoTimelineItem(UUID.randomUUID(), EditTime.zero, TimelineClip(sourceID = UUID.randomUUID(), start = 0.0, end = 4.0)),
        VideoTimelineItem(UUID.randomUUID(), EditTime.seconds(4.0), TimelineClip(sourceID = UUID.randomUUID(), start = 10.0, end = 14.0)))))
    val seen = ArrayList<Double?>()
    val changed = TimelineEditing.settingVideoEffects(edit.timeline.selection(emptySet()), edit, 1.0) { c, source -> seen.add(source); VideoEffectEditing.adding(c, VideoEffectID.silentTint) }
    require(changed.timeline.video.all { VideoEffectEditing.effect(it.content, VideoEffectID.silentTint) != null } && seen.filterNotNull() == listOf(1.0) && seen.size == 2, "Whole project; source time only under the playhead ($seen)")
    val strobed = TimelineEditing.addingEffect(VideoEffectID.negativeStrobe, edit.timeline.selection(emptySet()), edit)
    val strobes = strobed.timeline.video.sortedBy { it.start }.map { VideoEffectEditing.effect(it.content, VideoEffectID.negativeStrobe)!! }
    require(strobes.map { it.id }.toSet().size == 1 && strobes.map { it.phaseOffset } == listOf(0.0, 4.0) && strobes[1].start == 10.0, "One strobe across clips, phased by where each starts")
}

/// Time effects: the source clock, cadence holds, step blocks and freezes.
fun effectTimingTests() {
    val source = UUID.randomUUID()
    var crank = VideoEffectSettings(kind = VideoEffectID.undercranking, start = 0.0, end = 10.0)
    val clip = TimelineClip(sourceID = source, start = 0.0, end = 10.0, videoEffects = listOf(crank))
    require(abs(clip.duration - 8.0) < 1e-9 && abs(clip.sourceTime(4.0) - 5.0) < 1e-9 && abs(clip.timelineTime(5.0) - 4.0) < 1e-9, "Undercranking's speed-up shortens the clip (${clip.duration})")
    require(SourcePlaybackMap(clip).runs.none { it.silent }, "Sound plays unless muted")
    crank = crank.setting("mute", EffectValue.Toggle(true))
    require(SourcePlaybackMap(clip.copy(videoEffects = listOf(crank))).runs.all { it.silent }, "Mute silences the clip's own sound")
    require(TimelineClip(sourceID = source, start = 0.0, end = 10.0, videoEffects = listOf(crank.copy(enabled = false))).duration == 10.0, "A disabled effect does not retime")
    // Animated speed is subdivided where it changes; the integral matches.
    val ramp = VideoEffectSettings(kind = VideoEffectID.stepPrinting, start = 0.0, end = 10.0).let { e ->
        e.copy(parameters = e.parameters.map { if (it.id == "speed") it.settingKeyframe(0.0, EffectValue.Number(0.5)).settingKeyframe(10.0, EffectValue.Number(1.0)) else it }) }
    val ramped = TimelineClip(sourceID = source, start = 0.0, end = 10.0, videoEffects = listOf(ramp))
    require(ramped.playbackSpans.size == 1 && abs(ramped.duration - 20.0) < 1e-9, "Stepper speed holds between keys (${ramped.duration})")

    // Cadence: the default 16 fps cranked at 1.25× speed holds each source frame for 1/12.8 s.
    val held = TimelineClip(sourceID = source, start = 0.0, end = 10.0, videoEffects = listOf(VideoEffectSettings(kind = VideoEffectID.undercranking, start = 0.0, end = 10.0)))
    val clock = VideoEffectSequenceClock(held)
    val hold = clock.hold(0.5)!!
    require(hold.index == 6 && abs(hold.boundary - 6 / 12.8) < 1e-9, "Cadence hold ($hold)")
    val plan = VideoEffectSamplePlan.make(held, 0.5, 30.0, clock)!!
    require(plan.times.single() == hold.boundary && plan.firstAtOrAfter, "A cranked frame shows the frame at its boundary")
    require(VideoEffectSamplePlan.make(TimelineClip(sourceID = source, start = 0.0, end = 10.0), 0.5, 30.0, VideoEffectSequenceClock(TimelineClip(sourceID = source, start = 0.0, end = 10.0))) == null, "No time effect, no plan")

    // Step printing: blocks of whole source frames; the smear's normalised dissolves.
    val frames = VideoEffectFrameIndex(DoubleArray(300) { it / 30.0 })
    val step = VideoEffectSettings(kind = VideoEffectID.stepPrinting, start = 0.0, end = 10.0).setting("step", EffectValue.Number(3.0)).setting("smear", EffectValue.Number(0.7))
    val stepped = TimelineClip(sourceID = source, start = 0.0, end = 10.0, videoEffects = listOf(step))
    val block = VideoEffectSamplePlan.make(stepped, 0.14, 30.0, VideoEffectSequenceClock(stepped), frames)!!
    require(block.times == listOf(3 / 30.0, 4 / 30.0, 5 / 30.0) && block.smear == 0.7, "Step block (${block.times})")
    val mixes = block.mixes
    require(mixes.size == 2 && abs(mixes[0] - (0.7 / 3) / (0.3 + 0.7 / 3 * 2)) < 1e-12 && abs(mixes[1] - 0.7 / 3) < 1e-12, "Smear weights ($mixes)")
    require(frames.block(9.99, 0.0, 3, 0.0, 10.0, false).all { it <= 299 / 30.0 }, "A block never reads past the clip")

    // A freeze: one silent run holding one frame, in the project form.
    val frozen = TimelineClip(sourceID = source, start = 2.0, end = 2.0 + 1 / 30.0, frozenFrame = FrozenFrameSegment(2.0, 3.0),
        videoEffects = listOf(VideoEffectSettings(kind = VideoEffectID.freezeFrame, start = 2.0, end = 2.0 + 1 / 30.0)))
    val map = SourcePlaybackMap(frozen)
    require(frozen.duration == 3.0 && frozen.sourceTime(1.7) == 2.0 && map.runs.single().isHold && map.runs.single().silent && map.duration == EditTime.seconds(3.0), "A freeze holds its frame silently for its duration")
    require(VideoEffectSamplePlan.make(frozen, 2.0, 30.0, VideoEffectSequenceClock(frozen))!!.times == listOf(2.0), "A freeze shows its frame")
    val coded = ProjectCoding.clip(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(frozen)))))
    require(coded == frozen, "A freeze round-trips in the iPhone app's form")

    // The Freeze frame command: a hold at the playhead, later footage moved by its length, then lengthened and shortened.
    val take = TimelineClip(sourceID = source, start = 0.0, end = 6.0)
    val edit = EditState(TrackTimeline.of(listOf(take)))
    val (frozenEdit, holdID) = TimelineEditing.freezing(edit, 2.0)!!
    val holdItem = frozenEdit.timeline.video.first { it.id == holdID }
    require(holdItem.start == EditTime.seconds(2.0) && holdItem.content.frozenFrame?.sourceTime == 2.0 && holdItem.mapping.duration == EditTime.seconds(3.0), "The hold sits at the playhead")
    require(frozenEdit.timeline.duration == EditTime.seconds(9.0) && frozenEdit.timeline.video.size == 3, "The footage after it moves by three seconds (${frozenEdit.timeline.duration.seconds})")
    require(frozenEdit.timeline.audio.first { it.start == EditTime.seconds(5.0) }.mapping.source(EditTime.zero) == 2.0, "The take's sound moves with its picture")
    require(frozenEdit.timeline.video.first { it.start == EditTime.seconds(5.0) }.mapping.source(EditTime.zero) == 2.0, "Nothing is replaced: the take continues from the frozen frame")
    val longer = TimelineEditing.settingHold(frozenEdit, holdID, 5.0)
    require(longer.timeline.duration == EditTime.seconds(11.0) && longer.timeline.video.first { it.id == holdID }.content.videoEffects!!.first { it.kind == VideoEffectID.freezeFrame }.number("hold") == 5.0, "A longer hold pushes the rest")
    val shorter = TimelineEditing.settingHold(frozenEdit, holdID, 1.0)
    require(shorter.timeline.duration == EditTime.seconds(7.0) && shorter.timeline.video.none { it.start < EditTime.seconds(3.0) && it.end > EditTime.seconds(3.0) && it.id != holdID && it.start != EditTime.zero }, "A shorter hold closes up (${shorter.timeline.duration.seconds})")
    shorter.timeline.validate(listOf(SourceMedia(source, "a.mp4", 6.0)))
    require(TimelineEditing.freezing(frozenEdit, 3.0) == null, "A hold is not frozen again")
    require(frozenEdit.timeline.audio.any { it.followsVideo == holdID && it.mapping.runs.all { r -> r.silent } } && frozenEdit.timeline.links.any { holdID in it.members }, "The hold has its silent sound, linked")
    val (removed, _) = TimelineEditing.deleting(frozenEdit.timeline.selection(setOf(holdID)), frozenEdit)
    require(removed.timeline.duration == EditTime.seconds(6.0) && removed.timeline.video.none { it.id == holdID }, "Removing the hold closes up (${removed.timeline.duration.seconds})")
    // A slower clip before the hold pushes it, as it pushes any take.
    val slower = TimelineEditing.settingSpeed(0.5, frozenEdit.timeline.selection(setOf(frozenEdit.timeline.video.first { it.start == EditTime.zero }.id)), frozenEdit)
    require(slower.timeline.video.first { it.id == holdID }.start == EditTime.seconds(4.0), "The hold follows a retimed clip before it")
}

/// Follow face: the track's interpolation and gaps, the choice among faces, crash zoom's aim.
fun effectSubjectTests() {
    val a = EffectSubjectSample(1.0, 0.1, 0.1, 0.2, 0.2, 0.9, true); val b = EffectSubjectSample(1.2, 0.3, 0.1, 0.2, 0.2, 0.9, true)
    val track = EffectSubjectTrack(listOf(a, b))
    require(abs(track.sample(1.1)!!.centerX - 0.3) < 1e-12 && track.sample(0.9) == null && track.sample(1.8) == null && track.sample(1.5) == b, "Track samples interpolate, hold, and lapse after half a second")
    require(EffectSubjectTrack(listOf(a.copy(lost = true), b)).sample(1.1) == null, "A lost sample is a gap")
    val chooser = EffectSubjectChooser(0.8, 0.5)
    val left = EffectSubjectSample(0.0, 0.0, 0.4, 0.2, 0.2, 0.9, true); val right = EffectSubjectSample(0.0, 0.7, 0.4, 0.2, 0.2, 0.9, true)
    require(chooser.update(listOf(left, right), 0.0)?.x == 0.7, "The face under the seed point is chosen")
    val moved = chooser.update(listOf(left, right.copy(x = 0.72)), 1.0 / 15)!!
    require(moved.x > 0.7 && moved.x < 0.72, "The centre eases toward the new position (${moved.x})")
    require(chooser.update(listOf(left), 2.0 / 15) == null, "Another face is not jumped to while the subject is recent")
    val crash = VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.crashZoom), 10.0, 30.0, target = 0.25 to 0.75)
    val v = VideoEffectRendering.frameValues(crash)
    require(v[0] == 0.25f && v[1] == 0.75f && abs(v[2] - 2.5f) < 1e-6 && v[3] == 0f, "Crash zoom aims at the subject and holds its zoom (${v.toList()})")
    val snap = VideoEffectRendering.frameValues(VideoEffectRendering.uniforms(VideoEffectSettings(kind = VideoEffectID.crashZoom), 0.09, 30.0))
    require(snap[2] > 1f && snap[2] < 2.5f && snap[3] > 0f && snap[0] == 0.5f, "During the push the zoom grows with motion blur")
}

/// Quantization's palettes: the seeded generator, k-means on Oklab, smoothing and cuts, the shown palette.
fun effectPaletteTests() {
    val random = EffectRandom(0)
    require(listOf(random.next(), random.next(), random.next()) == listOf(0.8833108082136426, 0.43152799704850997, 0.026433771592597743) && EffectRandom(12345).next() == 0.1330796686614273, "SplitMix64 as on iPhone")
    // Two flat colours: two centres, one each, darker first; the band between them.
    val red = doubleArrayOf(0.8, 0.05, 0.05); val blue = doubleArrayOf(0.02, 0.05, 0.6)
    val samples = List(200) { if (it % 2 == 0) red else blue }
    val palette = EffectPaletteAnalysis.analyze(samples, 2, seed = 7)
    require(palette.valid && palette.colors.size == 2 && palette.thresholds.size == 1, "A valid two-colour palette")
    require(EffectColorMath.lab(EffectColorMath.linear(palette.colors[0]))[0] < EffectColorMath.lab(EffectColorMath.linear(palette.colors[1]))[0], "Colours are ordered by lightness")
    require(palette.colors[0].blue > palette.colors[0].red && palette.colors[1].red > palette.colors[1].blue, "Each centre is one of the colours (${palette.colors})")
    require(EffectPaletteAnalysis.analyze(samples, 2, seed = 7) == palette, "The same frame and seed give the same palette")
    // Smoothing toward a slightly changed frame; a scene cut jumps.
    val shifted = List(200) { if (it % 2 == 0) doubleArrayOf(0.75, 0.06, 0.05) else blue }
    val eased = EffectPaletteAnalysis.analyze(shifted, 2, previous = palette, elapsed = 0.2, seed = 8)
    val target = EffectPaletteAnalysis.analyze(shifted, 2, seed = 8)
    require(eased.colors[1].red > target.colors[1].red && eased.colors[1].red < palette.colors[1].red + 1e-9, "Small changes ease in")
    val green = List(200) { doubleArrayOf(0.05, 0.7, 0.05) }
    val cut = EffectPaletteAnalysis.analyze(green, 2, previous = palette, elapsed = 0.2, seed = 9)
    require(cut.colors.all { it.green > it.red }, "A scene cut replaces the palette")
    // What is shown: a Pop preset takes the analysed bands; monochrome keeps the analysis; a saved palette is reduced keeping hues.
    var effect = VideoEffectSettings(kind = VideoEffectID.quantization).setting("colors", EffectValue.Number(2.0))
    require(EffectPalettes.display(effect, 0.0, palette, null) == palette, "Scene palette by default")
    val pop = EffectPalettePreset.pop.first()
    effect = effect.setting("palette", EffectValue.Choice(pop.name))
    require(EffectPalettes.display(effect, 0.0, palette, null)!!.colors == pop.sampled(2), "A Pop palette recolours the bands")
    require(EffectPalettes.sample(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(0.5, 0.5, 0.5), EffectRGB(0.52, 0.5, 0.5), EffectRGB(1.0, 0.0, 0.0)), 3).contains(EffectRGB(1.0, 0.0, 0.0)), "Reducing keeps the saturated entry")
    require(EffectPalettes.locked(mapOf("colors" to listOf(mapOf("red" to 0.0, "green" to 0.0, "blue" to 0.0), mapOf("red" to 1.0, "green" to 1.0, "blue" to 1.0)), "thresholds" to listOf(0.5)))?.colors?.size == 2, "The saved locked palette is read")
}

/// Temporal mattes: window length and weights, scene cuts, and what is refused.
fun effectMatteTests() {
    require(VideoEffectMatteKind.Isolation.capacity == 8 && VideoEffectMatteKind.Key.capacity == 6, "Window lengths as on iPhone")
    val times = DoubleArray(100) { it / 30.0 }
    val window = VideoEffectTemporalWindow.make(times, 1.0, 0.0, VideoEffectMatteKind.Isolation)!!
    require(window.indices == (23..30).toList() && abs(window.weights.sum() - 1) < 1e-12 && window.tailBound > 0, "Eight frames, weights summing to one (${window.indices})")
    val early = VideoEffectTemporalWindow.make(times, 2 / 30.0 + 0.001, 1 / 30.0, VideoEffectMatteKind.Key)!!
    require(early.indices == listOf(1, 2) && early.tailBound == 0.0, "The window starts at the effect's frame (${early.indices})")
    val gap = times.copyOf().also { for (i in 50 until 100) it[i] += 1.0 }
    require(VideoEffectTemporalWindow.make(gap, gap[52], 0.0, VideoEffectMatteKind.Isolation)!!.indices.first() == 50, "A gap in the frames stops the history")
    val flat = FloatArray(576) { 0.4f }; val other = FloatArray(576) { 0.9f }; val noisy = FloatArray(576) { if (it % 2 == 0) 0.42f else 0.38f }
    require(VideoEffectTemporalWindow.priors(listOf(flat, noisy, other), VideoEffectMatteKind.Isolation) == listOf(1.0 / 3, 0.0), "A small change eases, a scene cut does not")
    val subject = VideoEffectSettings(kind = VideoEffectID.colorIsolation).setting("mode", EffectValue.Choice("The subject"))
    require(VideoEffectRendering.refusal(subject)!!.contains("The subject") && VideoEffectRendering.refusal(VideoEffectSettings(kind = VideoEffectID.colorIsolation)) == null, "Colour isolation by colour is drawn, by subject refused by name")
}
