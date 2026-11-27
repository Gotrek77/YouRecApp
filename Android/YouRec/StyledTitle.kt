package yourec

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/// The bundled title typefaces, from the APK's TitleFonts assets.
object TitleFonts {
    var assets: AssetManager? = null
    private val cache = HashMap<String, Typeface>()

    /// The typeface for a design: the family's file for its slant, at its weight.
    /// A family without a slanted file is drawn upright, as on iPhone: italic is never synthesized.
    fun typeface(design: TitleDesign, folder: java.io.File? = null): Typeface = synchronized(cache) {
        if (design.face.startsWith("import:")) return imported(design, folder)
        val family = TitleTypeface.of(design.face)
        val italic = design.italic && family?.slanted != null
        val key = "${design.face}/${design.weight}/$italic"
        cache[key]?.let { return it }
        val made = if (family == null || family.id == "system") Typeface.create(fallback(design.face), design.weight.coerceIn(1, 1000), design.italic)
            else load(family.id, design.weight, italic)
        cache[key] = made
        made
    }

    /// A face imported into the project, at its weight when it has a weight axis. A missing
    /// face is drawn in the system font, as on iPhone, and is not remembered: its file may arrive.
    private fun imported(design: TitleDesign, folder: java.io.File?): Typeface {
        val (file, face) = ImportedFonts.resolve(design.face, folder) ?: return Typeface.DEFAULT
        val key = "${file.path}/${design.face}/${design.weight}"
        cache[key]?.let { return it }
        val index = ImportedFontFace.Reference.of(design.face)!!.index
        val made = try {
            Typeface.Builder(file).setTtcIndex(index).apply { if (face.weights.size > 1) setFontVariationSettings("'wght' ${design.weight}") }.build()
        } catch (e: Exception) { android.util.Log.w("YouRec", "imported font ${file.name} cannot be read", e); null } ?: return Typeface.DEFAULT
        cache[key] = made
        return made
    }

    // ponytail: a few iPhone font names mapped to Android's generic families; any other face is the system font.
    private fun fallback(face: String): Typeface {
        val name = face.lowercase()
        return when {
            listOf("menlo", "courier", "mono").any { it in name } -> Typeface.MONOSPACE
            listOf("georgia", "times", "serif", "baskerville", "palatino", "didot", "bodoni", "garamond").any { it in name } && "sans" !in name -> Typeface.SERIF
            listOf("condensed", "narrow", "compressed").any { it in name } -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    private fun load(id: String, weight: Int, italic: Boolean): Typeface {
        val assets = assets ?: return Typeface.DEFAULT
        // Static files chosen by weight; variable files at the weight, their other axes pinned to the instance the iPhone app starts from.
        val (file, variation) = when (id) {
            "barlowcondensed" -> "BarlowCondensed-" + TitleTypeface.staticStyle(id, weight, italic) + ".ttf" to null
            "ibmplexmono" -> "IBMPlexMono-" + TitleTypeface.staticStyle(id, weight, italic) + ".ttf" to null
            "archivoblack" -> "ArchivoBlack-Regular.ttf" to null
            "atkinsonhyperlegiblenext" -> (if (italic) "AtkinsonHyperlegibleNext-Italic[wght].ttf" else "AtkinsonHyperlegibleNext[wght].ttf") to "'wght' $weight"
            "sourceserif4" -> (if (italic) "SourceSerif4-Italic[opsz,wght].ttf" else "SourceSerif4[opsz,wght].ttf") to "'wght' $weight, 'opsz' 20"
            "fraunces" -> (if (italic) "Fraunces-Italic[SOFT,WONK,opsz,wght].ttf" else "Fraunces[SOFT,WONK,opsz,wght].ttf") to "'wght' $weight, 'opsz' 9, 'SOFT' 0, 'WONK' 1"
            "bodonimoda" -> (if (italic) "BodoniModa-Italic[opsz,wght].ttf" else "BodoniModa[opsz,wght].ttf") to "'wght' $weight, 'opsz' 11"
            "unbounded" -> "Unbounded[wght].ttf" to "'wght' $weight"
            "caveat" -> "Caveat[wght].ttf" to "'wght' $weight"
            else -> return Typeface.DEFAULT
        }
        return try {
            Typeface.Builder(assets, "TitleFonts/$id/$file").apply { if (variation != null) setFontVariationSettings(variation) }.build() ?: Typeface.DEFAULT
        } catch (e: Exception) {
            android.util.Log.w("YouRec", "title font $id/$file cannot be read", e); Typeface.DEFAULT
        }
    }
}

/// A designed title, ported from the iPhone app's StyledTextLayout: the whole
/// string is shaped first, then the plate, the outline outside the letters, the
/// fill or gradient, and one shadow cast by the finished lettering (not the plate).
class StyledTitle(text: String, val design: TitleDesign, val fontSize: Float, maxWidth: Float, folder: java.io.File? = null) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = TitleFonts.typeface(design, folder); textSize = fontSize; letterSpacing = design.tracking.toFloat()
    }
    private val inset: Float
    private val layout: StaticLayout
    val width: Float
    val height: Float

