package yourec

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/// The subject a Crash zoom (Follow face) or Dolly zoom aims at, after the
/// iPhone app's VideoEffectSubjectTracking.swift. Boxes are normalised and
/// top-down in the recorded frame.
data class EffectSubjectSample(val time: Double, val x: Double, val y: Double, val width: Double, val height: Double,
                               val confidence: Double, val face: Boolean, val lost: Boolean? = null) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
    val valid: Boolean get() = time.isFinite() && time in 0.0..604800.0 && confidence.isFinite() && confidence in 0.0..1.0
        && listOf(x, y, width, height).all { it.isFinite() } && width > 0 && height > 0 && x >= 0 && y >= 0 && x + width <= 1.0001 && y + height <= 1.0001
    fun overlap(other: EffectSubjectSample): Double {
        val left = max(x, other.x); val top = max(y, other.y); val right = min(x + width, other.x + other.width); val bottom = min(y + height, other.y + other.height)
        if (right <= left || bottom <= top) return 0.0
        val inter = (right - left) * (bottom - top)
        return inter / max(0.000001, width * height + other.width * other.height - inter)
    }
    fun contains(px: Double, py: Double) = px >= x && px <= x + width && py >= y && py <= y + height
}

data class EffectSubjectTrack(val samples: List<EffectSubjectSample> = emptyList(), val stepwise: Boolean? = null) {
    val valid: Boolean get() = samples.size <= 2_000_000 && samples.all { it.valid } && samples.zipWithNext().all { (a, b) -> a.time < b.time }
    /// The subject at `time`: interpolated between samples under half a second apart; null when lost.
    fun sample(at: Double): EffectSubjectSample? {
        var a = 0; var b = samples.size
        while (a < b) { val m = (a + b) / 2; if (samples[m].time <= at) a = m + 1 else b = m }
        if (a <= 0) return null
        val previous = samples[a - 1]
        if (previous.lost == true || at - previous.time > 0.5) return null
        if (stepwise == true) return previous
        if (a >= samples.size || samples[a].lost == true || samples[a].time - previous.time > 0.5) return previous
        val next = samples[a]; val f = ((at - previous.time) / (next.time - previous.time)).coerceIn(0.0, 1.0)
        return previous.copy(time = at, x = previous.x + (next.x - previous.x) * f, y = previous.y + (next.y - previous.y) * f,
            width = previous.width + (next.width - previous.width) * f, height = previous.height + (next.height - previous.height) * f)
    }
}

/// Choosing and smoothing the subject from per-frame detections, as the
/// iPhone app's EffectSubjectTracker does after each detection: the face
/// overlapping the previous one, else the one under (or nearest) the seed
/// point, else the largest; the centre eased with a 0.12 s time constant.
/// Android detects faces on every analysed frame (android.media.FaceDetector);
/// the iPhone app tracks objects between detections with Vision.
class EffectSubjectChooser(private val pointX: Double?, private val pointY: Double?) {
    private var previous: EffectSubjectSample? = null
    fun update(candidates: List<EffectSubjectSample>, time: Double): EffectSubjectSample? {
        val usable = candidates.filter { it.confidence >= 0.5 && it.valid }
        val last = previous
        val chosen = if (last != null && time - last.time < 0.5) {
            usable.filter { it.face == last.face && it.overlap(last) > 0.1 }.maxByOrNull { it.overlap(last) }
        } else if (pointX != null && pointY != null) {
            usable.minWithOrNull(Comparator { a, b ->
                val ia = a.contains(pointX, pointY); val ib = b.contains(pointX, pointY)
                if (ia != ib) (if (ia) -1 else 1) else hypot(a.centerX - pointX, a.centerY - pointY).compareTo(hypot(b.centerX - pointX, b.centerY - pointY))
            })
        } else usable.maxByOrNull { it.width * it.height }
        if (chosen == null) { if (last != null && time - last.time >= 0.5) previous = null; return null }
        var result = chosen.copy(time = time)
        if (last != null && time > last.time && time - last.time < 0.5 && last.face == result.face) {
            val alpha = 1 - exp(-(time - last.time) / 0.12)
            val cx = last.centerX + (result.centerX - last.centerX) * alpha; val cy = last.centerY + (result.centerY - last.centerY) * alpha
            result = result.copy(x = cx - result.width / 2, y = cy - result.height / 2)
        }
        // Kept inside the frame.
        val left = max(0.0, result.x); val top = max(0.0, result.y)
        result = result.copy(x = left, y = top, width = min(1.0, result.x + result.width) - left, height = min(1.0, result.y + result.height) - top)
        if (!result.valid) { previous = null; return null }
        previous = result
        return result
    }
}
