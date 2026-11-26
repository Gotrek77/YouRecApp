package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/// A 2D affine map, as CGAffineTransform: x' = a·x + c·y + tx, y' = b·x + d·y + ty.
/// Picture geometry is computed with it once, in canvas pixels with y down, and
/// both the preview and the export draw from the same numbers.
data class Affine(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    fun x(px: Double, py: Double) = a * px + c * py + tx
    fun y(px: Double, py: Double) = b * px + d * py + ty
    /// This map first, then `next`.
    fun then(next: Affine) = Affine(a * next.a + b * next.c, a * next.b + b * next.d, c * next.a + d * next.c, c * next.b + d * next.d,
                                    tx * next.a + ty * next.c + next.tx, tx * next.b + ty * next.d + next.ty)
    companion object {
        fun translation(x: Double, y: Double) = Affine(tx = x, ty = y)
        fun scale(x: Double, y: Double) = Affine(a = x, d = y)
        fun rotation(radians: Double) = Affine(cos(radians), sin(radians), -sin(radians), cos(radians))
        /// The whole source inside the canvas, centred: bars rather than a crop.
        fun fit(source: CanvasSize, canvas: CanvasSize): Affine {
            if (source.width <= 0 || source.height <= 0) return Affine()
            var scale = minOf(canvas.width / source.width, canvas.height / source.height)
            // Sources a pixel or two away from the canvas shape fill it, without hairline bars.
            if (abs(source.width / source.height - canvas.width / canvas.height) < 0.01) scale = maxOf(canvas.width / source.width, canvas.height / source.height)
            return translation(-source.width / 2, -source.height / 2).then(scale(scale, scale)).then(translation(canvas.width / 2, canvas.height / 2))
        }
    }
}

data class Rectangle(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
}

// Framing is stored in the oriented source, independent of preview size.
// The output aspect belongs to the project; a crop fills that output frame.
data class CropSettings(val id: UUID = UUID.randomUUID(), val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0,
                        val rotation: Double = 0.0) {   // Clockwise degrees, applied before output-space mirrors.
    private val radians: Double get() = rotation * Math.PI / 180
    val valid: Boolean get() = x.isFinite() && y.isFinite() && zoom.isFinite() && rotation.isFinite() && rotation in -180.0..180.0
        && x in 0.0..1.0 && y in 0.0..1.0 && zoom in 1.0..8.0
    // The crop window is axis-aligned in output space. Its inverse-rotated
    // footprint must fit inside the source, including when panned to an edge.
    fun rect(source: CanvasSize, canvas: CanvasSize): Rectangle {
        if (source.width <= 0 || source.height <= 0 || canvas.width <= 0 || canvas.height <= 0) return Rectangle(0.0, 0.0, 0.0, 0.0)
        val c = abs(cos(radians)); val s = abs(sin(radians))
        val scale = maxOf((canvas.width * c + canvas.height * s) / source.width, (canvas.width * s + canvas.height * c) / source.height) * zoom.coerceIn(1.0, 8.0)
        val width = canvas.width / scale; val height = canvas.height / scale
        val halfX = (width * c + height * s) / 2; val halfY = (width * s + height * c) / 2
        val cx = minOf(source.width - halfX, maxOf(halfX, x * source.width))
        val cy = minOf(source.height - halfY, maxOf(halfY, y * source.height))
        return Rectangle(cx - width / 2, cy - height / 2, width, height)
    }
    fun transform(source: CanvasSize, canvas: CanvasSize): Affine {
        val area = rect(source, canvas)
        if (area.width <= 0) return Affine()
        val scale = canvas.width / area.width
        return Affine.translation(-area.midX, -area.midY).then(Affine.rotation(radians)).then(Affine.scale(scale, scale))
            .then(Affine.translation(canvas.width / 2, canvas.height / 2))
    }
    /// The same framing with its centre moved to where the picture can actually be.
    fun clamped(source: CanvasSize, canvas: CanvasSize): CropSettings {
        val result = copy(zoom = zoom.coerceIn(1.0, 8.0), rotation = normalizedRotation(rotation))
        val area = result.rect(source, canvas)
        return result.copy(x = area.midX / maxOf(1.0, source.width), y = area.midY / maxOf(1.0, source.height))
    }
    fun isFullFrame(source: CanvasSize, canvas: CanvasSize): Boolean {
        if (abs(normalizedRotation(rotation)) >= 0.00001 || zoom != 1.0 || source.width <= 0 || source.height <= 0) return false
        val area = rect(source, canvas)
        // Encoder-safe even dimensions can slightly change an imported picture's
        // aspect. That rounding alone must not create a crop.
        return abs(area.width - source.width) < maxOf(2.0, source.width * 0.0025) && abs(area.height - source.height) < maxOf(2.0, source.height * 0.0025)
    }
    companion object {
        fun normalizedRotation(angle: Double): Double {
            if (!angle.isFinite()) return 0.0
            val value = (angle + 180).rem(360.0)
            val normalized = (if (value < 0) value + 360 else value) - 180
            return if (normalized == -180.0 && angle > 0) 180.0 else normalized
        }
    }
}

