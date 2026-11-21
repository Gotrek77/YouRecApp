package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max

/// The iPhone app's video effects (YouRec/VideoEffects/VideoEffectModel.swift
/// and VideoEffectCatalog.swift): effect kinds, their parameters with units and
/// bounds, keyframes in source time, and the order effects apply in. Parameter
/// IDs, values and units are part of the project format.
enum class EffectFamily(val title: String) {
    Key("Background"), Camera("Camera"), Color("Color & tone"), Quantization("Quantization"), Texture("Film texture"), Frame("Frame"), Time("Time");
    val raw: String get() = name.replaceFirstChar { it.lowercase() }
}
enum class EffectMode { Live, Edit, Both }
enum class RGBChannel(val title: String) {
    Red("Red"), Green("Green"), Blue("Blue");
    val x: String get() = name.lowercase() + "X"
    val y: String get() = name.lowercase() + "Y"
}

/// Effect kinds by the iPhone app's names; `shaderIndex` is its stable shader ABI.
enum class VideoEffectID(val shaderIndex: Int) {
    rgbOffset(23), negativeStrobe(24), nightFix(21), colorCorrection(20), silentTint(0), threeStrip(1), bleachBypass(2), dayForNight(3),
    colorIsolation(4), noir(5), expressionism(6), quantization(7), dollyZoom(8), crashZoom(9), kaleidoscope(22), widescreen(11),
    freezeFrame(12), stepPrinting(13), undercranking(14), halation(15), sixteenMM(16), superEight(17), chromaKey(18), autoBackground(19);
    val descriptor: VideoEffectDescriptor get() = VideoEffectCatalog.all.first { it.id == this }
    companion object { fun of(raw: String) = values().firstOrNull { it.name == raw } }
}

data class EffectRGB(val red: Double, val green: Double, val blue: Double) {
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }
    companion object {
        fun hex(text: String): EffectRGB {
            val value = text.removePrefix("#").toLong(16)
            return EffectRGB(((value shr 16) and 255) / 255.0, ((value shr 8) and 255) / 255.0, (value and 255) / 255.0)
        }
    }
}

sealed class EffectValue {
    data class Number(val value: Double) : EffectValue()
    data class Toggle(val value: Boolean) : EffectValue()
    data class Choice(val value: String) : EffectValue()
    data class Color(val value: EffectRGB) : EffectValue()
    data class Media(val file: String?) : EffectValue()
    fun interpolated(other: EffectValue, fraction: Double): EffectValue {
        val t = fraction.coerceIn(0.0, 1.0)
        return when {
            this is Number && other is Number -> Number(value + (other.value - value) * t)
            this is Color && other is Color -> Color(EffectRGB(value.red + (other.value.red - value.red) * t, value.green + (other.value.green - value.green) * t, value.blue + (other.value.blue - value.blue) * t))
            else -> if (t < 1) this else other
        }
    }
}

sealed class EffectParameterKind {
    data class Slider(val min: Double, val max: Double, val defaultValue: Double, val step: Double) : EffectParameterKind()
    data class Toggle(val defaultValue: Boolean) : EffectParameterKind()
    data class Stepper(val values: List<Double>, val defaultValue: Double) : EffectParameterKind()
    data class Color(val defaultValue: EffectRGB) : EffectParameterKind()
    data class Picker(val options: List<String>, val defaultValue: String) : EffectParameterKind()
    data class Media(val image: Boolean, val video: Boolean) : EffectParameterKind()
    val initial: EffectValue get() = when (this) {
        is Slider -> EffectValue.Number(defaultValue); is Stepper -> EffectValue.Number(defaultValue)
        is Toggle -> EffectValue.Toggle(defaultValue); is Color -> EffectValue.Color(defaultValue)
        is Picker -> EffectValue.Choice(defaultValue); is Media -> EffectValue.Media(null)
    }
    val continuous: Boolean get() = this is Slider || this is Color
    fun accepts(value: EffectValue): Boolean = when {
        this is Slider && value is EffectValue.Number -> value.value.isFinite() && value.value in min..max
        this is Stepper && value is EffectValue.Number -> value.value in values
        this is Toggle && value is EffectValue.Toggle -> true
        this is Color && value is EffectValue.Color -> value.value.valid
        this is Picker && value is EffectValue.Choice -> value.value in options
        this is Media && value is EffectValue.Media -> value.file?.let { file ->
            val ext = file.substringAfterLast('.', "").lowercase()
            file.isNotEmpty() && file == java.io.File(file).name && !file.startsWith(".") && !file.contains("\\") &&
                (image && ext in setOf("png", "jpg", "jpeg", "heic", "heif", "tif", "tiff", "webp") || video && ext in setOf("mov", "mp4", "m4v"))
        } ?: true
        else -> false
    }
}

