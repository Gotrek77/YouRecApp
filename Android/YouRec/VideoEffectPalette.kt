package yourec

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/// Quantization's palettes, after the iPhone app's VideoEffectMath.swift
/// (EffectPalette, EffectPaletteAnalysis, EffectRandom) and
/// VideoEffectAssets.swift (displayPalette): colour centres found by k-means
/// in Oklab with a seeded k-means++ start, luma bands for the monochrome and
/// banded modes, smoothed between analysed frames unless the scene cuts.
data class EffectPalette(val colors: List<EffectRGB>, val thresholds: List<Double>) {
    val valid: Boolean get() = colors.size in 2..16 && colors.all { it.valid } && thresholds.size == colors.size - 1
        && thresholds.all { it.isFinite() && it in 0.0..1.0 } && thresholds.zipWithNext().all { (a, b) -> a <= b }
}
data class EffectPaletteFrame(val time: Double, val palette: EffectPalette)
data class EffectPaletteTrack(val frames: List<EffectPaletteFrame>) {
    val valid: Boolean get() = frames.isNotEmpty() && frames.size <= 3_100_000 && frames.all { it.time.isFinite() && it.time in 0.0..604800.0 && it.palette.valid }
        && frames.zipWithNext().all { (a, b) -> a.time < b.time }
    fun palette(at: Double): EffectPalette? {
        if (frames.isEmpty()) return null
        var a = 0; var b = frames.size
        while (a < b) { val m = (a + b) / 2; if (frames[m].time <= at) a = m + 1 else b = m }
        return frames[maxOf(0, a - 1)].palette
    }
}

/// The published Oklab equations (Björn Ottosson), on linear BT.709.
object EffectColorMath {
    fun linear(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    fun encoded(x: Double) = if (x <= 0.0031308) x * 12.92 else 1.055 * maxOf(0.0, x).pow(1 / 2.4) - 0.055
    fun linear(c: EffectRGB) = doubleArrayOf(linear(c.red), linear(c.green), linear(c.blue))
    fun rgb(c: DoubleArray) = EffectRGB(encoded(c[0]).coerceIn(0.0, 1.0), encoded(c[1]).coerceIn(0.0, 1.0), encoded(c[2]).coerceIn(0.0, 1.0))
    fun lab(c: DoubleArray): DoubleArray {
        val l = cbrt(0.4122214708 * c[0] + 0.5363325363 * c[1] + 0.0514459929 * c[2])
        val m = cbrt(0.2119034982 * c[0] + 0.6806995451 * c[1] + 0.1073969566 * c[2])
        val s = cbrt(0.0883024619 * c[0] + 0.2817188376 * c[1] + 0.6299787005 * c[2])
        return doubleArrayOf(0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s, 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s)
    }
    fun linearFromLab(c: DoubleArray): DoubleArray {
        val l = (c[0] + 0.3963377774 * c[1] + 0.2158037573 * c[2]).pow(3); val m = (c[0] - 0.1055613458 * c[1] - 0.0638541728 * c[2]).pow(3)
        val s = (c[0] - 0.0894841775 * c[1] - 1.291485548 * c[2]).pow(3)
        return doubleArrayOf(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s)
    }
    fun luminance(c: DoubleArray) = c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722
    fun prepared(c: DoubleArray): DoubleArray {
        val y = luminance(c); val s = y * y * (3 - 2 * y); val target = y + (s - y) * 0.2
        return DoubleArray(3) { (c[it] + target - y).coerceIn(0.0, 1.0) }
    }
    fun distance2(a: DoubleArray, b: DoubleArray) = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])
}

/// SplitMix64, as the iPhone app's EffectRandom.
class EffectRandom(private var state: Long) {
    fun next(): Double {
        state += -0x61c8864680b583ebL
        var x = state
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        return ((x xor (x ushr 31)) ushr 11).toDouble() / 9007199254740992.0
    }
}

