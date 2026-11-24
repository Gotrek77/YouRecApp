package yourec

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/// The design part of the Text panel, after the iPhone app's TitleInspector:
/// one section at a time (Looks, Font, Border, Shadow, Background). Any change
/// makes the title a designed one; Plain returns it to its Style and Colour.
/// Not ported yet: saved channel styles, gradient and colour pickers, the
/// eyedropper, sliders for blur, offset, padding and radius, and imported fonts.
object TitleDesignControls {
    enum class Section(val title: String) { Looks("Looks"), Font("Font"), Border("Border"), Shadow("Shadow"), Background("Background") }

    fun views(context: Context, title: TextOverlay, section: Section, choose: (Section) -> Unit, change: (TextOverlay, String) -> Unit): List<View> {
        val design = title.design
        fun designed(next: TitleDesign, message: String) = change(title.copy(appearance = next), message)
        fun option(label: String, description: String, on: Boolean, action: () -> Unit) =
            Design.button(context, label, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { action() }
                .apply { contentDescription = description + if (on) ", selected" else "" }
        val tabs = choices(context, Section.values().map { s -> option(s.title, "Title " + s.title, s == section) { choose(s) } })
        val body: List<View> = when (section) {
            Section.Looks -> listOf(choices(context, listOf(option("Plain", "Look Plain", title.appearance == null) { change(title.copy(appearance = null), "Text look") }) +
                TitleDesign.looks.map { look ->
                    val chosen = design.applyingLook(look.design)
                    option(look.name, "Look " + look.name, title.appearance == chosen) { designed(chosen, "Text look") }
                }))
            Section.Font -> {
                val family = TitleTypeface.of(design.face)
                listOfNotNull(
                    choices(context, TitleTypeface.families.map { f ->
                        option(f.name, "Font " + f.name, f.id == design.face) {
                            // A family without the current weight takes its nearest one.
                            val weight = f.weights.minByOrNull { Math.abs(it - design.weight) } ?: design.weight
                            designed(design.copy(face = f.id, weight = weight, italic = design.italic && f.slanted != null), "Text font")
                        }
                    }),
                    family?.weights?.takeIf { it.size > 1 }?.let { weights -> choices(context, weights.map { w ->
                        option(weightName(w), "Weight " + weightName(w), w == design.weight) { designed(design.copy(weight = w), "Text weight") }
                    } + listOfNotNull(family.slanted?.let { option("Italic", "Italic", design.italic) { designed(design.copy(italic = !design.italic), "Text italic") } })) },
                    choices(context, TitleAlignment.values().map { a ->
                        val name = a.raw.replaceFirstChar { it.uppercase() }
                        option(name, "Align " + name, a == design.alignment) { designed(design.copy(alignment = a), "Text alignment") }
                    }))
            }
            Section.Border -> listOf(choices(context, listOf(option("Off", "Text border off", !design.outlined) { designed(design.copy(outlined = false), "Text border") }) +
                listOf(0.025 to "Thin", 0.045 to "Medium", 0.09 to "Bold").map { (width, name) ->
                    option(name, "Text border " + name, design.outlined && Math.abs(design.borderWidth - width) < 1e-6) { designed(design.copy(outlined = true, borderWidth = width), "Text border") }
                }))
            Section.Shadow -> listOf(choices(context, listOf(
                option("Off", "Drop shadow off", !design.shadow) { designed(design.copy(shadow = false), "Text shadow") },
                option("On", "Drop shadow on", design.shadow) { designed(design.copy(shadow = true), "Text shadow") })))
            Section.Background -> listOf(choices(context, listOf(TitlePlate.Off to "None", TitlePlate.Block to "One box", TitlePlate.Lines to "Each line").map { (plate, name) ->
                option(name, "Background " + name, design.plate == plate) { designed(design.copy(plate = plate), "Text background") }
            }))
        }
        return listOf(tabs) + body
    }

    private fun weightName(weight: Int) = when (weight) { 400 -> "Regular"; 500 -> "Medium"; 600 -> "Semibold"; 700 -> "Bold"; 800 -> "Heavy"; else -> weight.toString() }

    private fun choices(context: Context, items: List<View>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            for (item in items) addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Design.dp(context, 6f) })
        })
    }
}