data class EffectKeyframe(val id: UUID = UUID.randomUUID(), val time: Double, val value: EffectValue)

data class EffectParameter(val id: String, val title: String, val kind: EffectParameterKind, val value: EffectValue = kind.initial,
                           val keyframes: List<EffectKeyframe> = emptyList(), val animatable: Boolean = true) {
    companion object { fun wrappedDegrees(value: Double) = value - 360 * floor((value + 180) / 360) }
    fun value(at: Double): EffectValue {
        val first = keyframes.firstOrNull() ?: return value
        if (at <= first.time) return first.value
        for ((a, b) in keyframes.zipWithNext()) {
            if (at >= b.time) continue
            val fraction = (at - a.time) / (b.time - a.time)
            if ((id == "splitAngle" || id == "sourceAngle") && a.value is EffectValue.Number && b.value is EffectValue.Number) {
                val difference = b.value.value - a.value.value
                val travel = if (abs(difference) > 180) difference - Math.copySign(360.0, difference) else difference
                return EffectValue.Number(wrappedDegrees(a.value.value + travel * fraction))
            }
            return if (kind.continuous) a.value.interpolated(b.value, fraction) else a.value
        }
        return keyframes.last().value
    }
    fun settingKeyframe(at: Double, newValue: EffectValue): EffectParameter {
        if (!animatable || !at.isFinite() || at < 0 || !kind.accepts(newValue)) return this
        return copy(keyframes = (keyframes.filter { abs(it.time - at) >= 1.0 / 60000 } + EffectKeyframe(time = at, value = newValue)).sortedBy { it.time })
    }
    val valid: Boolean get() = kind.accepts(value) && (animatable || keyframes.isEmpty()) && keyframes.size <= 100_000
        && keyframes.map { it.id }.toSet().size == keyframes.size
        && keyframes.all { it.time.isFinite() && it.time in 0.0..604800.0 && kind.accepts(it.value) }
        && keyframes.zipWithNext().all { (a, b) -> a.time < b.time }
}

data class VideoEffectDescriptor(val id: VideoEffectID, val displayName: String, val historyNote: String, val family: EffectFamily, val mode: EffectMode,
                                 val parameters: List<EffectParameter>) {
    val supportsIntensity: Boolean get() = family != EffectFamily.Time && family != EffectFamily.Camera
}

data class StrobeTimePoint(val source: Double, val elapsed: Double)

