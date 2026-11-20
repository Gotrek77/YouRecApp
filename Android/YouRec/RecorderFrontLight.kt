package yourec

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.WindowManager

/// A lit border around the preview that lights a face from the screen, at full
/// screen brightness while it shows. It belongs to the recorder's interface:
/// its pixels never enter the capture pipeline.
class RecorderFrontLight(context: Context) : View(context) {
    enum class Width(val title: String, val fraction: Float) { Thin("Thin", 0.045f), Medium("Medium", 0.085f), Wide("Wide", 0.14f) }
    enum class Tone(val title: String) { White("White"), Warm("Warm"), Cool("Cool"), Custom("Custom") }

    private val preferences = context.getSharedPreferences("yourec.recorder", Context.MODE_PRIVATE)
    var border: Width = Width.values().firstOrNull { it.name == preferences.getString("frontLightWidth", null) } ?: Width.Medium
        set(value) { field = value; preferences.edit().putString("frontLightWidth", value.name).apply(); invalidate() }
    var tone: Tone = Tone.values().firstOrNull { it.name == preferences.getString("frontLightTone", null) } ?: Tone.White
        set(value) { field = value; preferences.edit().putString("frontLightTone", value.name).apply(); invalidate() }
    /// Hue of the custom tone, 0–360.
    var customHue: Float = preferences.getFloat("frontLightHue", 340f)
        set(value) { field = value; preferences.edit().putFloat("frontLightHue", value).apply(); invalidate() }
    var opacity: Float = preferences.getFloat("frontLightOpacity", 1f)
        set(value) { field = value.coerceIn(0f, 1f); preferences.edit().putFloat("frontLightOpacity", field).apply(); invalidate(); brighten() }
    var cornerRadius = 0f
    var on = false
        set(value) { field = value; visibility = if (value) VISIBLE else GONE; brighten() }
    private var shown = false

    val color: Int get() = when (tone) {
        Tone.White -> Color.WHITE
        Tone.Warm -> Color.rgb(255, 209, 158)
        Tone.Cool -> Color.rgb(184, 222, 255)
        Tone.Custom -> Color.HSVToColor(floatArrayOf(customHue, 0.3f, 1f))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init { visibility = GONE; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val thickness = minOf(width, height) * border.fraction
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val inner = maxOf(minOf(cornerRadius, 10f), cornerRadius - thickness)
        path.addRoundRect(RectF(thickness, thickness, width - thickness, height - thickness), inner, inner, Path.Direction.CW)
        paint.color = color; paint.alpha = Math.round(opacity * 255)
        canvas.drawPath(path, paint)
    }

    /// The recorder is on screen (true) or has gone (false).
    fun visible(value: Boolean) { shown = value; brighten() }
    // Full brightness only while the light shows, in the recorder window's lifetime.
    private fun brighten() {
        val window = (context as? Activity)?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = if (on && shown && opacity > 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }
}
