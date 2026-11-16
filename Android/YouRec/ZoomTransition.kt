package yourec

import kotlin.math.ln
import kotlin.math.pow

/// Time-based easing in magnification space. Both velocity and acceleration
/// reach zero at either end, and late timer callbacks still meet the deadline.
class ZoomTransition(val start: Double, val target: Double, val began: Double, val duration: Double) {
    fun isFinished(time: Double) = duration <= 0 || time >= began + duration

    private fun progress(time: Double): Double {
        if (isFinished(time)) return 1.0
        if (time <= began) return 0.0
        val t = (time - began) / duration
        return t * t * t * (10 + t * (-15 + 6 * t))
    }
    fun value(time: Double): Double {
        if (isFinished(time)) return target
        if (time <= began) return start
        return 2.0.pow(log2(start) + log2(target / start) * progress(time))
    }

    companion object {
        private fun log2(value: Double) = ln(value) / ln(2.0)
        val durationPresets = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0)
        fun durationLabel(seconds: Double) = if (seconds == 0.0) "Instant" else "${number(seconds)} ${if (seconds == 1.0) "second" else "seconds"}"
        fun number(value: Double) = if (value == Math.rint(value)) value.toLong().toString() else value.toString()
        fun boundedDuration(value: Double) = if (value.isFinite()) (Math.floor(value * 2 + 0.5) / 2).coerceIn(0.0, 30.0) else 0.0
    }
}