    init {
        val effect = (if (design.outlined) design.borderWidth else 0.0) + (if (design.shadow) 2 * design.shadowBlur + Math.abs(design.shadowOffset) else 0.0)
        inset = Math.ceil(fontSize * (maxOf(effect, if (design.plate == TitlePlate.Off) 0.0 else design.padding) + 0.14)).toFloat() + 2
        val limit = maxOf(fontSize, maxWidth - 2 * inset)
        val measured = build(text, limit.toInt())
        val widest = (0 until measured.lineCount).maxOfOrNull { measured.getLineMax(it) } ?: 0f
        val box = Math.ceil(maxOf(fontSize, widest).toDouble()).toFloat() + 2
        layout = build(text, box.toInt())
        width = box + 2 * inset
        height = Math.ceil(maxOf(fontSize, layout.height.toFloat()).toDouble()).toFloat() + 2 + 2 * inset
    }

    private fun build(text: String, width: Int): StaticLayout {
        val alignment = when (design.alignment) { TitleAlignment.Left -> Layout.Alignment.ALIGN_NORMAL; TitleAlignment.Center -> Layout.Alignment.ALIGN_CENTER; TitleAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxOf(1, width)).setAlignment(alignment)
            .setLineSpacing((design.lineSpacing * fontSize).toFloat(), 1f).setIncludePad(false).build()
    }

    /// The title at `scale` (1 is its own size); null when the picture would be unreasonably large.
    fun picture(scale: Float): Bitmap? {
        if (!scale.isFinite() || scale <= 0) return null
        val w = Math.ceil((width * scale).toDouble()).toInt(); val h = Math.ceil((height * scale).toDouble()).toInt()
        if (w < 1 || h < 1 || w > 16384 || h > 16384 || w.toLong() * h > 32_000_000) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save(); canvas.scale(scale, scale); plate(canvas); canvas.restore()
        val letters = lettering(w, h, scale)
        if (design.shadow) {
            val blur = (design.shadowBlur * fontSize * scale).toFloat()
            // Core Graphics' shadow blur is about twice the Gaussian's deviation; Android's radius maps to deviation as 0.577 r + 0.5.
            val radius = ((blur / 2 - 0.5f) / 0.57735f)
            val offset = IntArray(2)
            val shadow = letters.extractAlpha(Paint().apply { if (radius > 0.5f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }, offset)
            canvas.drawBitmap(shadow, offset[0].toFloat(), offset[1] + (design.shadowOffset * fontSize * scale).toFloat(), Paint().apply { color = design.shade.argb })
            shadow.recycle()
        }
        canvas.drawBitmap(letters, 0f, 0f, null)
        letters.recycle()
        return bitmap
    }

    private fun plate(canvas: Canvas) {
        if (design.plate == TitlePlate.Off) return
        val pad = (design.padding * fontSize).toFloat(); val radius = (design.radius * fontSize).toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.backing.argb }
        fun box(r: RectF) { val corner = minOf(radius, minOf(r.width(), r.height()) / 2); canvas.drawRoundRect(r, corner, corner, fill) }
        if (design.plate == TitlePlate.Block) { box(RectF(inset - pad, inset - pad, width - inset + pad, height - inset + pad)); return }
        // One plate per line, around its letters without the trailing spaces.
        val metrics = paint.fontMetrics
        val path = android.graphics.Path()
        for (line in 0 until layout.lineCount) {
            val extent = layout.getLineMax(line)
            if (extent <= 0) continue
            val left = inset + when (design.alignment) { TitleAlignment.Left -> 0f; TitleAlignment.Center -> (layout.width - extent) / 2; TitleAlignment.Right -> layout.width - extent }
            val baseline = inset + layout.getLineBaseline(line)
            val r = RectF(left - pad, baseline + metrics.ascent - pad, left + extent + pad, baseline + metrics.descent + pad)
            val corner = minOf(radius, minOf(r.width(), r.height()) / 2)
            path.addRoundRect(r, corner, corner, android.graphics.Path.Direction.CW)
        }
        canvas.drawPath(path, fill)
    }

