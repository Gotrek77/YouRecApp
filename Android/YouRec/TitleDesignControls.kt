package yourec

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable

/// The design part of the Text and Subtitles panels, after the iPhone app's
/// TitleInspector: one section at a time (Looks, Font, Fill, Border, Shadow,
/// Background). Any change makes the title a designed one; Plain returns it to
/// its Style and Colour. Sliders preview while dragging and save on release.
/// Not ported yet: saved channel styles, the system colour wheel, the eyedropper
/// and favourite fonts.
object TitleDesignControls {
    enum class Section(val title: String) { Looks("Looks"), Font("Font"), Fill("Fill"), Border("Border"), Shadow("Shadow"), Background("Background") }

    /// The colour control shown open, by its title; at most one at a time.
    private var openColor: String? = null
    /// The gradient a title had before Solid, restored by Gradient, as on iPhone.
    private var lastGradient = TitleGradient.gallery[0].second

    /// `plain` is a title without a design; `looks` offers the single-title looks (not shown for a card's lines).
    /// `preview` shows a slider's value while it is dragged; `change` saves it.
    fun views(context: Context, design: TitleDesign, plain: Boolean, section: Section, looks: Boolean, choose: (Section) -> Unit,
              fonts: List<ImportedFontFace> = emptyList(), missingFont: String? = null, importFont: (() -> Unit)? = null,
              preview: ((TitleDesign) -> Unit)? = null, change: (TitleDesign?, String) -> Unit): List<View> {
        fun designed(next: TitleDesign, message: String) = change(next, message)
        fun option(label: String, description: String, on: Boolean, action: () -> Unit) =
            Design.button(context, label, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { action() }
                .apply { contentDescription = description + if (on) ", selected" else "" }
        val shown = Section.values().filter { looks || it != Section.Looks }
        val section = if (section in shown) section else Section.Font
        val tabs = choices(context, shown.map { s -> option(s.title, "Title " + s.title, s == section) { openColor = null; choose(s) } })
        fun slider(name: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, message: String, degrees: Boolean = false, apply: (Double) -> TitleDesign) =
            slider(context, name, name, value, range, step, preview?.let { show -> { v: Double -> show(apply(v)) } }, degrees) { designed(apply(it), message) }
        fun color(title: String, ink: TitleInk, message: String, editsOpacity: Boolean = true, apply: (TitleInk) -> TitleDesign) =
            TitleColorControl.views(context, title, ink, openColor == title, { openColor = if (openColor == title) null else title; choose(section) }, editsOpacity) {
                designed(apply(it), message)
            }
        val body: List<View> = when (section) {
            Section.Looks -> listOf(choices(context, listOf(option("Plain", "Look Plain", plain) { change(null, "Text look") }) +
                TitleDesign.looks.map { look ->
                    val chosen = design.applyingLook(look.design)
                    option(look.name, "Look " + look.name, !plain && design == chosen) { designed(chosen, "Text look") }
                }))
            Section.Font -> {
                val family = TitleTypeface.of(design.face)
                val face = fonts.firstOrNull { it.id == design.face }
                listOfNotNull(
                    choices(context, TitleTypeface.families.map { f ->
                        option(f.name, "Font " + f.name, f.id == design.face) {
                            // A family without the current weight takes its nearest one.
                            val weight = f.weights.minByOrNull { Math.abs(it - design.weight) } ?: design.weight
                            designed(design.copy(face = f.id, weight = weight, italic = design.italic && f.slanted != null), "Text font")
                        }
                    } + fonts.map { f ->
                        // An imported face starts at its own default weight; italic is never synthesized.
                        option(f.name, "Font " + f.name, f.id == design.face) {
                            if (f.id != design.face) designed(design.copy(face = f.id, weight = f.defaultWeight, italic = false), "Text font")
                        }
                    } + listOfNotNull(missingFont?.let { option("$it (missing)", "Font $it, missing", true) { } },
                        importFont?.let { action -> Design.button(context, "Import font…", Design.text, Design.raised, 13f) { action() }.apply { contentDescription = "Import font" } })),
                    missingFont?.let { Design.label(context, "Using a fallback for $it. Import the original font to restore it.", 12f) },
                    (family?.weights ?: face?.weights)?.takeIf { it.size > 1 }?.let { weights -> choices(context, weights.map { w ->
                        option(weightName(w), "Weight " + weightName(w), w == design.weight) { designed(design.copy(weight = w), "Text weight") }
                    } + listOfNotNull(family?.slanted?.let { option("Italic", "Italic", design.italic) { designed(design.copy(italic = !design.italic), "Text italic") } })) },
                    choices(context, TitleAlignment.values().map { a ->
                        val name = a.raw.replaceFirstChar { it.uppercase() }
                        option(name, "Align " + name, a == design.alignment) { designed(design.copy(alignment = a), "Text alignment") }
                    }),
                    slider("Letter spacing", design.tracking, -0.05..0.25, 0.01, "Text spacing") { design.copy(tracking = it) },
                    slider("Line spacing", design.lineSpacing, 0.0..0.8, 0.01, "Text spacing") { design.copy(lineSpacing = it) })
            }
            Section.Fill -> {
                val gradient = design.gradient
                listOf(choices(context, listOf(
                    option("Solid", "Fill Solid", gradient == null) { if (gradient != null) { lastGradient = gradient; designed(design.copy(gradient = null), "Text fill") } },
                    option("Gradient", "Fill Gradient", gradient != null) { if (gradient == null) designed(design.copy(gradient = lastGradient), "Text fill") }))) +
                if (gradient == null) color("Text color", design.fill, "Text colour") { design.copy(fill = it) }
                else listOf(choices(context, TitleGradient.gallery.map { (name, value) ->
                    option(name, "Gradient " + name, gradient == value) { lastGradient = value; designed(design.copy(gradient = value), "Text gradient") }.apply {
                        if (gradient != value) background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(value.first.argb, value.last.argb)).apply { cornerRadius = Design.dp(context, 10f).toFloat() }
                        if (gradient != value) setTextColor(Design.background)
                    }
                })) + color("First color", gradient.first, "Text gradient") { design.copy(gradient = gradient.copy(first = it)) } +
                    color("Second color", gradient.last, "Text gradient") { design.copy(gradient = gradient.copy(last = it)) } +
                    slider("Angle", gradient.angle, 0.0..360.0, 1.0, "Text gradient", degrees = true) { design.copy(gradient = gradient.copy(angle = it)) }
            }
            Section.Border -> listOf(choices(context, listOf(option("Off", "Text border off", !design.outlined) { designed(design.copy(outlined = false), "Text border") }) +
                listOf(0.025 to "Thin", 0.045 to "Medium", 0.09 to "Bold").map { (width, name) ->
                    option(name, "Text border " + name, design.outlined && Math.abs(design.borderWidth - width) < 1e-6) { designed(design.copy(outlined = true, borderWidth = width), "Text border") }
                })) + if (!design.outlined) emptyList() else
                    listOf(slider("Border width", design.borderWidth, 0.01..0.20, 0.005, "Text border") { design.copy(borderWidth = it) }) +
                    color("Border color", design.border, "Text border") { design.copy(border = it) }
            Section.Shadow -> listOf(choices(context, listOf(
                option("Off", "Drop shadow off", !design.shadow) { designed(design.copy(shadow = false), "Text shadow") },
                option("On", "Drop shadow on", design.shadow) { designed(design.copy(shadow = true), "Text shadow") }))) +
                if (!design.shadow) emptyList() else color("Shadow color", design.shade, "Text shadow") { design.copy(shade = it) } +
                    slider("Blur", design.shadowBlur, 0.0..0.6, 0.01, "Text shadow") { design.copy(shadowBlur = it) } +
                    slider("Offset", design.shadowOffset, -0.5..0.5, 0.01, "Text shadow") { design.copy(shadowOffset = it) }
            Section.Background -> listOf(choices(context, listOf(TitlePlate.Off to "None", TitlePlate.Block to "One box", TitlePlate.Lines to "Each line").map { (plate, name) ->
                option(name, "Background " + name, design.plate == plate) { designed(design.copy(plate = plate), "Text background") }
            })) + if (design.plate == TitlePlate.Off) emptyList() else
                color("Box color", design.backing, "Text background", editsOpacity = false) { design.copy(backing = it) } +
                slider("Background opacity", design.backing.alpha, 0.0..1.0, 0.01, "Text background") { design.copy(backing = TitleInk(design.backing.rgb, Math.round(it * 255).toInt())) } +
                slider("Padding", design.padding, 0.0..0.8, 0.01, "Text background") { design.copy(padding = it) } +
                slider("Corner radius", design.radius, 0.0..0.8, 0.01, "Text background") { design.copy(radius = it) }
        }
        return listOf(tabs) + body
    }

