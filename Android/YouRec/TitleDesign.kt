package yourec

/// Title designs, ported from the iPhone app's TitleDesign.swift. The model and
/// its looks are platform-independent; StyledTextRenderer.kt draws them.

/// Packed sRGB, in RRGGBBAA order. Stored values are independent of the display's colour scheme.
data class TitleInk(val rgba: Long) {
    constructor(rgb: Long, alpha: Int) : this(((rgb and 0xFFFFFF) shl 8) or (alpha.toLong() and 255))
    val rgb: Long get() = rgba ushr 8
    val alpha: Double get() = (rgba and 255) / 255.0
    /// Android's packed colour, AARRGGBB.
    val argb: Int get() = (((rgba and 255) shl 24) or (rgba ushr 8)).toInt()
    companion object {
        fun of(rgb: Long) = TitleInk(rgb, 255)
        fun of(color: TextColor) = of((Math.round(color.red * 255) shl 16) or (Math.round(color.green * 255) shl 8) or Math.round(color.blue * 255))
        val white = of(0xFFFFFF); val black = of(0x000000)
    }
}

data class TitleGradient(val first: TitleInk, val last: TitleInk, /** Screen degrees: 0 left to right, 90 top to bottom. */ val angle: Double) {
    constructor(first: Long, last: Long, angle: Double) : this(TitleInk.of(first), TitleInk.of(last), angle)
    /// The gradient's start and end in a box of `width` by `height`, top-left origin.
    fun axis(width: Double, height: Double): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val a = angle * Math.PI / 180
        val x = Math.cos(a); val y = Math.sin(a)
        val half = (Math.abs(x) * width + Math.abs(y) * height) / 2
        return (width / 2 - x * half to height / 2 - y * half) to (width / 2 + x * half to height / 2 + y * half)
    }
    companion object {
        val gallery: List<Pair<String, TitleGradient>> = listOf(
            "Sunset" to TitleGradient(0xfff0ba, 0xf58283, 105.0), "Golden hour" to TitleGradient(0xfff5d6, 0xe4a044, 85.0),
            "Ember" to TitleGradient(0xffcf74, 0xc84939, 30.0), "Rose" to TitleGradient(0xffdae2, 0xba5486, 120.0),
            "Lavender" to TitleGradient(0xeee0ff, 0x9a87dd, 95.0), "Midnight" to TitleGradient(0x91aff1, 0x45417b, 45.0),
            "Glacier" to TitleGradient(0xf6ffff, 0x50afcd, 100.0), "Lagoon" to TitleGradient(0x72e7df, 0x386ab1, 20.0),
            "Sea glass" to TitleGradient(0xe4fadb, 0x69b7a7, 130.0), "Olive" to TitleGradient(0xf4e8aa, 0x829969, 70.0),
            "Silver" to TitleGradient(0xffffff, 0x9ca9b8, 90.0), "Copper" to TitleGradient(0xf0c6a5, 0xa36556, 145.0))
    }
}

enum class TitleAlignment(val raw: String) { Left("left"), Center("center"), Right("right") }
enum class TitlePlate(val raw: String) { Off("off"), Block("block"), Lines("lines") }