object EffectPaletteAnalysis {
    /// Palette of `count` colours for samples in linear RGB (three per pixel).
    fun analyze(samples: List<DoubleArray>, count: Int, previous: EffectPalette? = null, elapsed: Double = 0.2, seed: Long = 0): EffectPalette {
        val n = count.coerceIn(2, 16)
        val rgb = samples.filter { s -> s.all { it.isFinite() } }.map { s -> EffectColorMath.prepared(DoubleArray(3) { s[it].coerceIn(0.0, 1.0) }) }
        if (rgb.isEmpty()) return EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5))
        val labs = rgb.map { EffectColorMath.lab(it) }; val ys = rgb.map { EffectColorMath.luminance(it) }.sorted()
        val thresholds = (1 until n).map { i -> ys[minOf(ys.size - 1, (i.toDouble() * ys.size / n).toInt())] }
        val random = EffectRandom(seed)
        val centers = ArrayList<DoubleArray>()
        if (previous != null && previous.colors.size == n) previous.colors.forEach { centers.add(EffectColorMath.lab(EffectColorMath.linear(it))) }
        else {
            centers.add(labs[(random.next() * labs.size).toInt() % labs.size])
            while (centers.size < n) {
                val distances = labs.map { p -> centers.minOf { EffectColorMath.distance2(p, it) } }
                val target = random.next() * distances.sum()
                var cumulative = 0.0; var index = labs.size - 1
                for (i in labs.indices) { cumulative += distances[i]; if (cumulative >= target) { index = i; break } }
                centers.add(labs[index])
            }
        }
        repeat(6) {
            val sums = Array(n) { DoubleArray(3) }; val weights = IntArray(n)
            for (p in labs) {
                var best = 0; var distance = Double.POSITIVE_INFINITY
                for (i in centers.indices) { val d = EffectColorMath.distance2(p, centers[i]); if (d < distance) { distance = d; best = i } }
                for (k in 0..2) sums[best][k] += p[k]; weights[best]++
            }
            val populated = ArrayList<DoubleArray>()
            for (i in centers.indices) if (weights[i] > 0) { centers[i] = DoubleArray(3) { sums[i][it] / weights[i] }; populated.add(centers[i]) }
            if (populated.size < n) {
                // A blank first frame can seed identical grey centres; revive empty ones from the least represented pixel.
                val residual = labs.map { p -> populated.minOf { EffectColorMath.distance2(p, it) } }.toDoubleArray()
                for (i in centers.indices) {
                    if (weights[i] != 0) continue
                    val farthest = residual.indices.maxByOrNull { residual[it] } ?: break
                    centers[i] = labs[farthest]
                    if (residual[farthest] < 1e-10) continue
                    for (j in labs.indices) residual[j] = minOf(residual[j], EffectColorMath.distance2(labs[j], centers[i]))
                }
            }
        }
        var sorted: List<DoubleArray> = centers.sortedBy { it[0] }
        var bands = thresholds
        if (previous != null && previous.colors.size == n) {
            val old = previous.colors.map { EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it[0] }
            val cut = sorted.zip(old).any { (a, b) -> sqrt(EffectColorMath.distance2(a, b)) > 0.25 }
            if (!cut) {
                val alpha = 1 - exp(-maxOf(0.0, elapsed) / 0.5)
                sorted = old.zip(sorted).map { (o, c) -> DoubleArray(3) { o[it] + (c[it] - o[it]) * alpha } }
                if (previous.thresholds.size == bands.size) bands = previous.thresholds.zip(bands).map { (o, c) -> o + (c - o) * alpha }
            }
        }
        return EffectPalette(sorted.map { EffectColorMath.rgb(EffectColorMath.linearFromLab(it)) }, bands)
    }
}