    /// Outline and letters together, in output pixels: the layer the shadow is cast from.
    private fun lettering(w: Int, h: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale); canvas.translate(inset, inset)
        if (design.outlined && design.borderWidth > 0) {
            // A round-joined stroke twice as wide as the border grows each letter by the border, as a morphological maximum does.
            paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = (2 * design.borderWidth * fontSize).toFloat()
            paint.color = design.border.argb; layout.draw(canvas)
            // Only the ring outside the letters keeps the border colour; the fill is laid over clear glyphs.
            paint.style = Paint.Style.FILL; paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); layout.draw(canvas); paint.xfermode = null
        }
        paint.style = Paint.Style.FILL
        val gradient = design.gradient
        if (gradient != null) {
            val (a, b) = gradient.axis(width.toDouble() - 2 * inset, height.toDouble() - 2 * inset)
            paint.color = -1
            paint.shader = LinearGradient(a.first.toFloat(), a.second.toFloat(), b.first.toFloat(), b.second.toFloat(), gradient.first.argb, gradient.last.argb, Shader.TileMode.CLAMP)
        } else paint.color = design.fill.argb
        layout.draw(canvas)
        paint.shader = null
        return bitmap
    }

    companion object {
        /// A designed overlay title on a canvas of the given size, as the iPhone's TextRasterizer draws it:
        /// lines wrap at 94% of the frame, and the whole title shrinks to fit inside it.
        fun picture(title: TextOverlay, design: TitleDesign, canvasWidth: Int, canvasHeight: Int, folder: java.io.File? = null): Bitmap? {
            if (!title.hasText || canvasWidth <= 0 || canvasHeight <= 0) return null
            val styled = StyledTitle(title.text, design, maxOf(8f, (title.size * minOf(canvasWidth, canvasHeight)).toFloat()), canvasWidth * 0.94f, folder)
            return styled.picture(minOf(1f, canvasWidth / styled.width, canvasHeight / styled.height))
        }

        /// A label and a headline on their card, as the iPhone's LabelHeadlineLayout draws them; the card shrinks to fit the frame.
        fun card(title: TextOverlay, group: LabelHeadline, canvasWidth: Int, canvasHeight: Int, folder: java.io.File? = null): Bitmap? {
            if (!title.hasText || canvasWidth <= 0 || canvasHeight <= 0) return null
            val fontSize = maxOf(8f, (title.size * minOf(canvasWidth, canvasHeight)).toFloat())
            val room = maxOf(1f, canvasWidth * 0.94f - 2 * Math.ceil(group.card.padding * fontSize).toFloat())
            fun line(words: String, style: TitleDesign, scale: Double) = if (words.isBlank()) null else StyledTitle(words, style, (fontSize * scale).toFloat(), room, folder)
            val label = line(group.label, group.labelStyle, group.labelScale)
            val headline = line(title.text, group.headlineStyle, group.headlineScale)
            val shape = group.geometry(label?.let { it.width.toDouble() to it.height.toDouble() }, headline?.let { it.width.toDouble() to it.height.toDouble() }, fontSize.toDouble())
            val width = shape.width.toFloat(); val height = shape.height.toFloat()
            if (width <= 0 || height <= 0) return null
            val scale = minOf(1f, canvasWidth / width, canvasHeight / height)
            val w = Math.ceil((width * scale).toDouble()).toInt(); val h = Math.ceil((height * scale).toDouble()).toInt()
            if (w < 1 || h < 1 || w > 16384 || h > 16384 || w.toLong() * h > 32_000_000) return null
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.save(); canvas.scale(scale, scale)
            val card = group.card
            val radius = minOf((card.radius * fontSize).toFloat(), minOf(width, height) / 2)
            canvas.drawRoundRect(RectF(0f, 0f, width, height), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.fill.argb })
            val stroke = minOf((card.borderWidth * fontSize).toFloat(), minOf(width, height))
            if (stroke > 0) {
                val inset = stroke / 2; val r = maxOf(0f, radius - inset)
                canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), r, r,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = card.border.argb })
            }
            canvas.restore()
            for ((styled, rect) in listOf(label to shape.label, headline to shape.headline)) {
                if (styled == null || rect == null) continue
                val picture = styled.picture(scale) ?: run { bitmap.recycle(); return null }
                val target = RectF((rect.x * scale).toFloat(), (rect.y * scale).toFloat(), ((rect.x + rect.width) * scale).toFloat(), ((rect.y + rect.height) * scale).toFloat())
                canvas.drawBitmap(picture, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
                picture.recycle()
            }
            return bitmap
        }
    }
}
