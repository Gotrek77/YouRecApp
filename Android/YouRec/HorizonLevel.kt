package yourec

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/// The iPhone recorder's horizon level. Positive angles straighten a line that
/// descends to the right in the delivered pixels.
class HorizonFraming(val radians: Double, val magnification: Double) {
    companion object {
        const val defaultDegrees = 9.0
        val degreeRange = 0.5..10.0
        fun degrees(value: Double): Double = if (!value.isFinite()) defaultDegrees else (Math.rint(value * 2) / 2).coerceIn(degreeRange)
        /// The zoom that keeps a turned crop inside the picture at the largest angle.
        fun scale(maxDegrees: Double, width: Double, height: Double): Double {
            if (width <= 0 || height <= 0) return 1.0
            val angle = degrees(maxDegrees) * PI / 180
            return cos(angle) + maxOf(width / height, height / width) * sin(angle)
        }
    }
    /// Top-left normalized, output to input: (x, y) of the output reads input
    /// (a x + c y + tx, b x + d y + ty). The same map drives the shader and focus hit testing.
    fun sourceTransform(width: Double, height: Double): Affine {
        val a = cos(radians) / magnification; val s = sin(radians) / magnification
        val b = s * width / height; val c = -s * height / width
        return Affine(a, b, c, a, (1 - a - c) / 2, (1 - b - a) / 2)
    }
}

/// Gravity with a slowly agreed visual bias, eased and limited.
class HorizonEstimate {
    private var angle = 0.0
    private var lastTime: Double? = null
    private var lastGravityTime = Double.NEGATIVE_INFINITY
    private var lastGravity = 0.0
    private var visualBias = 0.0
    private var lastVisualTime = Double.NEGATIVE_INFINITY
    private var lastAgreement = Double.NEGATIVE_INFINITY
    private var agreedBias = 0.0
    private var candidate = 0.0; private var agreements = 0

    companion object {
        /// Roll from gravity in the iPhone's device axes and units (x right, y up,
        /// in g, pointing down). `rotation` is the picture's rotation as on iPhone:
        /// 90 for an upright portrait take.
        fun roll(x: Double, y: Double, rotation: Double, mirrored: Boolean): Double? {
            if (hypot(x, y) <= 0.24) return null   // Looking straight up or down has no reliable horizon.
            val raw = atan2(-x, -y) + (rotation - 90) * PI / 180
            // The interface or a take can be locked a quarter-turn away from the phone.
            // A small roll then tilts scene verticals and horizontals by the same amount.
            val residual = atan2(sin(4 * raw), cos(4 * raw)) / 4
            return if (mirrored) -residual else residual
        }
    }

    fun observe(line: Double?, gravity: Double, time: Double) {
        if (line == null || abs(line - gravity) > 3 * PI / 180) { agreements = 0; return }
        val difference = line - gravity
        if (time - lastVisualTime > 0.9 || abs(difference - candidate) > PI / 180) { agreements = 0; candidate = difference }
        agreements += 1
        candidate += 0.4 * (difference - candidate)
        lastVisualTime = time
        if (agreements >= 3) {
            agreedBias = (candidate * 180 / PI).coerceIn(-1.5, 1.5) * PI / 180
            lastAgreement = time
        }
    }

    fun correction(gravity: Double?, time: Double, maxDegrees: Double, stabilized: Boolean): Double {
        val dt = (time - (lastTime ?: (time - 1.0 / 30))).coerceIn(0.0, 0.1)
        lastTime = time
        if (gravity != null) { lastGravity = gravity; lastGravityTime = time }
        // Hold through brief occlusions, then fade the contribution of the lost horizon.
        val recent = time - lastAgreement < 0.9
        visualBias += ((if (recent) agreedBias else 0.0) - visualBias) * (1 - exp(-dt / (if (recent) 0.35 else 0.9)))
        val raw = if (time - lastGravityTime < 0.25) lastGravity + visualBias else 0.0
        val limit = HorizonFraming.degrees(maxDegrees) * PI / 180; val knee = limit * 0.85
        val magnitude = abs(raw)
        val bounded = if (magnitude <= knee) magnitude else knee + (limit - knee) * tanh((magnitude - knee) / (limit - knee))
        // When the phone is diagonal neither quarter-turn is a natural reference.
        // Taper to neutral so crossing 45° in a locked take cannot flip the frame.
        val ambiguity = ((abs(lastGravity) * 180 / PI - 35) / 10).coerceIn(0.0, 1.0)
        val target = (if (raw < 0) -bounded else bounded) * (1 - ambiguity * ambiguity * (3 - 2 * ambiguity))
        // Stabilization already suppresses fast shake; correct slower roll on its delivered frame.
        val tau = if (gravity == null) 0.35 else if (stabilized) 0.22 else 0.075
        angle = (angle + (target - angle) * (1 - exp(-dt / tau))).coerceIn(-limit, limit)
        return angle
    }
}

/// Looks for a long edge with consistent polarity across most of the image.
/// Short, moving or strongly sloped edges cannot vote the frame away from gravity.
object HorizonLine {
    private class Edge(val x: Int, val y: Int, val strength: Int)
    /// `luma` is width × height, row by row from the top.
    fun estimate(luma: ByteArray, width: Int, height: Int): Double? {
        if (width < 32 || height < 24 || luma.size != width * height) return null
        fun at(i: Int) = luma[i].toInt() and 0xff
        val edges = ArrayList<Edge>()
        var x = 2
        while (x < width - 2) {
            for (y in 2 until height - 2) {
                val i = y * width + x
                val v = at(i + width) - at(i - width)
                if (abs(v) < 14 || abs(at(i + 1) - at(i - 1)) >= abs(v)) continue
                edges.add(Edge(x, y, v.coerceIn(-64, 64)))
            }
            x += 2
        }
        if (edges.size < width / 3) return null
        val pad = width / 4 + 2; val rows = height + 2 * pad
        val scores = ArrayList<Double>()
        for (halfDegrees in -24..24) {
            val slope = tan(halfDegrees * PI / 360)
            val votes = IntArray(rows * 8)
            for (edge in edges) {
                val row = pad + edge.y - Math.round((edge.x - width / 2) * slope).toInt()
                votes[row * 8 + edge.x * 8 / width] += edge.strength
            }
            var best = 0.0
            for (row in pad / 2 until rows - pad / 2) {
                var sum = 0
                for (band in 0 until 8) sum += votes[row * 8 + band].coerceIn(-width * 2, width * 2)
                best = maxOf(best, abs(sum).toDouble())
            }
            scores.add(best)
        }
        val peak = scores.maxOrNull() ?: return null
        if (peak <= width * 11.0) return null
        val top = scores.indices.filter { scores[it] >= peak * 0.97 }
        if (top.last() - top.first() > 6) return null
        return ((top.first() + top.last()) / 2.0 - 24) * PI / 360
    }
}

/// Android's gravity sensor reports the reaction to gravity in m/s² (an
/// upright phone reads y = +9.81); the iPhone's motion gravity points down in
/// g (y = -1). This is the only place the two conventions meet.
object HorizonSensor {
    const val standardGravity = 9.80665
    fun deviceGravity(x: Double, y: Double): Pair<Double, Double> = -x / standardGravity to -y / standardGravity
}