data class EffectPalettePreset(val id: String, val name: String, val colors: List<String>) {
    fun sampled(count: Int): List<EffectRGB> {
        val sorted = colors.map { EffectRGB.hex(it) }.sortedBy { EffectColorMath.lab(EffectColorMath.linear(it))[0] }
        if (sorted.size < 2 || count < 2) return sorted
        return (0 until count).map { sorted[Math.round(it.toDouble() * (sorted.size - 1) / (count - 1)).toInt()] }
    }
    companion object {
        /// The iPhone app's PopPalettes.json, as it is.
        val pop = listOf(
        EffectPalettePreset("pop1", "Pop 1", listOf("#141326", "#29204A", "#442059", "#64285D", "#8F285B", "#BB305E", "#D74667", "#E45F73", "#DC7D60", "#CB9A52", "#C8AD4F", "#D4C958", "#DEDE78", "#BEE6CF", "#D4EDF4", "#F4F4D8")),
        EffectPalettePreset("pop2", "Pop 2", listOf("#10252B", "#183943", "#204D57", "#29616A", "#34797A", "#45928C", "#73A291", "#9BAE8C", "#BAB582", "#D2BE77", "#E3CA7C", "#EBD58F", "#EFE0AA", "#F1E9C2", "#F5F1D9", "#FAF8ED")),
        EffectPalettePreset("pop3", "Pop 3", listOf("#231A35", "#3B2449", "#572851", "#773255", "#934453", "#B15350", "#CC6653", "#DE7D5D", "#E99772", "#EEAF8C", "#F0C1A3", "#E9CFB7", "#DCDCCF", "#E3E7E0", "#EDF0E8", "#FAF6E8"))
        )
    }
}

object EffectPalettes {
    /// The palette a Quantization draws with at `time`, as the iPhone app's displayPalette.
    fun display(effect: VideoEffectSettings, time: Double, analyzed: EffectPalette?, locked: EffectPalette?): EffectPalette? {
        val count = effect.number("colors", time).toInt()
        var palette = if (analyzed?.colors?.size == count) analyzed else null
        if (effect.toggle("lockPalette", time) && locked != null && locked.colors.size == count) palette = locked
        if (effect.toggle("monochrome", time)) return palette
        val choice = effect.choice("palette", time)
        val saved = effect.palette
        if (choice == "Saved" && saved != null && saved.size >= 2) {
            val colors = sample(saved, count)
            // Nearest-colour matching needs no scene analysis; bands come from the colours.
            val luminances = colors.map { EffectColorMath.luminance(EffectColorMath.linear(it)) }.sorted()
            return EffectPalette(colors, palette?.thresholds ?: luminances.zipWithNext().map { (a, b) -> (a + b) / 2 })
        }
        EffectPalettePreset.pop.firstOrNull { it.name == choice }?.let { preset -> palette = palette?.copy(colors = preset.sampled(count)) }
        return palette
    }
    /// A saved palette brought to `count` colours, keeping distinct hues.
    fun sample(colors: List<EffectRGB>, count: Int): List<EffectRGB> {
        if (colors.size <= 1 || count <= 1 || count == colors.size) return colors
        val entries = colors.map { it to EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it.second[0] }
        if (entries.size < count) return (0 until count).map { entries[Math.round(it.toDouble() * (entries.size - 1) / (count - 1)).toInt()].first }
        val chosen = mutableListOf(0)
        val distance = entries.map { EffectColorMath.distance2(it.second, entries[0].second) }.toDoubleArray(); distance[0] = -1.0
        while (chosen.size < count) {
            val next = distance.indices.maxByOrNull { distance[it] } ?: break
            chosen.add(next); distance[next] = -1.0
            for (i in entries.indices) distance[i] = minOf(distance[i], EffectColorMath.distance2(entries[i].second, entries[next].second))
        }
        return chosen.sorted().map { entries[it].first }
    }
    /// The saved quantization palette of the project form (lockedPalette), when well formed.
    fun locked(raw: Any?): EffectPalette? = try {
        raw?.let { value ->
            val fields = Fields.of(value)
            EffectPalette(fields.list("colors").map { c -> Fields.of(c).let { EffectRGB(it.double("red"), it.double("green"), it.double("blue")) } }, fields.list("thresholds").map { Fields.double(it) })
        }?.takeIf { it.valid }
    } catch (e: ProjectError) { null }
}