data class TitleDesign(
    val face: String = "system", val weight: Int = 700, val italic: Boolean = false,
    val alignment: TitleAlignment = TitleAlignment.Center, val tracking: Double = 0.0, val lineSpacing: Double = 0.0,
    val fill: TitleInk = TitleInk.white, val gradient: TitleGradient? = null,
    val outlined: Boolean = false, val border: TitleInk = TitleInk.black,
    /** Thickness in em, outside the glyph. */ val borderWidth: Double = 0.045,
    val shadow: Boolean = true, val shade: TitleInk = TitleInk(0x000000, 210), val shadowBlur: Double = 0.12, val shadowOffset: Double = 0.05,
    val plate: TitlePlate = TitlePlate.Off, val backing: TitleInk = TitleInk(0x111218, 205), val padding: Double = 0.28, val radius: Double = 0.14) {

    val valid: Boolean get() = face.isNotEmpty() && face.toByteArray().size <= 255 && weight in 100..1000 && tracking in -0.05..0.25
        && lineSpacing in 0.0..0.8 && borderWidth in 0.0..0.20 && shadowBlur in 0.0..0.6
        && shadowOffset in -0.5..0.5 && padding in 0.0..0.8 && radius in 0.0..0.8 && (gradient?.angle ?: 0.0) in 0.0..360.0

    /// Outline is a separate tool, including when switching between looks.
    fun applyingLook(look: TitleDesign) = look.copy(outlined = outlined, border = border, borderWidth = borderWidth)

    enum class LookCollection(val title: String) { Editorial("Editorial"), Cinema("Cinema"), Print("Print"), Display("Display") }
    data class Look(val collection: LookCollection, val name: String, val design: TitleDesign)

    companion object {
        fun legacy(style: TextStyle, color: TextColor): TitleDesign {
            val plated = style == TextStyle.Box || style == TextStyle.Highlight
            return TitleDesign(weight = if (style == TextStyle.Clean) 400 else 700,
                fill = if (style == TextStyle.Highlight) TitleInk.black else TitleInk.of(color),
                shadow = !plated, plate = if (plated) TitlePlate.Block else TitlePlate.Off,
                backing = if (style == TextStyle.Highlight) TitleInk.of(color) else if (plated) TitleInk(0x050505, 209) else TitleInk(0x111218, 205))
        }
        private fun ink(rgb: Long, alpha: Int = 255) = TitleInk(rgb, alpha)
        private val E = LookCollection.Editorial; private val C = LookCollection.Cinema; private val P = LookCollection.Print; private val D = LookCollection.Display
        private val L = TitleAlignment.Left
        val looks: List<Look> = listOf(
            Look(E, "Clean", TitleDesign(face = "atkinsonhyperlegiblenext", weight = 600, lineSpacing = 0.04, shade = ink(0x090B0D, 240), shadowBlur = 0.085, shadowOffset = 0.035)),
            Look(E, "Field report", TitleDesign(face = "barlowcondensed", weight = 700, alignment = L, tracking = 0.02, lineSpacing = 0.05,
                fill = ink(0xF8F8F4), shadow = false, plate = TitlePlate.Lines, backing = ink(0x102335, 224), padding = 0.15, radius = 0.0)),
            Look(E, "Dateline", TitleDesign(face = "ibmplexmono", weight = 500, alignment = L, tracking = 0.025, lineSpacing = 0.06,
                fill = ink(0xFFD79B), shade = ink(0x21170F, 250), shadowBlur = 0.055, shadowOffset = 0.055)),
            Look(E, "Caption box", TitleDesign(face = "atkinsonhyperlegiblenext", weight = 500, lineSpacing = 0.09,
                shadow = false, plate = TitlePlate.Block, backing = ink(0x111319, 168), padding = 0.24, radius = 0.24)),
            Look(E, "Pull quote", TitleDesign(face = "sourceserif4", weight = 600, alignment = L, tracking = -0.012, lineSpacing = 0.12,
                fill = ink(0x27231F), shadow = false, plate = TitlePlate.Block, backing = ink(0xF4EFDF), padding = 0.31, radius = 0.025)),
            Look(E, "Interview", TitleDesign(face = "system", weight = 600, alignment = L, tracking = -0.01, lineSpacing = 0.06,
                fill = ink(0x121D28), shadow = false, plate = TitlePlate.Block, backing = ink(0xEFF6FA, 166), padding = 0.26, radius = 0.14)),

            Look(C, "Film title", TitleDesign(face = "sourceserif4", weight = 500, tracking = 0.035, lineSpacing = 0.10,
                fill = ink(0xF9EFDC), shade = ink(0x181519, 245), shadowBlur = 0.10, shadowOffset = 0.045)),
            Look(C, "Opening credit", TitleDesign(face = "barlowcondensed", weight = 400, tracking = 0.075, lineSpacing = 0.16,
                fill = ink(0xF4F6FA), shade = ink(0x101720, 245), shadowBlur = 0.07, shadowOffset = 0.04)),
            Look(C, "Intertitle", TitleDesign(face = "bodonimoda", weight = 600, tracking = 0.035, lineSpacing = 0.12,
                fill = ink(0xF3E6CE), shadow = false, plate = TitlePlate.Block, backing = ink(0x19191A, 214), padding = 0.40, radius = 0.035)),
            Look(C, "Daylight", TitleDesign(face = "bodonimoda", weight = 700, italic = true, tracking = -0.012, lineSpacing = 0.08,
                fill = ink(0x28212A), shade = ink(0xFFF9ED, 229), shadowBlur = 0.12, shadowOffset = 0.0)),
            Look(C, "Night drive", TitleDesign(face = "unbounded", weight = 500, tracking = 0.065, lineSpacing = 0.12,
                fill = ink(0xDEEDFF), gradient = TitleGradient(0xF4F8FC, 0xA9CDFA, 90.0), shade = ink(0x192D50), shadowBlur = 0.07, shadowOffset = 0.035,
                plate = TitlePlate.Block, backing = ink(0x112238, 102), padding = 0.22, radius = 0.075)),
            Look(C, "Matinée", TitleDesign(face = "barlowcondensed", weight = 700, italic = true, tracking = 0.02,
                fill = ink(0xFFDAA8), shadow = false, plate = TitlePlate.Lines, backing = ink(0x531E27, 190), padding = 0.16, radius = 0.035)),

            Look(P, "Headline", TitleDesign(face = "archivoblack", weight = 400, alignment = L, tracking = -0.035,
                fill = ink(0xFFFCF2), shade = ink(0x151319), shadowBlur = 0.018, shadowOffset = 0.065)),
            Look(P, "Magazine italic", TitleDesign(face = "fraunces", weight = 600, italic = true, tracking = -0.018, lineSpacing = 0.04,
                fill = ink(0xFFE7DC), shade = ink(0x352027, 245), shadowBlur = 0.09, shadowOffset = 0.04)),
            Look(P, "Source note", TitleDesign(face = "ibmplexmono", weight = 400, alignment = L, tracking = 0.015, lineSpacing = 0.12,
                fill = ink(0x302822), shadow = false, plate = TitlePlate.Lines, backing = ink(0xF4E4C7, 181), padding = 0.16, radius = 0.0)),
            Look(P, "Masthead", TitleDesign(face = "bodonimoda", weight = 800, alignment = L, tracking = -0.04,
                fill = ink(0x471628), shadow = false, plate = TitlePlate.Block, backing = ink(0xF7E9E9, 232), padding = 0.12, radius = 0.0)),
            Look(P, "Clothbound", TitleDesign(face = "sourceserif4", weight = 500, tracking = 0.045, lineSpacing = 0.10,
                fill = ink(0xF1E2B2), shadow = false, plate = TitlePlate.Block, backing = ink(0x183F34, 200), padding = 0.38, radius = 0.065)),
            Look(P, "Field note", TitleDesign(face = "caveat", weight = 700, alignment = L, lineSpacing = 0.06,
                fill = ink(0xFFE379), shade = ink(0x292018), shadowBlur = 0.095, shadowOffset = 0.045)),

            Look(D, "Gaffer tape", TitleDesign(face = "caveat", weight = 700, alignment = L, tracking = 0.025, lineSpacing = 0.06,
                fill = ink(0x1A2025), shadow = false, plate = TitlePlate.Lines, backing = ink(0xF3F1E6, 186), padding = 0.14, radius = 0.0)),
            Look(D, "Highlighter", TitleDesign(face = "atkinsonhyperlegiblenext", weight = 800, alignment = L, tracking = -0.025, lineSpacing = 0.04,
                fill = ink(0x142214), shadow = false, plate = TitlePlate.Lines, backing = ink(0xD9FA68), padding = 0.13, radius = 0.045)),
            Look(D, "Afterglow", TitleDesign(face = "fraunces", weight = 800, tracking = -0.025, lineSpacing = 0.03,
                fill = ink(0xFFF0AD), gradient = TitleGradient(0xFFF0AD, 0xFFA68E, 105.0), shade = ink(0x462335, 245), shadowBlur = 0.08, shadowOffset = 0.055)),
            Look(D, "Wide type", TitleDesign(face = "unbounded", weight = 800, alignment = L, tracking = -0.03,
                fill = ink(0xDDF4C9), shade = ink(0x17362B), shadowBlur = 0.02, shadowOffset = 0.075)),
            Look(D, "Flyer", TitleDesign(face = "archivoblack", weight = 400, tracking = -0.025, lineSpacing = 0.04,
                fill = ink(0xFFFFF5), shadow = false, plate = TitlePlate.Block, backing = ink(0xAC2633, 226), padding = 0.22, radius = 0.0)),
            Look(D, "Lilac gel", TitleDesign(face = "unbounded", weight = 600, tracking = -0.025, lineSpacing = 0.05,
                fill = ink(0x261A40), shadow = false, plate = TitlePlate.Block, backing = ink(0xE8D9FF, 170), padding = 0.30, radius = 0.80)))
    }
}

