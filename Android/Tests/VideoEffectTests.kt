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
}