/// One effect on a clip, in source seconds. `lockedPalette` is the iPhone
/// app's saved quantization palette, kept as written.
data class VideoEffectSettings(val id: UUID = UUID.randomUUID(), val kind: VideoEffectID, val enabled: Boolean = true, val version: Int = 1,
                               val start: Double = 0.0, val end: Double = 604800.0, val parameters: List<EffectParameter> = defaults(kind),
                               val analysisFile: String? = null, val analysisDataFile: String? = null, val capturedEffectID: UUID? = null,
                               val palette: List<EffectRGB>? = null, val lockedPalette: Any? = null, val algorithm: Int? = algorithmFor(kind),
                               val strobeTimeMap: List<StrobeTimePoint>? = null, val phaseOffset: Double? = null, val releaseTime: Double? = null) {
    companion object {
        fun algorithmFor(kind: VideoEffectID) = if (kind == VideoEffectID.colorIsolation || kind == VideoEffectID.chromaKey) 2 else 1
        fun defaults(kind: VideoEffectID): List<EffectParameter> {
            val catalogue = kind.descriptor.parameters
            return if (kind.descriptor.supportsIntensity) listOf(EffectParameter("intensity", "Intensity", EffectParameterKind.Slider(0.0, 1.0, 1.0, 0.01))) + catalogue else catalogue
        }
        /// Night fix first; then by family, Kaleidoscope last in its family, Color Correction first in its own.
        fun ordered(effects: List<VideoEffectSettings>): List<VideoEffectSettings> = effects.withIndex().sortedWith { a, b ->
            val x = a.value; val y = b.value
            when {
                (x.kind == VideoEffectID.nightFix) != (y.kind == VideoEffectID.nightFix) -> if (x.kind == VideoEffectID.nightFix) -1 else 1
                x.kind.descriptor.family.ordinal != y.kind.descriptor.family.ordinal -> x.kind.descriptor.family.ordinal.compareTo(y.kind.descriptor.family.ordinal)
                (x.kind == VideoEffectID.kaleidoscope) != (y.kind == VideoEffectID.kaleidoscope) -> if (y.kind == VideoEffectID.kaleidoscope) -1 else 1
                (x.kind == VideoEffectID.colorCorrection) != (y.kind == VideoEffectID.colorCorrection) -> if (x.kind == VideoEffectID.colorCorrection) -1 else 1
                else -> a.index.compareTo(b.index)
            }
        }.map { it.value }
    }
    val analysisID: UUID get() = capturedEffectID ?: id
    fun value(id: String, at: Double = 0.0): EffectValue? = parameters.firstOrNull { it.id == id }?.value(at)
    fun number(id: String, at: Double = 0.0) = (value(id, at) as? EffectValue.Number)?.value ?: 0.0
    fun toggle(id: String, at: Double = 0.0) = (value(id, at) as? EffectValue.Toggle)?.value ?: false
    fun choice(id: String, at: Double = 0.0) = (value(id, at) as? EffectValue.Choice)?.value ?: ""
    fun color(id: String, at: Double = 0.0) = (value(id, at) as? EffectValue.Color)?.value ?: EffectRGB(0.0, 0.0, 0.0)
    fun setting(id: String, newValue: EffectValue): VideoEffectSettings =
        copy(parameters = parameters.map { if (it.id == id && it.kind.accepts(newValue)) it.copy(value = newValue) else it })
    val usesTemporalMatte: Boolean get() = algorithm == 2 && (kind == VideoEffectID.colorIsolation || kind == VideoEffectID.chromaKey)
    val usesModernPersonMask: Boolean get() = algorithm == 1 || usesTemporalMatte
    fun isActive(at: Double) = enabled && at >= start && at < end && (!kind.descriptor.supportsIntensity || number("intensity", at) > 0)
    /// Effects that need analysis of the recording or a subject mask before they can be drawn.
    val requiresOfflineAnalysis: Boolean get() {
        if (!enabled) return false
        return when (kind) {
            VideoEffectID.nightFix -> choice("mode") != "Fast"
            VideoEffectID.quantization, VideoEffectID.autoBackground, VideoEffectID.dollyZoom -> true
            VideoEffectID.crashZoom -> toggle("trackFace", start) || parameters.any { it.id == "trackFace" && it.keyframes.isNotEmpty() }
            VideoEffectID.colorIsolation, VideoEffectID.chromaKey -> usesModernPersonMask
            else -> false
        }
    }
    val mediaFiles: List<String> get() {
        val files = listOfNotNull(analysisFile, analysisDataFile).toMutableList()
        for (p in parameters) for (v in listOf(p.value) + p.keyframes.map { it.value }) if (v is EffectValue.Media && v.file != null) files.add(v.file)
        return files.distinct().sorted()
    }
    val valid: Boolean get() {
        val reference = defaults(kind)
        return (algorithm == null || algorithm == 1 || usesTemporalMatte) && version == 1 && start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 604800
            && parameters.size == reference.size && parameters.map { it.id }.toSet().size == parameters.size
            && parameters.all { p -> p.valid && reference.any { it.id == p.id && it.kind == p.kind && it.animatable == p.animatable } }
            && (strobeTimeMap?.let { points -> kind == VideoEffectID.negativeStrobe && points.size in 2..100_000
                && points.all { it.source.isFinite() && it.elapsed.isFinite() && it.elapsed in 0.0..604800.0 }
                && points.zipWithNext().all { (a, b) -> a.source < b.source && a.elapsed <= b.elapsed } } ?: true)
            && (releaseTime?.let { kind == VideoEffectID.crashZoom && it.isFinite() && it >= start - (phaseOffset ?: 0.0) && it < end } ?: true)
            && (phaseOffset?.let { (kind == VideoEffectID.crashZoom || kind == VideoEffectID.negativeStrobe) && it.isFinite() && it in 0.0..604800.0 } ?: true)
            && (palette?.let { it.size in 2..16 && it.all { c -> c.valid } } ?: true)
            && mediaFiles.all { !it.startsWith(".") && !it.contains("\\") && it == java.io.File(it).name }
    }
    /// Whether Negative Strobe shows the negative at `time`, integrating held rate keys.
    fun strobeShowsNegative(time: Double, fps: Double, holdTime: Double? = null): Boolean {
        fun elapsed(t: Double): Double {
            val points = strobeTimeMap
            if (points == null || points.isEmpty()) return max(0.0, t - start)
            if (t <= points.first().source) return points.first().elapsed
            if (t >= points.last().source) return points.last().elapsed
            var lo = 0; var hi = points.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (points[mid].source <= t) lo = mid else hi = mid }
            val a = points[lo]; val b = points[hi]
            return a.elapsed + (b.elapsed - a.elapsed) * (t - a.source) / (b.source - a.source)
        }
        val offset = phaseOffset ?: 0.0; val clock = offset + (holdTime ?: elapsed(time))
        if (toggle("timedBurst") && clock >= number("burstLength") - 1e-8) return false
        val frequency = parameters.firstOrNull { it.id == "frequency" } ?: return false
        val ceiling = max(0.5, fps * 0.5)
        fun rate(value: EffectValue) = if (value is EffectValue.Number) min(ceiling, value.value) else 8.0
        var hz = rate(frequency.value(start)); var last = 0.0; var cycles = 0.0
        for (key in frequency.keyframes) {
            if (key.time <= start) continue
            if (key.time > time) break
            val next = offset + elapsed(key.time)
            cycles += (next - last) * hz; last = next; hz = rate(key.value)
        }
        cycles += (clock - last) * hz
        return floor(cycles * 2 + 1e-7) % 2 == 1.0
    }
}

