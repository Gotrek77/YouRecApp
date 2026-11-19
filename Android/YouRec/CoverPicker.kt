package yourec

import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/// Single pictures of the finished edit, drawn by the same path as preview and
/// export, on a thread of their own. Requests are served in order; one asked
/// with `latest` replaces any that has not started yet.
class EditFrames(project: Project, folder: File) {
    private val thread = HandlerThread("yourec-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val plan = RenderPlan(project.edit)
    private val sources = RenderSources(project, folder)
    /// The project picture size, as the export would make it.
    val canvas: CanvasSize = sources.canvas(project.edit)
    private var egl: EGLCore? = null
    private var program: FrameProgram? = null
    @Volatile private var generation = 0
    @Volatile private var released = false

    fun grab(time: EditTime, longestSide: Int, latest: Boolean = false, completion: (Bitmap?) -> Unit) {
        if (latest) generation += 1
        val asked = generation
        handler.post {
            if (released || (latest && asked != generation)) return@post
            val picture = try { render(time, longestSide) } catch (e: Exception) {
                android.util.Log.w("YouRec", "cover: frame at ${time.seconds} s failed", e); null
            }
            main.post { if (!released && (!latest || asked == generation)) completion(picture) }
        }
    }

    private fun render(time: EditTime, longestSide: Int): Bitmap? {
        val core = egl ?: EGLCore().also { egl = it; it.makeCurrent(null) }
        val shader = program ?: FrameProgram().also { program = it }
        val scale = minOf(1.0, longestSide / maxOf(canvas.width, canvas.height))
        val width = RenderPlan.even(canvas.width * scale); val height = RenderPlan.even(canvas.height * scale)
        val ids = IntArray(2)
        GLES20.glGenTextures(1, ids, 0); GLES20.glGenFramebuffers(1, ids, 1)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[1])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null
            FrameProgram.clear(width, height)
            if (!sources.draw(plan.layers(time), shader, width, height, plan.wash(time))) return null
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            // GL rows start at the bottom of the picture.
            val row = width * 4; val flipped = ByteBuffer.allocateDirect(pixels.capacity()); val line = ByteArray(row)
            for (y in height - 1 downTo 0) { pixels.position(y * row); pixels.get(line); flipped.put(line) }
            flipped.rewind()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(flipped) }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, ids, 1); GLES20.glDeleteTextures(1, ids, 0)
            core.makeCurrent(null)
        }
    }

    fun release() {
        released = true
        handler.post {
            sources.releaseFrames()
            program?.release(); program = null
            egl?.release(); egl = null
            thread.quitSafely()
        }
        Thread { sources.releaseSound() }.start()
    }

    companion object {
        fun timestamp(time: EditTime): String {
            val hundredths = Math.round(time.seconds * 100)
            return String.format(java.util.Locale.ROOT, "%d:%02d.%02d", hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
        }
        /// Write a picture as the JPEG a cover is shared as, named after the project and time.
        fun jpeg(picture: Bitmap, title: String, time: EditTime, folder: File): File {
            val name = title.split(Regex("[/:\\\\\\p{Cntrl}]")).filter { it.isNotEmpty() }.joinToString("-").trim().ifEmpty { "YouRec" }.take(40)
            val file = File(folder, name + " - " + timestamp(time).replace(":", "-") + ".jpg")
            val partial = File(folder, "." + file.name + ".partial")
            java.io.FileOutputStream(partial).use { picture.compress(Bitmap.CompressFormat.JPEG, 95, it); it.fd.sync() }
            RecoveryFile.move(partial, file, replace = true)
            return file
        }
    }
}
