package yourec

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/// One colour of a title, after the iPhone app's TitleColorControl: a row with
/// its swatch that opens to the app's colours, recently used ones, a hex field
/// and, where the colour has its own opacity, an opacity slider.
/// Not ported yet: the system colour wheel and sampling a colour from the video frame.
object TitleColorControl {
    private const val RECENT_LIMIT = 8
    private const val PREFERENCES = "title"
    private const val RECENT_KEY = "recentColors.v1"
    private val HEX = Regex("^[0-9A-Fa-f]{6}$")

    /// `open` shows the choices; `toggle` asks the panel to show or hide them.
    fun views(context: Context, title: String, ink: TitleInk, open: Boolean, toggle: () -> Unit, editsOpacity: Boolean = true,
              change: (TitleInk) -> Unit): List<View> {
        fun select(chosen: TitleInk) {
            val next = if (editsOpacity) chosen else TitleInk(chosen.rgb, Math.round(ink.alpha * 255).toInt())
            remember(context, if (editsOpacity) next else TitleInk.of(next.rgb))
            change(next)
        }
        val header = TitleDesignControls.line(context, Design.label(context, title, 14f).apply { layoutParams = Design.weighted(1f) }, swatch(context, ink, 22f)).apply {
            minimumHeight = Design.dp(context, 44f); isClickable = true; setOnClickListener { toggle() }
            contentDescription = title + ", " + hex(ink) + if (open) ", expanded" else ", collapsed"
        }
        if (!open) return listOf(header)
        val palette = TextColor.values().map { TitleInk.of(it) }
        val seen = palette.toMutableSet()
        val swatches = palette + recents(context).map { if (editsOpacity) it else TitleInk.of(it.rgb) }.filter { seen.add(it) }
        val row = TitleDesignControls.choices(context, swatches.map { c ->
            swatch(context, c, 30f).apply {
                setPadding(0, 0, 0, 0); isClickable = true; setOnClickListener { select(c) }
                contentDescription = "Color " + hex(c) + (if (c.alpha < 1) ", " + Math.round(c.alpha * 100) + "% opacity" else "") + if (c == ink) ", selected" else ""
            }
        })
        val field = EditText(context).apply {
            setText(hex(ink)); setTextColor(Design.text); hint = "RRGGBB"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(7)); contentDescription = "$title hex"
        }
        val set = Design.button(context, "Set", size = 13f) {
            val clean = field.text.toString().trim().removePrefix("#")
            if (HEX.matches(clean)) select(TitleInk(clean.toLong(16), Math.round(ink.alpha * 255).toInt()))
            else field.error = "Six hex digits, as FFD60A"
        }.apply { contentDescription = "Set $title" }
        val hexRow = TitleDesignControls.line(context, Design.label(context, "Hex", 14f).apply { setPadding(0, 0, Design.dp(context, 8f), 0) }, field.apply { layoutParams = Design.weighted(1f) }, set)
        val views = mutableListOf<View>(header, row, hexRow)
        if (editsOpacity) views += TitleDesignControls.slider(context, "Opacity", "$title opacity", ink.alpha, 0.0..1.0, 0.01, null) { value ->
            change(TitleInk(ink.rgb, Math.round(value * 255).toInt()))
        }
        return views
    }

    fun hex(ink: TitleInk) = String.format("%06X", ink.rgb)

    private fun swatch(context: Context, ink: TitleInk, size: Float) = TextView(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink.argb); setStroke(Design.dp(context, 1f), 0xFF8E8E93.toInt()) }
        layoutParams = LinearLayout.LayoutParams(Design.dp(context, size), Design.dp(context, size)).apply { gravity = Gravity.CENTER_VERTICAL }
        minimumHeight = Design.dp(context, size); minWidth = Design.dp(context, size)
    }

    private fun recents(context: Context): List<TitleInk> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(RECENT_KEY, "")!!
            .split(',').mapNotNull { it.toLongOrNull(16)?.let(::TitleInk) }

    private fun remember(context: Context, ink: TitleInk) {
        val list = (listOf(ink) + recents(context).filter { it != ink }).take(RECENT_LIMIT)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(RECENT_KEY, list.joinToString(",") { String.format("%08X", it.rgba) }).apply()
    }
}