/// Original effect definitions, generated from the iPhone app's VideoEffectCatalog.swift.
object VideoEffectCatalog {
    val all: List<VideoEffectDescriptor> = listOf(
        VideoEffectDescriptor(VideoEffectID.rgbOffset, "RGB Offset", "Color dye-transfer printing aligned three separate matrices to register the color image.", EffectFamily.Color, EffectMode.Both, listOf(
            *RGBChannel.values().flatMap { channel -> listOf(EffectParameter(channel.x, channel.title + " X", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.001)), EffectParameter(channel.y, channel.title + " Y", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.001))) }.toTypedArray())),
        VideoEffectDescriptor(VideoEffectID.negativeStrobe, "Negative Strobe", "Experimental flicker films of the 1960s intercut positive and negative images.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("frequency", "Cycles per second", EffectParameterKind.Stepper(listOf(1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0), 8.0)),
            EffectParameter("timedBurst", "Limit duration", EffectParameterKind.Toggle(false), animatable = false),
            EffectParameter("burstLength", "Burst length (s)", EffectParameterKind.Slider(0.1, 10.0, 1.0, 0.05), animatable = false))),
        VideoEffectDescriptor(VideoEffectID.nightFix, "Night fix", "Digital film restoration combines motion-aligned frames to separate image detail from noise before correcting exposure.", EffectFamily.Color, EffectMode.Edit, listOf(
            EffectParameter("mode", "Mode", EffectParameterKind.Picker(listOf("Fast", "Medium", "Precise"), "Fast"), animatable = false),
            EffectParameter("brightness", "Shadow brightness", EffectParameterKind.Slider(0.0, 8.0, 2.5, 0.1)),
            EffectParameter("reduction", "Noise reduction", EffectParameterKind.Slider(0.0, 3.0, 1.0, 0.05)),
            EffectParameter("detail", "Preserve detail", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.05)))),
        VideoEffectDescriptor(VideoEffectID.colorCorrection, "Color Correction", "Film laboratories balanced each scene with color timing; digital grading added precise control of contrast and individual tones.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("exposure", "Exposure", EffectParameterKind.Slider(-4.0, 4.0, 0.0, 0.05)),
            EffectParameter("contrast", "Contrast", EffectParameterKind.Slider(0.25, 2.0, 1.0, 0.01)),
            EffectParameter("shadows", "Shadows", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("highlights", "Highlights", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("blacks", "Blacks", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("whites", "Whites", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("pivot", "Contrast pivot", EffectParameterKind.Slider(0.1, 0.9, 0.5, 0.01)),
            EffectParameter("temperature", "Temperature", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("tint", "Tint", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("saturation", "Saturation", EffectParameterKind.Slider(0.0, 2.0, 1.0, 0.01)),
            EffectParameter("vibrance", "Vibrance", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("hue", "Hue", EffectParameterKind.Slider(-180.0, 180.0, 0.0, 1.0)))),
        VideoEffectDescriptor(VideoEffectID.silentTint, "Silent Tint", "Silent cinema used tinting and toning to color whole shots and suggest mood or time of day.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("tint", "Tint", EffectParameterKind.Picker(listOf("Night Blue", "Amber", "Fire Red", "Nature Green", "Sepia"), "Amber")),
            EffectParameter("mode", "Process", EffectParameterKind.Picker(listOf("Tint", "Tone"), "Tint")),
            EffectParameter("contrast", "Contrast", EffectParameterKind.Slider(0.8, 1.4, 1.15, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.threeStrip, "Three-Strip", "Three-strip dye-transfer printing layered cyan, yellow and magenta dyes to create rich cinema color.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("saturation", "Saturation", EffectParameterKind.Slider(1.0, 1.8, 1.4, 0.01)),
            EffectParameter("redDepth", "Red depth", EffectParameterKind.Slider(0.0, 1.0, 0.6, 0.01)),
            EffectParameter("warmth", "Warmth", EffectParameterKind.Slider(-1.0, 1.0, 0.2, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.bleachBypass, "Bleach Bypass", "Retaining silver during color-film processing produces desaturated color and dense contrast.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("strength", "Strength", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.dayForNight, "Day for Night", "Day-for-night photography uses exposure and color filtration to make daylight scenes read as night.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("darkness", "Darkness", EffectParameterKind.Slider(0.0, 1.0, 0.6, 0.01)),
            EffectParameter("blue", "Blue", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01)),
            EffectParameter("moonlight", "Moonlight highlights", EffectParameterKind.Toggle(true)))),
        VideoEffectDescriptor(VideoEffectID.colorIsolation, "Color Isolation", "Selective color contrasts a colored detail with monochrome surroundings, as in Rumble Fish (1983).", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("mode", "Keep", EffectParameterKind.Picker(listOf("A color", "The subject"), "A color")),
            EffectParameter("color", "Hue", EffectParameterKind.Color(EffectRGB.hex("#C0392B"))),
            EffectParameter("range", "Hue range", EffectParameterKind.Slider(5.0, 60.0, 22.0, 1.0)),
            EffectParameter("softness", "Softness", EffectParameterKind.Slider(0.0, 1.0, 0.4, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.noir, "Noir", "Film noir uses low-key lighting and strong shadows; colored lens filters also shaped black-and-white tones.", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("contrast", "Contrast", EffectParameterKind.Slider(1.0, 2.0, 1.5, 0.01)),
            EffectParameter("crush", "Black crush", EffectParameterKind.Slider(0.0, 0.3, 0.12, 0.01)),
            EffectParameter("grain", "Grain", EffectParameterKind.Toggle(true)),
            EffectParameter("shadowColor", "Blue shadows", EffectParameterKind.Toggle(false)))),
        VideoEffectDescriptor(VideoEffectID.expressionism, "Expressionism", "German Expressionist cinema used stylized shadows and distorted sets, notably in The Cabinet of Dr. Caligari (1920).", EffectFamily.Color, EffectMode.Both, listOf(
            EffectParameter("contrast", "Contrast", EffectParameterKind.Slider(1.5, 3.0, 2.2, 0.01)),
            EffectParameter("vignette", "Vignette", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01)),
            EffectParameter("iris", "Iris", EffectParameterKind.Picker(listOf("Oval", "Circle"), "Oval")),
            EffectParameter("tint", "Tint", EffectParameterKind.Toggle(false)))),
        VideoEffectDescriptor(VideoEffectID.quantization, "Quantization", "Posterization reduces continuous tones to flat bands, echoing photographic silkscreen prints of the 1960s.", EffectFamily.Quantization, EffectMode.Both, listOf(
            EffectParameter("colors", "Colors", EffectParameterKind.Stepper(listOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 16.0), 4.0)),
            EffectParameter("monochrome", "B&W", EffectParameterKind.Toggle(false)),
            EffectParameter("palette", "Palette", EffectParameterKind.Picker(listOf("From scene", "Pop 1", "Pop 2", "Pop 3", "Saved"), "From scene")),
            EffectParameter("smoothing", "Smoothing", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("dithering", "Dithering", EffectParameterKind.Picker(listOf("Off", "Ordered 4x4", "Ordered 8x8"), "Off")),
            EffectParameter("faceDetail", "Face detail", EffectParameterKind.Toggle(true)),
            EffectParameter("lockPalette", "Lock palette", EffectParameterKind.Toggle(false)))),
        VideoEffectDescriptor(VideoEffectID.dollyZoom, "Dolly Zoom", "Opposing a camera move with zoom changes perspective while keeping the subject the same size, as in Vertigo (1958).", EffectFamily.Camera, EffectMode.Both, listOf(
            EffectParameter("direction", "Direction", EffectParameterKind.Picker(listOf("Stretch", "Compress"), "Stretch")),
            EffectParameter("strength", "Strength", EffectParameterKind.Slider(0.1, 0.5, 0.3, 0.01)),
            EffectParameter("duration", "Duration", EffectParameterKind.Slider(1.0, 5.0, 2.5, 0.1), animatable = false),
            EffectParameter("easing", "Easing", EffectParameterKind.Picker(listOf("In-out cubic", "Linear"), "In-out cubic")),
            EffectParameter("targetX", "Subject X", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("targetY", "Subject Y", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.crashZoom, "Crash Zoom", "Fast lens zooms became an expressive device in 1960s and 1970s westerns and martial-arts cinema.", EffectFamily.Camera, EffectMode.Both, listOf(
            EffectParameter("zoom", "Zoom amount", EffectParameterKind.Slider(1.5, 4.0, 2.5, 0.1)),
            EffectParameter("speed", "Push duration", EffectParameterKind.Slider(0.1, 0.5, 0.18, 0.01), animatable = false),
            EffectParameter("hold", "Hold", EffectParameterKind.Slider(0.0, 3.0, 1.0, 0.1), animatable = false),
            EffectParameter("return", "Return", EffectParameterKind.Toggle(false)),
            EffectParameter("motionBlur", "Motion blur", EffectParameterKind.Slider(0.0, 1.0, 0.6, 0.01)),
            EffectParameter("targetX", "Target X", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("targetY", "Target Y", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("trackFace", "Follow face", EffectParameterKind.Toggle(true)))),
        VideoEffectDescriptor(VideoEffectID.kaleidoscope, "Kaleidoscope", "A camera fitted with mirrors turned ordinary workshop scenes into moving patterns in Kaleidoscope Shop (1959).", EffectFamily.Camera, EffectMode.Both, listOf(
            EffectParameter("divisions", "Divisions", EffectParameterKind.Stepper(listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), 2.0)),
            EffectParameter("centerX", "Split center X", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.001)),
            EffectParameter("centerY", "Split center Y", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.001)),
            EffectParameter("splitAngle", "Split rotation", EffectParameterKind.Slider(-180.0, 180.0, 0.0, 1.0)),
            EffectParameter("sourcePanX", "Source pan X", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.001)),
            EffectParameter("sourcePanY", "Source pan Y", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.001)),
            EffectParameter("sourceAngle", "Source rotation", EffectParameterKind.Slider(-180.0, 180.0, 0.0, 1.0)))),
        VideoEffectDescriptor(VideoEffectID.widescreen, "Widescreen 1953", "The widescreen expansion of the 1950s brought broad compositions, anamorphic flares and distinctive lens rendering.", EffectFamily.Frame, EffectMode.Both, listOf(
            EffectParameter("ratio", "Ratio", EffectParameterKind.Picker(listOf("2.39:1", "2.20:1", "1.85:1", "1.33:1"), "2.39:1")),
            EffectParameter("bars", "Bars", EffectParameterKind.Picker(listOf("Black", "Blur fill"), "Black")),
            EffectParameter("flares", "Flares", EffectParameterKind.Slider(0.0, 1.0, 0.4, 0.01)),
            EffectParameter("flareColor", "Flare color", EffectParameterKind.Picker(listOf("Blue", "Amber", "Neutral"), "Blue")),
            EffectParameter("ovalBokeh", "Oval bokeh", EffectParameterKind.Toggle(false)))),
        VideoEffectDescriptor(VideoEffectID.freezeFrame, "Freeze Frame", "A held frame can suspend a film at a decisive moment, as at the end of The 400 Blows (1959).", EffectFamily.Time, EffectMode.Edit, listOf(
            EffectParameter("hold", "Hold duration", EffectParameterKind.Slider(0.5, 10.0, 3.0, 0.1), animatable = false),
            EffectParameter("push", "Push-in", EffectParameterKind.Slider(0.0, 0.15, 0.05, 0.01)),
            EffectParameter("style", "Style", EffectParameterKind.Picker(listOf("Clean", "Print"), "Clean")),
            EffectParameter("audio", "Audio", EffectParameterKind.Picker(listOf("Cut", "Continue", "Fade out over hold"), "Cut")))),
        VideoEffectDescriptor(VideoEffectID.stepPrinting, "Step Printing", "Repeating printed frames produces stuttering motion, combined with blur in Chungking Express (1994).", EffectFamily.Time, EffectMode.Edit, listOf(
            EffectParameter("step", "Step frames", EffectParameterKind.Stepper(listOf(2.0, 3.0, 4.0, 5.0, 6.0), 3.0)),
            EffectParameter("smear", "Smear", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01)),
            EffectParameter("speed", "Speed", EffectParameterKind.Stepper(listOf(0.5, 1.0), 1.0)))),
        VideoEffectDescriptor(VideoEffectID.undercranking, "Undercranking", "Shooting fewer frames than the projection rate gives the quick, uneven movement associated with silent comedy.", EffectFamily.Time, EffectMode.Both, listOf(
            EffectParameter("fps", "Frame rate", EffectParameterKind.Stepper(listOf(12.0, 16.0, 18.0), 16.0)),
            EffectParameter("speed", "Speed-up", EffectParameterKind.Slider(1.0, 1.5, 1.25, 0.05)),
            EffectParameter("flicker", "Flicker", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("weave", "Gate weave", EffectParameterKind.Slider(0.0, 1.0, 0.4, 0.01)),
            EffectParameter("mute", "Mute audio", EffectParameterKind.Toggle(false)))),
        VideoEffectDescriptor(VideoEffectID.halation, "Halation", "Film halation spreads warm light around bright highlights; diffusion and pre-exposure can soften contrast.", EffectFamily.Texture, EffectMode.Both, listOf(
            EffectParameter("glow", "Glow", EffectParameterKind.Slider(0.0, 1.0, 0.4, 0.01)),
            EffectParameter("red", "Halation red", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("flash", "Shadow lift", EffectParameterKind.Slider(0.0, 1.0, 0.2, 0.01)),
            EffectParameter("flashColor", "Flash color", EffectParameterKind.Picker(listOf("Neutral", "Warm", "Cool"), "Warm")))),
        VideoEffectDescriptor(VideoEffectID.sixteenMM, "16mm", "Lightweight 16mm cameras helped filmmakers work outside studios and became central to documentary production.", EffectFamily.Texture, EffectMode.Both, listOf(
            EffectParameter("grain", "Grain", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("grainSize", "Grain size", EffectParameterKind.Picker(listOf("Fine", "Medium", "Coarse"), "Medium")),
            EffectParameter("softness", "Softness", EffectParameterKind.Slider(0.0, 1.0, 0.3, 0.01)),
            EffectParameter("weave", "Gate weave", EffectParameterKind.Slider(0.0, 1.0, 0.2, 0.01)),
            EffectParameter("frameEdge", "Frame edge", EffectParameterKind.Toggle(false)),
            EffectParameter("color", "Color", EffectParameterKind.Picker(listOf("Color", "B&W"), "Color")))),
        VideoEffectDescriptor(VideoEffectID.superEight, "Super 8", "Introduced in 1965, the small-gauge cartridge format made home-movie filmmaking more accessible.", EffectFamily.Texture, EffectMode.Both, listOf(
            EffectParameter("fade", "Fade", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("grain", "Grain", EffectParameterKind.Slider(0.0, 1.0, 0.7, 0.01)),
            EffectParameter("weave", "Weave", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("fps", "Frame rate", EffectParameterKind.Stepper(listOf(18.0, 24.0), 18.0)),
            EffectParameter("leaks", "Light leaks", EffectParameterKind.Slider(0.0, 1.0, 0.2, 0.01)),
            EffectParameter("border", "Frame border", EffectParameterKind.Toggle(true)))),
        VideoEffectDescriptor(VideoEffectID.chromaKey, "Chroma Key", "Color-difference travelling mattes separate a subject photographed against a blue or green backing.", EffectFamily.Key, EffectMode.Both, listOf(
            EffectParameter("screenColor", "Manual screen color", EffectParameterKind.Color(EffectRGB.hex("#00FF00"))),
            EffectParameter("screenType", "Screen type", EffectParameterKind.Picker(listOf("Auto", "Green", "Blue"), "Auto")),
            EffectParameter("autoScreen", "Detect green / blue screen", EffectParameterKind.Toggle(true)),
            EffectParameter("strength", "Strength", EffectParameterKind.Slider(0.0, 2.0, 1.0, 0.01)),
            EffectParameter("balance", "Balance", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("blackClip", "Black clip", EffectParameterKind.Slider(0.0, 0.5, 0.05, 0.01)),
            EffectParameter("whiteClip", "White clip", EffectParameterKind.Slider(0.5, 1.0, 0.9, 0.01)),
            EffectParameter("softness", "Edge softness", EffectParameterKind.Slider(0.0, 1.0, 0.3, 0.01)),
            EffectParameter("grow", "Shrink / grow", EffectParameterKind.Slider(-5.0, 5.0, 0.0, 0.1)),
            EffectParameter("despill", "Despill", EffectParameterKind.Slider(0.0, 1.0, 0.8, 0.01)),
            EffectParameter("wrap", "Light wrap", EffectParameterKind.Slider(0.0, 1.0, 0.25, 0.01)),
            EffectParameter("personHint", "Use person hint", EffectParameterKind.Toggle(false)),
            EffectParameter("cleanPlate", "Clean plate", EffectParameterKind.Media(true, false)),
            EffectParameter("view", "View", EffectParameterKind.Picker(listOf("Result", "Matte", "Original"), "Result")),
            EffectParameter("background", "Background", EffectParameterKind.Picker(listOf("Solid", "Image", "Video", "Blur original", "Studio", "Window"), "Solid")),
            EffectParameter("backgroundColor", "Background color", EffectParameterKind.Color(EffectRGB.hex("#20252D"))),
            EffectParameter("media", "Background media", EffectParameterKind.Media(true, true)),
            EffectParameter("fit", "Framing", EffectParameterKind.Picker(listOf("Fill", "Fit"), "Fill")),
            EffectParameter("backgroundX", "Background X", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("backgroundY", "Background Y", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("backgroundZoom", "Background zoom", EffectParameterKind.Slider(1.0, 4.0, 1.0, 0.05)),
            EffectParameter("backgroundBlur", "Background blur", EffectParameterKind.Slider(0.0, 20.0, 0.0, 1.0)),
            EffectParameter("backgroundBrightness", "Background brightness", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("backgroundAudio", "Background sound", EffectParameterKind.Toggle(false)),
            EffectParameter("matchColor", "Match color", EffectParameterKind.Toggle(false)),
            EffectParameter("matchStrength", "Match strength", EffectParameterKind.Slider(0.0, 1.0, 0.3, 0.01)))),
        VideoEffectDescriptor(VideoEffectID.autoBackground, "Auto Background", "The travelling-matte tradition continues with a subject mask made by the camera instead of a colored screen.", EffectFamily.Key, EffectMode.Both, listOf(
            EffectParameter("softness", "Edge softness", EffectParameterKind.Slider(0.0, 1.0, 0.3, 0.01)),
            EffectParameter("grow", "Shrink / grow", EffectParameterKind.Slider(-5.0, 5.0, 0.0, 0.1)),
            EffectParameter("wrap", "Light wrap", EffectParameterKind.Slider(0.0, 1.0, 0.25, 0.01)),
            EffectParameter("portraitBlur", "Portrait blur", EffectParameterKind.Slider(0.0, 40.0, 12.0, 1.0)),
            EffectParameter("view", "View", EffectParameterKind.Picker(listOf("Result", "Matte", "Original"), "Result")),
            EffectParameter("background", "Background", EffectParameterKind.Picker(listOf("Solid", "Image", "Video", "Blur original", "Studio", "Window"), "Blur original")),
            EffectParameter("backgroundColor", "Background color", EffectParameterKind.Color(EffectRGB.hex("#20252D"))),
            EffectParameter("media", "Background media", EffectParameterKind.Media(true, true)),
            EffectParameter("fit", "Framing", EffectParameterKind.Picker(listOf("Fill", "Fit"), "Fill")),
            EffectParameter("backgroundX", "Background X", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("backgroundY", "Background Y", EffectParameterKind.Slider(0.0, 1.0, 0.5, 0.01)),
            EffectParameter("backgroundZoom", "Background zoom", EffectParameterKind.Slider(1.0, 4.0, 1.0, 0.05)),
            EffectParameter("backgroundBlur", "Background blur", EffectParameterKind.Slider(0.0, 20.0, 0.0, 1.0)),
            EffectParameter("backgroundBrightness", "Background brightness", EffectParameterKind.Slider(-1.0, 1.0, 0.0, 0.01)),
            EffectParameter("backgroundAudio", "Background sound", EffectParameterKind.Toggle(false)),
            EffectParameter("matchColor", "Match color", EffectParameterKind.Toggle(false)),
            EffectParameter("matchStrength", "Match strength", EffectParameterKind.Slider(0.0, 1.0, 0.3, 0.01))))
)
}
