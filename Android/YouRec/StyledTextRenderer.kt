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
/// Pictures placed over the video, loaded once per file onto the GL thread.
class ImageTextures(private val folder: java.io.File) {
    private class Entry(val texture: Int, val size: CanvasSize)
    private val entries = LinkedHashMap<String, Entry?>(8, 0.75f, true)
    private var program: OverlayProgram? = null

    fun draw(image: ImageOverlay, width: Int, height: Int, placement: Affine?, alpha: Float = 1f) {
        val entry = (if (entries.containsKey(image.filename)) entries[image.filename] else load(image.filename).also { entries[image.filename] = it }) ?: return
        while (entries.size > 8) { val oldest = entries.keys.first(); entries.remove(oldest)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) } }
        val shader = program ?: OverlayProgram().also { program = it }
        val canvas = CanvasSize(width.toDouble(), height.toDouble())
        val rect = image.rect(entry.size.width, entry.size.height, canvas)
        var place = Affine(a = rect.width / entry.size.width, d = rect.height / entry.size.height, tx = rect.x, ty = rect.y)
        if (placement != null) place = place.then(placement)
        shader.draw(entry.texture, place, entry.size, canvas, image.opacity.toFloat() * alpha)
    }
    private fun load(name: String): Entry? {
        if (!ImageOverlay.validFilename(name)) return null
        val file = java.io.File(folder, name)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) { android.util.Log.w("YouRec", "image overlay $name cannot be read"); return null }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
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
        for (entry in entries.values) entry?.let { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) }
        entries.clear(); program?.release(); program = null
    }
}

class TitleTextures {
    class Entry(val texture: Int, val size: CanvasSize)
    private val entries = LinkedHashMap<Pair<TextOverlay, Pair<Int, Int>>, Entry>(16, 0.75f, true)
    private var program: OverlayProgram? = null

    /// Draw one title on a canvas of `width` by `height`, through `placement` when its picture has a window of its own.
    fun draw(title: TextOverlay, width: Int, height: Int, placement: Affine?, alpha: Float = 1f) {
        // Position and time do not change the picture of a title: share it.
        val key = title.copy(start = 0.0, end = 0.0, x = 0.0, y = 0.0) to (width to height)
        val entry = entries.getOrPut(key) { render(key.first, width, height) ?: return }
        while (entries.size > 24) { val oldest = entries.keys.first(); entries.remove(oldest)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) } }
        val shader = program ?: OverlayProgram().also { program = it }
        // Kept inside the recorded frame, not the device screen or its safe area.
        var place = Affine.translation((title.x * width - entry.size.width / 2).coerceIn(0.0, maxOf(0.0, width - entry.size.width)),
                                       (title.y * height - entry.size.height / 2).coerceIn(0.0, maxOf(0.0, height - entry.size.height)))
        if (placement != null) place = place.then(placement)
        shader.draw(entry.texture, place, entry.size, CanvasSize(width.toDouble(), height.toDouble()), alpha)
    }

    private fun render(title: TextOverlay, width: Int, height: Int): Entry? {
        val bitmap = picture(title, width, height) ?: return null
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
        /// The four plain styles, drawn as the iPhone app draws them: Clean and
        /// Bold are coloured letters with a soft shadow to stay legible over
        /// footage; Box sets them on a dark rounded plate; Highlight sets near-black
        /// letters on a plate of the chosen colour. Long lines wrap at 84% of the frame.
        fun picture(title: TextOverlay, canvasWidth: Int, canvasHeight: Int): Bitmap? {
            if (!title.hasText) return null
            val size = maxOf(8f, (title.size * minOf(canvasWidth, canvasHeight)).toFloat())
            val plate = title.style == TextStyle.Box || title.style == TextStyle.Highlight
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = size
                typeface = if (title.style == TextStyle.Clean) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                color = if (title.style == TextStyle.Highlight) TextColor.Black.argb else title.color.argb
                textAlign = Paint.Align.CENTER
                if (!plate) setShadowLayer(size * 0.12f, 0f, size * 0.04f, 0xE6000000.toInt())
            }
            val padding = size * 0.35f
            val widest = canvasWidth * 0.84f - padding * 2
            val lines = ArrayList<String>()
            for (paragraph in title.text.trim().split("\n")) {
                var line = ""
                for (word in paragraph.trim().split(" ")) {
                    val longer = if (line.isEmpty()) word else "$line $word"
                    if (line.isNotEmpty() && paint.measureText(longer) > widest) { lines.add(line); line = word } else line = longer
                }
                lines.add(line)
            }
            val metrics = paint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent
            val shown = lines.take(maxOf(1, ((canvasHeight * 0.8f) / lineHeight).toInt()))
            val width = Math.ceil((minOf(widest, maxOf(size, shown.maxOf { paint.measureText(it) })) + 2 * padding).toDouble()).toInt().coerceIn(2, 8192)
            val height = Math.ceil((lineHeight * shown.size + 2 * padding).toDouble()).toInt().coerceIn(2, 8192)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (plate) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (title.style == TextStyle.Highlight) title.color.argb else 0xD1050505.toInt() }
                canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, padding * 0.7f, padding * 0.7f, fill)
            }
            for ((index, line) in shown.withIndex()) canvas.drawText(line, width / 2f, padding - metrics.ascent + index * lineHeight, paint)
            return bitmap
        }
    }
}
