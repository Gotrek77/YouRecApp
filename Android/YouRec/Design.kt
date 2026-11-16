package yourec

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/// One visual language for Recorder, Projects and Editor: dark surfaces that
/// leave the footage as the focus, large targets, and colour used sparingly.
/// Views are built in code; there are no layout resources to keep in step.
object Design {
    const val background = 0xFF000000.toInt()
    const val surface = 0xFF1C1C1E.toInt()
    const val raised = 0xFF2C2C2E.toInt()
    const val separator = 0xFF3A3A3C.toInt()
    const val text = 0xFFFFFFFF.toInt()
    const val secondary = 0xFFAEAEB2.toInt()
    const val accent = 0xFFFFD60A.toInt()      // selection, playhead, active state
    const val recording = 0xFFFF453A.toInt()   // recording and destructive actions only
    const val warning = 0xFFFF9F0A.toInt()
    const val positive = 0xFF30D158.toInt()
    const val scrim = 0x99000000.toInt()       // behind controls placed over live footage
    const val touch = 48                       // minimum target, dp

    fun dp(context: Context, value: Float): Int = Math.round(value * context.resources.displayMetrics.density)
    fun dp(view: View, value: Float): Int = dp(view.context, value)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun label(context: Context, value: CharSequence = "", size: Float = 15f, color: Int = text, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        // Timers and levels must not shimmer sideways as their digits change.
        typeface = if (mono) Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL) else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    /// A text button with a full-size target. `fill` 0 leaves it borderless.
    fun button(context: Context, title: CharSequence, color: Int = text, fill: Int = raised, size: Float = 15f, action: (View) -> Unit) = label(context, title, size, color, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(context, touch.toFloat()); minWidth = dp(context, touch.toFloat())
        setPadding(dp(context, 14f), 0, dp(context, 14f), 0)
        val shape = rounded(fill, dp(context, 12f).toFloat())
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), if (fill == 0) null else shape, rounded(Color.WHITE, dp(context, 12f).toFloat()))
        isClickable = true; isFocusable = true
        contentDescription = title
        setOnClickListener { tap(it); action(it) }
    }

    /// A round symbol button for controls placed over footage.
    fun symbol(context: Context, glyph: CharSequence, description: String, diameter: Float = touch.toFloat(), fill: Int = scrim, action: (View) -> Unit) = label(context, glyph, 20f, text, bold = true).apply {
        gravity = Gravity.CENTER
        val side = dp(context, diameter)
        layoutParams = ViewGroup.LayoutParams(side, side)
        minWidth = side; minHeight = side
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(fill, side / 2f), rounded(Color.WHITE, side / 2f))
        isClickable = true; isFocusable = true
        contentDescription = description
        setOnClickListener { tap(it); action(it) }
    }

    fun row(context: Context, vararg children: View, spacing: Float = 8f, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
        children.forEachIndexed { i, child -> addView(child, wrap().apply { if (i > 0) leftMargin = dp(context, spacing) }) }
    }
    fun column(context: Context, vararg children: View, spacing: Float = 8f, gravity: Int = Gravity.START) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; this.gravity = gravity
        children.forEachIndexed { i, child -> addView(child, wrap().apply { if (i > 0) topMargin = dp(context, spacing) }) }
    }
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun weighted(weight: Float = 1f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(0, height, weight)
    fun frame(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.MATCH_PARENT, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(width, height, gravity)
    fun spacer(context: Context) = View(context)

    fun tap(view: View) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun tick(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    /// 0:07, 12:34 or 1:02:03.
    fun clock(seconds: Double): String {
        val total = Math.max(0.0, seconds).toLong()
        val h = total / 3600; val m = total / 60 % 60; val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
    /// Editor time with frames at 30 per second: 0:07.12
    fun timecode(seconds: Double): String {
        val frames = Math.max(0L, Math.round(seconds * 30))
        return String.format("%d:%02d.%02d", frames / 1800, frames / 30 % 60, frames % 30)
    }
}
