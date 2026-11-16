package yourec

import kotlin.math.abs
import kotlin.math.ln

fun zoomTransitionTests() {
    // Different distances and directions must meet the same duration,
    // with no overshoot, including callbacks arriving after the deadline.
    for ((start, target) in listOf(1.0 to 8.0, 8.0 to 1.0, 0.5 to 1.0, 3.0 to 3.0)) {
        for (duration in listOf(0.5, 1.0, 5.0, 30.0)) {
            val ramp = ZoomTransition(start, target, 100.0, duration)
            require(ramp.value(99.0) == start && ramp.value(100.0) == start, "A ramp starts at the current zoom")
            require(ramp.value(100 + duration) == target && ramp.value(110 + duration) == target, "A ramp applies the exact target at its deadline")
            require(!ramp.isFinished(100 + duration * 0.9) && ramp.isFinished(100 + duration), "A ramp lasts its selected duration")
            var previous = start
            for (tick in 1..600) {
                val value = ramp.value(100 + duration * tick / 600)
                require(value >= minOf(start, target) - 1e-12 && value <= maxOf(start, target) + 1e-12, "Zoom never overshoots")
                require(if (target >= start) value >= previous - 1e-12 else value <= previous + 1e-12, "Zoom is monotonic")
                previous = value
            }
            if (target != start) {
                fun delta(a: Double, b: Double) = abs(ln(ramp.value(100 + b * duration) / ramp.value(100 + a * duration)))
                require(delta(0.9, 1.0) < delta(0.8, 0.9) && delta(0.8, 0.9) < delta(0.7, 0.8), "Zoom motion slows before stopping")
                require(delta(0.0, 0.1) < delta(0.1, 0.2) && delta(0.99, 1.0) < delta(0.49, 0.5) / 100, "Speed reaches zero at both ends")
            }
            val interruptedAt = 100 + duration * 0.37
            val applied = ramp.value(interruptedAt)
            val replacement = ZoomTransition(applied, start, interruptedAt, 1.0)
            require(replacement.value(interruptedAt) == applied && replacement.value(interruptedAt + 1) == start, "A new request starts from the actual current zoom")
        }
    }
    require(ZoomTransition(1.0, 3.0, 0.0, 0.0).value(0.0) == 3.0, "Instant applies the target at once")
    require(ZoomTransition.boundedDuration(-1.0) == 0.0 && ZoomTransition.boundedDuration(31.0) == 30.0 && ZoomTransition.boundedDuration(5.4) == 5.5, "Durations use half-second steps from 0 to 30")
    require(ZoomTransition.durationLabel(0.0) == "Instant" && ZoomTransition.durationLabel(1.0) == "1 second" && ZoomTransition.durationLabel(2.5) == "2.5 seconds", "Durations are labelled plainly")
}
