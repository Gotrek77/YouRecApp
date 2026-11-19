package yourec

import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

enum class ColorLook(val title: String) {
    Natural("Natural"), Warm("Warm"), Cool("Cool shadows"), Cinema("Cinema"), Mono("Mono"), Portrait("Portrait"), Daylight("Daylight"),
    GoldenHour("Golden hour"), MutedFilm("Muted film"), RichContrast("Rich contrast"), Pastel("Pastel"), Night("Night"), Silver("Silver"), Ink("Ink");
    /// The name in project documents, as the iPhone app writes it.
    val raw: String get() = name.replaceFirstChar { it.lowercase() }
}

/// A .cube file kept with the project under a name made from its content, so
/// editing a library can never change an earlier edit or an archived project.
data class ImportedLUT(val filename: String, val name: String) {
    val valid: Boolean get() = filename.startsWith("effect-lut-") && filename.endsWith(".cube") && filename.length < 160
        && filename == File(filename).name && !filename.contains("\\") && name.isNotEmpty() && name.length <= 120
}

/// One grade on a clip: light and white balance first, then a look or an
/// imported LUT, mixed with the balanced picture by `amount`.
data class ColorSettings(val id: UUID = UUID.randomUUID(), val look: ColorLook = ColorLook.Natural, val amount: Double = 1.0, val imported: ImportedLUT? = null,
                         val exposure: Double? = null, val warmth: Double? = null, val tint: Double? = null) {
    val hasBalance: Boolean get() = (exposure ?: 0.0) != 0.0 || (warmth ?: 0.0) != 0.0 || (tint ?: 0.0) != 0.0
    // A zero-strength look remains editable; only an empty grade is removed.
    val isIdentity: Boolean get() = !hasBalance && look == ColorLook.Natural && imported == null
    val valid: Boolean get() = amount.isFinite() && amount in 0.0..1.0 && (exposure ?: 0.0).let { it.isFinite() && it in -4.0..4.0 }
        && (warmth ?: 0.0).let { it.isFinite() && it in -1.0..1.0 } && (tint ?: 0.0).let { it.isFinite() && it in -1.0..1.0 } && (imported?.valid ?: true)
    val title: String get() = imported?.name ?: if (look == ColorLook.Natural && hasBalance) "Color balance" else look.title
    /// Per-channel multipliers for exposure and white balance, applied to
    /// display values. This approximates the iPhone app's linear-light exposure
    /// and its temperature/tint adaptation; it is not the same arithmetic.
    val gains: FloatArray get() {
        val light = 2.0.pow((exposure ?: 0.0) / 2.2); val w = warmth ?: 0.0; val t = tint ?: 0.0
        return floatArrayOf((light * (1 + 0.12 * w) * (1 + 0.05 * t)).toFloat(), (light * (1 - 0.10 * t)).toFloat(), (light * (1 - 0.12 * w) * (1 + 0.05 * t)).toFloat())
    }
    /// What the renderer computes for one display colour, here for reference and checks.
    fun graded(rgb: FloatArray, cube: ColorCube?): FloatArray {
        val g = gains
        val balanced = FloatArray(3) { (rgb[it] * g[it]).coerceIn(0f, 1f) }
        if (cube == null || amount <= 0) return balanced
        val mapped = cube.sample(balanced[0], balanced[1], balanced[2])
        return FloatArray(3) { (balanced[it] + (mapped[it] - balanced[it]) * amount.toFloat()).coerceIn(0f, 1f) }
    }
}

class LUTError(reason: String) : Exception(reason)

