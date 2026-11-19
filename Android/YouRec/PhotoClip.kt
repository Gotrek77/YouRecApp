package yourec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.File

/// A self-contained, silent timeline source, as on iPhone: the still is
/// decoded once and written as three seconds of 30 fps video, so frame-exact
/// edits and exports treat it like any other take.
object PhotoClip {
    const val seconds = 3
    private const val fps = 30
    private const val longest = 3840

    /// The picture upright, at most 3840 pixels on its longer side.
    fun decode(context: Context, uri: Uri, longest: Int = PhotoClip.longest): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ProjectError.InvalidMedia()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw ProjectError.InvalidMedia()
        val orientation = try { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            catch (e: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val turn = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> turn.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> turn.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> turn.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> turn.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> turn.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { turn.postRotate(90f); turn.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { turn.postRotate(270f); turn.postScale(-1f, 1f) }
        }
        var upright = if (turn.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
        val scale = minOf(1.0, longest.toDouble() / maxOf(upright.width, upright.height))
        if (scale < 1) upright = Bitmap.createScaledBitmap(upright, maxOf(2, (upright.width * scale).toInt()), maxOf(2, (upright.height * scale).toInt()), true)
        return upright
    }

    /// The largest even size no bigger than the picture that the H.264 encoder accepts.
    private fun encodable(width: Int, height: Int): Pair<Int, Int> {
        val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        var scale = 1.0
        while (scale > 0.1) {
            val w = maxOf(2, (width * scale / 2).toInt() * 2); val h = maxOf(2, (height * scale / 2).toInt() * 2)
            if (capabilities.isEmpty() || capabilities.any { it.isSizeSupported(w, h) }) return w to h
            scale *= 0.9
        }
        return 1920 to 1080
    }

    /// Write the still as a silent MP4. The file is complete when this returns.
    fun write(picture: Bitmap, destination: File) {
        val (width, height) = encodable(picture.width, picture.height)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, maxOf(2_000_000, minOf(40_000_000, width * height * 4)))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null; var egl: EGLCore? = null; var overlay: OverlayProgram? = null
        var complete = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val core = EGLCore().also { egl = it }
            val window = core.windowSurface(input); core.makeCurrent(window)
            val program = OverlayProgram().also { overlay = it }
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, picture, 0)
            val size = CanvasSize(picture.width.toDouble(), picture.height.toDouble()); val canvas = CanvasSize(width.toDouble(), height.toDouble())
            val fit = Affine(a = width / size.width, d = height / size.height)
            var track = -1; val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer!!.addTrack(encoder.outputFormat); muxer!!.start(); continue }
                    if (index < 0) continue
                    val buffer = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) { buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer!!.writeSampleData(track, buffer, info) }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            for (frame in 0 until seconds * fps) {
                FrameProgram.clear(width, height)
                program.draw(ids[0], fit, size, canvas)
                core.setPresentationTime(window, frame * 1_000_000_000L / fps)
                core.swap(window)
                drain(false)
            }
            encoder.signalEndOfInputStream()
            drain(true)
            GLES20.glDeleteTextures(1, ids, 0)
            core.release(window)
            muxer.stop()
            complete = true
        } finally {
            overlay?.release(); egl?.release()
            try { encoder.stop() } catch (e: Exception) { }
            encoder.release()
            try { muxer?.release() } catch (e: Exception) { }
            if (!complete) destination.delete()
        }
    }
}
