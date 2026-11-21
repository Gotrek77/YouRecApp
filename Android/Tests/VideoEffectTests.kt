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
    requireFailure<ProjectError.UnsupportedTools>("An effect this build cannot draw is refused by name") { ProjectCoding.videoEffects(listOf(JSON.parse(encoded.toByteArray()), unknown)) }
    try { ProjectCoding.videoEffects(listOf(JSON.parse(encoded.toByteArray()))) } catch (e: ProjectError.UnsupportedTools) { require(e.message!!.contains("Silent Tint"), "The refusal names the effect") }
    require(ProjectCoding.videoEffects(listOf(unknown)).isEmpty(), "An unknown effect kind is skipped, as on iPhone")
    println("Video effects: ok")
}