data class MirrorSettings(val id: UUID = UUID.randomUUID(), val horizontal: Boolean = false, val vertical: Boolean = false) {
    fun transform(canvas: CanvasSize) = Affine(a = if (horizontal) -1.0 else 1.0, d = if (vertical) -1.0 else 1.0,
                                               tx = if (horizontal) canvas.width else 0.0, ty = if (vertical) canvas.height else 0.0)
}

enum class TextStyle { Clean, Bold, Box, Highlight }
enum class TextColor(val red: Double, val green: Double, val blue: Double) {
    White(1.0, 1.0, 1.0), Black(0.03, 0.03, 0.04), Yellow(1.0, 0.85, 0.16), Coral(1.0, 0.3, 0.36), Mint(0.35, 0.94, 0.72), Blue(0.35, 0.66, 1.0);
    val argb: Int get() = (0xFF shl 24) or (Math.round(red * 255).toInt() shl 16) or (Math.round(green * 255).toInt() shl 8) or Math.round(blue * 255).toInt()
}
/// A title on the picture. Its times are in the source recording, so it stays
/// with its words through cuts, moves and speed changes; its place and size are
/// fractions of the frame, so it looks the same at every export size.
data class TextOverlay(val id: UUID = UUID.randomUUID(), val text: String = starterText, val start: Double = 0.0, val end: Double = 3.0,
                       val x: Double = 0.5, val y: Double = 0.5,
                       // Font size relative to the shorter canvas dimension; consistent on rotation.
                       val size: Double = 0.10, val style: TextStyle = TextStyle.Bold, val color: TextColor = TextColor.White,
                       /** A designed title (font, colours, outline, shadow, plate); null draws `style` and `color`. */
                       val appearance: TitleDesign? = null,
                       /** A label and a headline on a card; the headline's words are `text`. */
                       val labelHeadline: LabelHeadline? = null,
                       /** Entrance and exit animation. */ val motion: TitleMotion? = null,
                       /** Written by the iPhone app when the middle of an animated title was edited; kept, and followed, here. */
                       val playbackClock: TitlePlaybackClock? = null) {
    companion object { const val starterText = "Text" }
    /// A card with no headline still shows its label.
    val displayText: String get() = if (text.isBlank()) labelHeadline?.label ?: "" else text
    val hasText: Boolean get() = displayText.isNotBlank()
    val fontFaces: List<String> get() = listOfNotNull(appearance?.face, labelHeadline?.labelStyle?.face, labelHeadline?.headlineStyle?.face)
    val design: TitleDesign get() = appearance ?: TitleDesign.legacy(style, color)
    fun valid(sourceDuration: Double) = start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= sourceDuration + 0.001
        && text.length <= 500 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && size.isFinite() && size in 0.02..0.3 && appearance?.valid != false && labelHeadline?.valid != false
        && motion?.valid != false && playbackClock?.valid != false
}
object EditorDurationPresets { val wholeSeconds: List<Double> = (1..10).map { it.toDouble() } + listOf(15.0, 30.0, 60.0) }

/// A picture placed over the video, timed in source seconds like a title.
/// `width` is a fraction of the shorter canvas edge; the image keeps its aspect.
data class ImageOverlay(val id: UUID = UUID.randomUUID(), val filename: String, val start: Double = 0.0, val end: Double = 3.0,
                        val x: Double = 0.5, val y: Double = 0.5, val width: Double = 0.4, val opacity: Double = 1.0) {
    /// Where the image goes on the canvas, in pixels with y down.
    fun rect(imageWidth: Double, imageHeight: Double, canvas: CanvasSize): Rectangle {
        val w = minOf(canvas.width, canvas.height) * width; val h = w * imageHeight / maxOf(1.0, imageWidth)
        return Rectangle(canvas.width * x - w / 2, canvas.height * y - h / 2, w, h)
    }
    fun valid(sourceDuration: Double) = start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= sourceDuration + 0.001
        && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && width.isFinite() && width in 0.03..3.0 && opacity.isFinite() && opacity in 0.0..1.0
    companion object {
        fun validFilename(name: String) = name.startsWith("image-") && name == java.io.File(name).name && !name.contains("\\")
            && name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "heic")
    }
}

data class SpeedSettings(val id: UUID = UUID.randomUUID(), val rate: Double = 1.0)

enum class OutputShape(val title: String, val aspect: Double?) {
    Original("Original", null), Landscape("16:9", 16.0 / 9), Portrait("9:16", 9.0 / 16), Square("1:1", 1.0), Feed("4:5", 4.0 / 5);
    companion object {
        fun canvas(source: CanvasSize, aspect: Double?): CanvasSize {
            if (aspect == null) return source
            // Keep the source's short-edge resolution, rounded to encoder-safe pixels.
            val edge = minOf(source.width, source.height)
            fun even(value: Double) = maxOf(2.0, Math.floor(value / 2 + 0.5) * 2)
            return CanvasSize(even(if (aspect >= 1) edge * aspect else edge), even(if (aspect >= 1) edge else edge / aspect))
        }
    }
}

/// Where one clip's picture goes on the canvas: its crop fills the frame, an
/// uncropped picture keeps its whole frame inside it, and mirrors act on the result.
fun TimelineClip.pictureTransform(source: CanvasSize, canvas: CanvasSize): Affine {
    val placed = crop?.transform(source, canvas) ?: Affine.fit(source, canvas)
    return mirror?.let { placed.then(it.transform(canvas)) } ?: placed
}