/// A colour cube: `dimension` points per axis, three floats per point, red changing fastest.
class ColorCube(val dimension: Int, val rgb: FloatArray, val domainMin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), val domainMax: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0), val title: String? = null) {
    private fun at(r: Int, g: Int, b: Int, channel: Int) = rgb[((b * dimension + g) * dimension + r) * 3 + channel]
    /// Trilinear lookup, the meaning of a cube that any correct renderer must match.
    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        fun place(value: Float, axis: Int) = (((value - domainMin[axis]) / (domainMax[axis] - domainMin[axis])).coerceIn(0.0, 1.0) * (dimension - 1))
        val x = place(red, 0); val y = place(green, 1); val z = place(blue, 2)
        val x0 = floor(x).toInt().coerceAtMost(dimension - 2); val y0 = floor(y).toInt().coerceAtMost(dimension - 2); val z0 = floor(z).toInt().coerceAtMost(dimension - 2)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        return FloatArray(3) { c ->
            fun line(g: Int, b: Int) = at(x0, g, b, c) * (1 - fx) + at(x0 + 1, g, b, c) * fx
            fun face(b: Int) = line(y0, b) * (1 - fy) + line(y0 + 1, b) * fy
            (face(z0) * (1 - fz) + face(z0 + 1) * fz).toFloat()
        }
    }

    /// The cube laid out as a flat picture for a renderer without 3D textures:
    /// one tile per blue step, `columns` tiles to a row. Values are stored in 8 bits.
    class Atlas(val dimension: Int, val columns: Int, val rows: Int, val rgba: ByteArray) {
        val width: Int get() = dimension * columns
        val height: Int get() = dimension * rows
        /// The lookup a fragment shader performs on this picture: bilinear inside
        /// two neighbouring tiles, then a mix between them.
        fun sample(red: Float, green: Float, blue: Float): FloatArray {
            val n = dimension
            val z = blue.coerceIn(0f, 1f) * (n - 1); val z0 = floor(z).toInt(); val z1 = minOf(z0 + 1, n - 1); val f = z - z0
            fun tile(slice: Int): FloatArray {
                // Texel centres: half a texel in from each edge of the tile, so tiles never bleed.
                val u = (slice % columns) * n + red.coerceIn(0f, 1f) * (n - 1); val v = (slice / columns) * n + green.coerceIn(0f, 1f) * (n - 1)
                val u0 = floor(u).toInt(); val v0 = floor(v).toInt(); val fu = u - u0; val fv = v - v0
                fun texel(x: Int, y: Int, c: Int) = (rgba[(minOf(y, height - 1) * width + minOf(x, width - 1)) * 4 + c].toInt() and 0xff) / 255f
                return FloatArray(3) { c -> (texel(u0, v0, c) * (1 - fu) + texel(u0 + 1, v0, c) * fu) * (1 - fv) + (texel(u0, v0 + 1, c) * (1 - fu) + texel(u0 + 1, v0 + 1, c) * fu) * fv }
            }
            val a = tile(z0); val b = tile(z1)
            return FloatArray(3) { a[it] + (b[it] - a[it]) * f }
        }
    }
    fun atlas(): Atlas {
        val columns = Math.ceil(Math.sqrt(dimension.toDouble())).toInt(); val rows = (dimension + columns - 1) / columns
        val bytes = ByteArray(dimension * columns * dimension * rows * 4)
        for (b in 0 until dimension) for (g in 0 until dimension) for (r in 0 until dimension) {
            val x = (b % columns) * dimension + r; val y = (b / columns) * dimension + g
            val o = (y * dimension * columns + x) * 4
            for (c in 0 until 3) bytes[o + c] = Math.round(at(r, g, b, c).coerceIn(0f, 1f) * 255).toByte()
            bytes[o + 3] = 0xff.toByte()
        }
        return Atlas(dimension, columns, rows, bytes)
    }

    companion object {
        const val lookDimension = 33
        const val maximumBytes = 32 * 1024 * 1024
        private val looks = HashMap<ColorLook, ColorCube>()

        // Authored SDR creative looks. No external weights, licensed looks or Log
        // camera transforms. Identity-to-look interpolation controls the strength.
        @Synchronized fun of(look: ColorLook): ColorCube = looks.getOrPut(look) {
            val n = lookDimension
            val values = FloatArray(n * n * n * 3)
            var o = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                var rgb = doubleArrayOf(r.toDouble() / (n - 1), g.toDouble() / (n - 1), b.toDouble() / (n - 1))
                val luma = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
                fun each(change: (Double) -> Double) { rgb = DoubleArray(3) { change(rgb[it]) } }
                when (look) {
                    ColorLook.Natural -> { }
                    ColorLook.Warm -> { rgb[0] = rgb[0].pow(0.92); rgb[2] = rgb[2].pow(1.10) }
                    ColorLook.Cool -> { rgb[0] = rgb[0].pow(1.08); rgb[2] = rgb[2].pow(0.92) }
                    ColorLook.Mono -> each { luma }
                    ColorLook.Cinema -> {
                        each { (0.5 + (it - 0.5) * 1.12).coerceIn(0.0, 1.0) }
                        rgb[0] += 0.035 * luma; rgb[1] += 0.012 * (1 - luma); rgb[2] += 0.035 * (1 - luma)
                        each { luma + (it - luma) * 0.85 }
                    }
                    ColorLook.Portrait -> {
                        each { luma + (it - luma) * 0.92 }
                        rgb[0] += 0.018 * luma * (1 - luma); rgb[2] -= 0.025 * luma * (1 - luma)
                        each { it * 0.95 + 0.025 }
                    }
                    ColorLook.Daylight -> { each { 0.5 + (it - 0.5) * 1.04 }; each { luma + (it - luma) * 1.08 } }
                    ColorLook.GoldenHour -> {
                        rgb[0] += 0.12 * luma * (1 - luma); rgb[1] += 0.025 * luma
                        rgb[2] *= 0.86; each { maxOf(0.0, it).pow(0.95) }
                    }
                    ColorLook.MutedFilm -> { each { 0.055 + 0.89 * (luma + (it - luma) * 0.72) }; rgb[2] += 0.018 * (1 - luma) }
                    ColorLook.RichContrast -> each { val x = it.coerceIn(0.0, 1.0); x * x * (3 - 2 * x) * 0.55 + x * 0.45 }
                    ColorLook.Pastel -> each { 0.075 + 0.88 * maxOf(0.0, luma + (it - luma) * 0.65).pow(0.91) }
                    ColorLook.Night -> { each { maxOf(0.0, it * 0.94 - 0.012) }; rgb[2] += 0.045 * (1 - luma); rgb[1] += 0.012 * (1 - luma) }
                    ColorLook.Silver -> { val y = maxOf(0.0, rgb[0] * 0.3 + rgb[1] * 0.6 + rgb[2] * 0.1).pow(0.92); rgb = doubleArrayOf(y * 0.98, y, minOf(1.0, y * 1.015)) }
                    ColorLook.Ink -> { val y = ((luma - 0.08) / 0.84).coerceIn(0.0, 1.0); val dense = y * y * (3 - 2 * y); each { dense } }
                }
                for (c in 0 until 3) values[o++] = rgb[c].coerceIn(0.0, 1.0).toFloat()
            }
            ColorCube(n, values)
        }

        /// Strict, bounded CUBE reader. Red is the fastest-changing axis. Both
        /// standalone 1D curves and 3D cubes are read; combined shaper+cube files
        /// are rejected rather than silently losing their shaper.
        fun parse(data: ByteArray): ColorCube {
            if (data.size > maximumBytes) throw LUTError("Choose a UTF-8 .cube file smaller than 32 MB.")
            var size: Int? = null; var oneD = false; var title: String? = null
            val low = doubleArrayOf(0.0, 0.0, 0.0); val high = doubleArrayOf(1.0, 1.0, 1.0)
            val rows = ArrayList<FloatArray>(); val seen = HashSet<String>()
            for (raw in String(data, Charsets.UTF_8).split('\n', '\r')) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                val first = parts[0]
                if (first == "TITLE") {
                    if (!seen.add("TITLE")) throw LUTError("The LUT contains duplicate TITLE entries.")
                    title = line.drop(5).trim('"', ' ').take(120); continue
                }
                if (first == "LUT_3D_SIZE" || first == "LUT_1D_SIZE") {
                    val value = parts.getOrNull(1)?.toIntOrNull()
                    if (size != null || rows.isNotEmpty() || parts.size != 2 || value == null || value !in (if (first == "LUT_3D_SIZE") 2..65 else 2..16384))
                        throw LUTError("Use a standalone 3D LUT (2–65 points) or 1D LUT (2–16,384 points). Combined shaper LUTs are not supported.")
                    size = value; oneD = first == "LUT_1D_SIZE"; continue
                }
                if (first == "DOMAIN_MIN" || first == "DOMAIN_MAX") {
                    val values = parts.drop(1).mapNotNull { it.toDoubleOrNull() }
                    if (rows.isNotEmpty() || !seen.add(first) || parts.size != 4 || values.size != 3 || values.any { !it.isFinite() || abs(it) > 65536 }) throw LUTError("Invalid LUT input domain.")
                    for (i in 0 until 3) (if (first == "DOMAIN_MIN") low else high)[i] = values[i]
                    continue
                }
                val expected = size?.let { if (oneD) it else it * it * it }
                if (expected == null || parts.size != 3 || rows.size >= expected) throw LUTError("Unsupported LUT header or incorrect number of color entries.")
                val values = parts.mapNotNull { it.toFloatOrNull() }
                if (values.size != 3 || values.any { !it.isFinite() || abs(it) > 65536 }) throw LUTError("The LUT contains an invalid color value.")
                rows.add(floatArrayOf(values[0], values[1], values[2]))
            }
            val n = size
            if (n == null || rows.size != (if (oneD) n else n * n * n) || (0 until 3).any { high[it] <= low[it] }) throw LUTError("The LUT is incomplete or has an invalid input domain.")
            if (!oneD) return ColorCube(n, FloatArray(rows.size * 3) { rows[it / 3][it % 3] }, low, high, title)
            // Three independent curves become a cube of the look size: each channel reads its own curve.
            val d = lookDimension
            fun curve(value: Double, channel: Int): Float {
                val x = value * (n - 1); val i = floor(x).toInt().coerceAtMost(n - 2)
                return (rows[i][channel] * (1 - (x - i)) + rows[i + 1][channel] * (x - i)).toFloat()
            }
            val values = FloatArray(d * d * d * 3)
            var o = 0
            for (b in 0 until d) for (g in 0 until d) for (r in 0 until d) {
                values[o++] = curve(r.toDouble() / (d - 1), 0); values[o++] = curve(g.toDouble() / (d - 1), 1); values[o++] = curve(b.toDouble() / (d - 1), 2)
            }
            return ColorCube(d, values, low, high, title)
        }

        /// Check a picked file and keep it with the project under its content hash.
        fun ingest(data: ByteArray, suggestedName: String, folder: File): ImportedLUT {
            val cube = parse(data)
            val filename = "effect-lut-" + RecoveryFile.hash(data) + ".cube"
            val target = File(folder, filename)
            if (!target.exists()) RecoveryFile.write(data, target)
            return ImportedLUT(filename, cube.title?.takeIf { it.isNotEmpty() } ?: suggestedName.substringBeforeLast('.').take(120).ifEmpty { "Imported LUT" })
        }
        fun load(lut: ImportedLUT, folder: File): ColorCube {
            if (!lut.valid) throw LUTError("Invalid LUT reference.")
            val file = File(folder, lut.filename)
            if (file.length() > maximumBytes) throw LUTError("The LUT is too large.")
            return parse(file.readBytes())
        }
    }
}
