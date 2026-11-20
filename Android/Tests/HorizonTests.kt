package yourec

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/// Ported from the iPhone app's checkHorizon.
fun horizonTests() {
    val rad = PI / 180
    require(HorizonFraming.defaultDegrees == 9.0 && HorizonFraming.degrees(3.0) == 3.0, "Start at 9° and respect a chosen smaller angle")
    for ((w, h) in listOf(1920.0 to 1080.0, 1080.0 to 1920.0)) for (limit in listOf(0.5, 3.0, 10.0)) {
        val scale = HorizonFraming.scale(limit, w, h)
        for (roll in listOf(-limit, 0.0, limit)) {
            val map = HorizonFraming(roll * rad, scale).sourceTransform(w, h)
            for ((px, py) in listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 0.0, 1.0 to 1.0)) {
                val ix = map.x(px, py); val iy = map.y(px, py)
                require(ix >= -1e-8 && iy >= -1e-8 && ix <= 1.00000001 && iy <= 1.00000001, "The fixed crop must cover all four corners throughout the correction range")
            }
        }
    }
    for (rotation in listOf(0.0, 90.0, 180.0, 270.0)) {
        val a = 5 * rad - (rotation - 90) * rad
        for (mirrored in listOf(false, true)) {
            val roll = HorizonEstimate.roll(-sin(a), -cos(a), rotation, mirrored)
            require(abs((roll ?: 10.0) - (if (mirrored) -5 else 5) * rad) < 1e-7, "Gravity must follow the delivered camera axes")
            val locked = HorizonEstimate.roll(-sin(a + PI / 2), -cos(a + PI / 2), rotation, mirrored)
            require(abs((roll ?: 10.0) - (locked ?: 20.0)) < 1e-7, "A quarter-turned locked interface must correct the small tilt, even at 90°")
        }
    }
    require(HorizonEstimate.roll(0.02, 0.01, 90.0, false) == null, "Ignore unreliable roll looking straight up")
    val width = 192; val height = 108
    fun edge(angle: Double) = ByteArray(width * height) { i ->
        val line = height / 2.0 + (i % width - width / 2) * tan(angle * rad)
        (if (i / width >= line) 200 else 30).toByte()
    }
    for (degrees in listOf(-5.0, 0.0, 5.0)) {
        val measured = HorizonLine.estimate(edge(degrees), width, height)
        require(abs((measured ?: 100.0) / rad - degrees) <= 0.8, "Recognize the actual angle of a long edge ($degrees: $measured)")
    }
    require(HorizonLine.estimate(edge(26.0), width, height) == null, "Ignore strong perspective diagonals")
    val estimate = HorizonEstimate()
    for (i in 0 until 120) {
        val t = i / 30.0
        if (i % 9 == 0) estimate.observe(4 * rad, 3 * rad, t)
        estimate.correction(3 * rad, t, 10.0, false)
    }
    require(abs(estimate.correction(3 * rad, 4.0, 10.0, false) / rad - 4) < 0.1, "A persistent close visual horizon can refine gravity")
    estimate.observe(null, 3 * rad, 4.01)
    require(estimate.correction(3 * rad, 4.02, 10.0, false) > 3.9 * rad, "A passing object must not suddenly remove the visual correction")
    for (i in 1..100) estimate.correction(-20 * rad, 4 + i / 30.0, 3.0, true)
    val limited = estimate.correction(-20 * rad, 7.4, 3.0, true)
    require(limited >= -3 * rad && limited < -2.95 * rad, "Ease into the selected correction limit")
    val reduced = estimate.correction(-20 * rad, 7.41, 0.5, true)
    require(abs(reduced) <= 0.5 * rad, "Reducing the live angle limit must immediately fit inside the smaller crop")
    for (i in 1..100) estimate.correction(null, 7.4 + i / 30.0, 3.0, false)
    require(abs(estimate.correction(null, 11.0, 3.0, false)) < 0.01 * rad, "Ease back to neutral when gravity cannot define a horizon")
    // Android reports gravity as the reaction, in m/s²: the opposite sign of the iPhone's.
    val (x, y) = HorizonSensor.deviceGravity(0.0, 9.81)
    require(abs(x) < 1e-9 && abs(y + 1.0) < 1e-3 && HorizonEstimate.roll(x, y, 90.0, false)!! == 0.0, "An upright phone reads as level")
}
