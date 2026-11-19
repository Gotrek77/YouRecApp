package yourec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils

/// Titles as textures. A title is drawn once, at the size it will have on the
/// frame being rendered, and reused for every frame it stays on screen. The
/// preview and the export draw the same bitmap, scaled only by frame size.
/// Everything here belongs to the thread that owns the GL context.
class TitleTextures {
    class Entry(val texture: Int, val size: CanvasSize)
    private val entries = LinkedHashMap<Pair<TextOverlay, Int>, Entry>(16, 0.75f, true)
    private var program: OverlayProgram? = null

    /// Draw one title on a canvas of `width` by `height`, through `placement` when its picture has a window of its own.
    fun draw(title: TextOverlay, width: Int, height: Int, placement: Affine?) {
        val short = minOf(width, height)
        // Position and time do not change the picture of a title: share it.
        val key = title.copy(start = 0.0, end = 0.0, x = 0.0, y = 0.0) to short
        val entry = entries.getOrPut(key) { render(key.first, short) ?: return }
        while (entries.size > 24) { val oldest = entries.keys.first(); entries.remove(oldest)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) } }
        val shader = program ?: OverlayProgram().also { program = it }
        var place = Affine.translation(title.x * width - entry.size.width / 2, title.y * height - entry.size.height / 2)
        if (placement != null) place = place.then(placement)
        shader.draw(entry.texture, place, entry.size, CanvasSize(width.toDouble(), height.toDouble()))
    }

    private fun render(title: TextOverlay, short: Int): Entry? {
        val bitmap = picture(title, short) ?: return null
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        val entry = Entry(ids[0], CanvasSize(bitmap.width.toDouble(), bitmap.height.toDouble()))
        bitmap.recycle()
        return entry
    }

    fun release() {
        for (entry in entries.values) GLES20.glDeleteTextures(1, intArrayOf(entry.texture), 0)
        entries.clear()
        program?.release(); program = null
    }

    companion object {
        /// The four plain styles: Clean and Bold are coloured letters with a soft
        /// shadow to stay legible over footage; Box sets them on a dark plate;
        /// Highlight sets black letters on a plate of the chosen colour.
        fun picture(title: TextOverlay, short: Int): Bitmap? {
            val lines = title.text.trim().split("\n").map { it.trimEnd() }
            if (lines.all { it.isBlank() }) return null
            val size = (title.size * short).toFloat().coerceAtLeast(4f)
            val plate = title.style == TextStyle.Box || title.style == TextStyle.Highlight
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = size
                typeface = if (title.style == TextStyle.Clean) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                color = if (title.style == TextStyle.Highlight) TextColor.Black.argb else title.color.argb
                textAlign = Paint.Align.CENTER
                if (!plate) setShadowLayer(size * 0.08f, 0f, size * 0.04f, 0x99000000.toInt())
            }
            val metrics = paint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent
            val widest = lines.maxOf { paint.measureText(it) }
            // Room for the plate, or for the shadow to fade inside the bitmap.
            val padX = size * (if (plate) 0.35f else 0.2f); val padY = size * (if (plate) 0.18f else 0.2f)
            val width = Math.ceil((widest + 2 * padX).toDouble()).toInt().coerceIn(2, 8192)
            val height = Math.ceil((lineHeight * lines.size + 2 * padY).toDouble()).toInt().coerceIn(2, 8192)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (plate) canvas.drawColor(if (title.style == TextStyle.Highlight) title.color.argb else 0xD1050505.toInt())
            for ((index, line) in lines.withIndex()) canvas.drawText(line, width / 2f, padY - metrics.ascent + index * lineHeight, paint)
            return bitmap
        }
    }
}