/// The typefaces bundled with the app (ThirdParty/TitleFonts), as on iPhone.
/// `upright` and `slanted` are the iPhone's PostScript names, kept for reference.
data class TitleTypeface(val id: String, val name: String, val upright: String, val slanted: String? = null,
                         val weights: List<Int> = listOf(400, 500, 600, 700, 800)) {
    companion object {
        val families: List<TitleTypeface> = listOf(
            TitleTypeface("system", "System", "", ""),
            TitleTypeface("atkinsonhyperlegiblenext", "Atkinson Hyperlegible Next", "AtkinsonHyperlegibleNext-Regular", "AtkinsonHyperlegibleNext-Italic"),
            TitleTypeface("barlowcondensed", "Barlow Condensed", "BarlowCondensed-Regular", "BarlowCondensed-Italic", listOf(400, 700)),
            TitleTypeface("archivoblack", "Archivo Black", "ArchivoBlack-Regular", weights = listOf(400)),
            TitleTypeface("sourceserif4", "Source Serif 4", "SourceSerif4Roman-Regular", "SourceSerif4Italic-Italic"),
            TitleTypeface("fraunces", "Fraunces", "Fraunces-9ptBlack", "Fraunces-9ptBlackItalic"),
            TitleTypeface("ibmplexmono", "IBM Plex Mono", "IBMPlexMono-Regular", "IBMPlexMono-Italic", listOf(400, 500, 700)),
            TitleTypeface("bodonimoda", "Bodoni Moda", "BodoniModa-Regular", "BodoniModa-Italic"),
            TitleTypeface("unbounded", "Unbounded", "Unbounded-Regular"),
            TitleTypeface("caveat", "Caveat", "Caveat-Regular", weights = listOf(400, 500, 600, 700)))
        fun of(id: String) = families.firstOrNull { it.id == id }
        /// Barlow Condensed and IBM Plex Mono are static files chosen by weight, as on iPhone.
        fun staticStyle(family: String, weight: Int, italic: Boolean): String {
            val suffix = if (weight >= 600) "Bold" else if (family == "ibmplexmono" && weight >= 500) "Medium" else ""
            return suffix + if (italic) "Italic" else if (suffix.isEmpty()) "Regular" else ""
        }
    }
}