    /// A named slider showing its value as a percentage (or in degrees). `preview` follows the drag; `commit` is the release.
    fun slider(context: Context, name: String, description: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double,
               preview: ((Double) -> Unit)?, degrees: Boolean = false, commit: (Double) -> Unit): View {
        val steps = Math.round((range.endInclusive - range.start) / step).toInt()
        fun at(progress: Int) = (range.start + progress * step).coerceIn(range.start, range.endInclusive)
        fun shown(v: Double) = if (degrees) "${Math.round(v)}°" else "${Math.round(v * 100)}%"
        val label = Design.label(context, shown(value), 13f, Design.secondary, mono = true)
        val bar = SeekBar(context).apply {
            max = steps; progress = Math.round((value - range.start) / step).toInt().coerceIn(0, steps); contentDescription = description
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) { if (user) { label.text = shown(at(p)); preview?.invoke(at(p)) } }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { commit(at(b.progress)) }
            })
        }
        return Design.row(context, Design.label(context, name, 13f).apply { layoutParams = LinearLayout.LayoutParams(Design.dp(context, 104f), ViewGroup.LayoutParams.WRAP_CONTENT) },
            bar.apply { layoutParams = Design.weighted(1f, Design.dp(context, 44f)) }, label.apply { layoutParams = LinearLayout.LayoutParams(Design.dp(context, 48f), ViewGroup.LayoutParams.WRAP_CONTENT) })
    }

    /// Entrance or exit animation: the move, its direction and its length. `exitSide` picks which one is shown.
    fun motionViews(context: Context, motion: TitleMotion, exitSide: Boolean, chooseSide: (Boolean) -> Unit, change: (TitleMotion?, String) -> Unit): List<View> {
        fun option(label: String, description: String, on: Boolean, action: () -> Unit) =
            Design.button(context, label, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { action() }
                .apply { contentDescription = description + if (on) ", selected" else "" }
        fun moved(next: TitleMotion, message: String) = change(next.takeIf { it != TitleMotion() }, message)
        val move = if (exitSide) motion.exit else motion.entrance
        val views = arrayListOf<View>(choices(context, listOf(
            option("Entrance", "Animation Entrance", !exitSide) { chooseSide(false) },
            option("Exit", "Animation Exit", exitSide) { chooseSide(true) }) +
            TitleMove.values().map { m ->
                val name = m.raw.replaceFirstChar { it.uppercase() }
                option(name, (if (exitSide) "Exit " else "Entrance ") + name, m == move) {
                    moved(if (exitSide) motion.copy(exit = m) else motion.copy(entrance = m), "Text animation")
                }
            }))
        if (move == TitleMove.Drift || move == TitleMove.Wipe) {
            val direction = (if (exitSide) motion.outDirection else motion.inDirection) ?: if (move == TitleMove.Drift) TitleDirection.Up else TitleDirection.Right
            views += choices(context, TitleDirection.values().map { d ->
                val name = d.raw.replaceFirstChar { it.uppercase() }
                option(name, "Direction " + name, d == direction) { moved(if (exitSide) motion.copy(outDirection = d) else motion.copy(inDirection = d), "Text animation") }
            })
        }
        if (move != TitleMove.None) {
            val seconds = if (exitSide) motion.outSeconds else motion.inSeconds
            views += choices(context, listOf(0.2, 0.35, 0.7, 1.0, 2.0).map { value ->
                val name = ZoomTransition.number(value) + " s"
                option(name, "Animation lasts " + name, Math.abs(seconds - value) < 0.001) {
                    moved(if (exitSide) motion.copy(outSeconds = value) else motion.copy(inSeconds = value), "Text animation")
                }
            })
        }
        return views
    }

    private fun weightName(weight: Int) = when (weight) { 400 -> "Regular"; 500 -> "Medium"; 600 -> "Semibold"; 700 -> "Bold"; 800 -> "Heavy"; else -> weight.toString() }

    fun choices(context: Context, items: List<View>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            for (item in items) addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Design.dp(context, 6f) })
        })
    }
}