/// A card behind a label and a headline. Dimensions are relative to the base
/// headline size, so the whole design scales together.
data class TitleCard(val fill: TitleInk = TitleInk(0x08090B, 139), val border: TitleInk = TitleInk(0xFFFFFF, 72),
                     val borderWidth: Double = 0.017, val padding: Double = 0.42, val radius: Double = 0.29, val spacing: Double = 0.08,
                     val labelOffset: Offset? = null, val headlineOffset: Offset? = null) {
    /// Each axis is a fraction of the travel from the preset position to that edge.
    data class Offset(val x: Double = 0.0, val y: Double = 0.0) { val valid: Boolean get() = x in -1.0..1.0 && y in -1.0..1.0 }
    val valid: Boolean get() = borderWidth in 0.0..0.1 && padding in 0.0..1.0 && radius in 0.0..1.0 && spacing in 0.0..1.0
        && labelOffset?.valid != false && headlineOffset?.valid != false
}

/// Two lines on one card, after the iPhone's LabelHeadline. Both share the
/// overlay's anchor, size and timing; the headline's words are the overlay's text.
data class LabelHeadline(val label: String = "First text",
                         val labelStyle: TitleDesign = TitleDesign(face = "system", weight = 700, alignment = TitleAlignment.Left, tracking = 0.09,
                             fill = TitleInk.black, shadow = false, plate = TitlePlate.Block, backing = TitleInk.white, padding = 0.32, radius = 0.27),
                         val headlineStyle: TitleDesign = TitleDesign(face = "archivoblack", weight = 400, alignment = TitleAlignment.Left, tracking = -0.045, shadow = false),
                         val labelScale: Double = 0.36, val headlineScale: Double = 1.0, val card: TitleCard = TitleCard()) {
    enum class Part(val title: String) { Label("Label"), Headline("Headline"), Card("Card") }
    val valid: Boolean get() = label.length <= 500 && labelStyle.valid && headlineStyle.valid && card.valid
        && labelScale in 0.2..1.0 && headlineScale in 0.5..1.8

    /// A reusable look holds appearance only: words and manual placement belong to the title.
    data class Look(val label: TitleDesign, val headline: TitleDesign, val labelScale: Double, val headlineScale: Double, val card: TitleCard)
    val look: Look get() = Look(TitleDesign().applyingLook(labelStyle), TitleDesign().applyingLook(headlineStyle), labelScale, headlineScale,
        card.copy(labelOffset = null, headlineOffset = null))
    fun applyingLook(look: Look) = copy(labelStyle = labelStyle.applyingLook(look.label), headlineStyle = headlineStyle.applyingLook(look.headline),
        labelScale = look.labelScale, headlineScale = look.headlineScale, card = look.card.copy(labelOffset = card.labelOffset, headlineOffset = card.headlineOffset))

    /// Where the two lines go on the card, from each line's drawn size (its own insets included). Top-left origin.
    class Geometry(val width: Double, val height: Double, val label: Rect?, val headline: Rect?)
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun contains(px: Double, py: Double) = px >= x && px < x + width && py >= y && py < y + height
    }
    fun geometry(label: Pair<Double, Double>?, headline: Pair<Double, Double>?, fontSize: Double): Geometry {
        val pad = Math.ceil(card.padding * fontSize)
        val content = maxOf(label?.first ?: 0.0, headline?.first ?: 0.0)
        val gap = if (label != null && headline != null) Math.ceil(card.spacing * fontSize) else 0.0
        val headlineTop = pad + (label?.second ?: 0.0) + gap
        val width = content + 2 * pad; val height = headlineTop + (headline?.second ?: 0.0) + pad
        fun travel(fraction: Double, origin: Double, available: Double) = origin + fraction * (if (fraction < 0) origin else maxOf(0.0, available - origin))
        fun place(size: Pair<Double, Double>?, style: TitleDesign, y: Double, offset: TitleCard.Offset?): Rect? {
            val (w, h) = size ?: return null
            val slack = content - w
            val x = pad + when (style.alignment) { TitleAlignment.Left -> 0.0; TitleAlignment.Right -> slack; TitleAlignment.Center -> slack / 2 }
            return Rect(travel(offset?.x ?: 0.0, x, width - w), travel(offset?.y ?: 0.0, y, height - h), w, h)
        }
        return Geometry(width, height, place(label, labelStyle, pad, card.labelOffset), place(headline, headlineStyle, headlineTop, card.headlineOffset))
    }

    companion object {
        const val defaultHeadline = "Second text"
        private val L = TitleAlignment.Left
        val looks: List<Pair<String, Look>> = listOf(
            "Graphite" to LabelHeadline().look,
            "Editorial" to Look(
                TitleDesign(face = "ibmplexmono", weight = 500, alignment = L, tracking = 0.075, fill = TitleInk.of(0x962C26), shadow = false),
                TitleDesign(face = "sourceserif4", weight = 600, alignment = L, tracking = -0.01, fill = TitleInk.of(0x221D1A), shadow = false),
                0.42, 1.0, TitleCard(fill = TitleInk(0xF7F3EA, 246), border = TitleInk.of(0x51463C), borderWidth = 0.022, padding = 0.46, radius = 0.04, spacing = 0.12)),
            "Broadcast" to Look(
                TitleDesign(face = "barlowcondensed", weight = 700, alignment = L, tracking = 0.08, fill = TitleInk.of(0x111111), shadow = false,
                    plate = TitlePlate.Block, backing = TitleInk.of(0xFFD60A), padding = 0.20, radius = 0.06),
                TitleDesign(face = "barlowcondensed", weight = 700, alignment = L, shadow = false),
                0.44, 1.0, TitleCard(fill = TitleInk(0x0B0F14, 236), borderWidth = 0.0, padding = 0.36, radius = 0.08, spacing = 0.12)),
            "Notebook" to Look(
                TitleDesign(face = "caveat", weight = 700, alignment = L, fill = TitleInk.of(0xFFE066), shadow = false),
                TitleDesign(face = "fraunces", weight = 600, italic = true, alignment = L, tracking = -0.01, shadow = false),
                0.62, 1.0, TitleCard(fill = TitleInk(0x14213D, 232), borderWidth = 0.0, padding = 0.44, radius = 0.30, spacing = 0.06)),
            "Terminal" to Look(
                TitleDesign(face = "ibmplexmono", weight = 500, alignment = L, tracking = 0.06, fill = TitleInk.of(0x61E3A5), shadow = false),
                TitleDesign(face = "ibmplexmono", weight = 700, alignment = L, tracking = -0.025, fill = TitleInk.of(0xE6EDF3), shadow = false),
                0.41, 0.94, TitleCard(fill = TitleInk(0x0D1517, 240), border = TitleInk.of(0x61E3A5), borderWidth = 0.025, padding = 0.40, radius = 0.12, spacing = 0.12)),
            "Poster" to Look(
                TitleDesign(face = "barlowcondensed", weight = 700, alignment = L, tracking = 0.07, fill = TitleInk.black, shadow = false,
                    plate = TitlePlate.Block, backing = TitleInk.white, padding = 0.18, radius = 0.0),
                TitleDesign(face = "archivoblack", weight = 400, alignment = L, tracking = -0.035, shadow = false,
                    plate = TitlePlate.Block, backing = TitleInk.of(0xB7251F), padding = 0.15, radius = 0.0),
                0.44, 1.0, TitleCard(fill = TitleInk(0, 0), borderWidth = 0.0, padding = 0.12, radius = 0.0, spacing = 0.03)))
    }
}
