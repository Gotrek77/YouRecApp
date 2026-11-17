package yourec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/// One movie segment: H.264 from a Surface and AAC from the microphone, in an
/// MP4. It owns encoding independently of the camera. A camera change may stop
/// camera frames; whoever draws to `surface` keeps presenting the last picture,
/// and the microphone keeps writing here, so sound and picture stay the same length.
///
/// An MP4 written by MediaMuxer has no index until stop(): every path out of
/// this class, including errors, goes through close().
class CaptureSegmentWriter(val file: File, val width: Int, val height: Int, bitrate: Int, private val audioChannels: Int,
                           private val onFinish: (CaptureSegmentWriter, String?) -> Unit) {
    /// The encoder's input. Draw upright frames of width by height and give each a time from videoTime().
    val surface: Surface
    /// The file is a complete movie. False with `empty` when nothing was written at all.
    @Volatile var playable = false; private set
    @Volatile var empty = false; private set

    private class Packet(val video: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)
    private class Chunk(val bytes: ByteArray, var offset: Int, var time: Long)

    private val thread = HandlerThread("yourec.media").apply { start() }
    private val handler = Handler(thread.looper)
    private val muxer: MediaMuxer
    private val video: MediaCodec
    private var audio: MediaCodec? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private val waiting = ArrayList<Packet>()      // encoded before both tracks were known
    private val pcm = ArrayDeque<Chunk>()
    private val freeInputs = ArrayDeque<Int>()
    private var audioAnchor = -1L                  // microseconds; audio time is counted in samples from here
    private var audioFrames = 0L
    private var finishDeadline = 0L
    private val giveUp = Runnable { close(null) }
    private var videoWritten = 0
    private var lastVideo = -1L
    private var lastAudio = -1L
    private var finishing = false
    private var videoEnded = false
    private var audioEnded = false
    private var audioEndSent = false
    private var closed = false
    private val done = CountDownLatch(1)
    private val events = ArrayList<CaptureEvent>()
    // Zero until the first video frame: the movie starts with a picture, and
    // sound captured before it is trimmed to the sample.
    @Volatile private var origin = 0L

    // Declared before init: the encoders take these callbacks while being configured.
    private val videoEvents = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = output(codec, index, info, true)
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { if (!closed) { videoFormat = format; begin() } }
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = close("The video encoder stopped. " + (e.diagnosticInfo ?: ""))
    }
    private val audioEvents = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { if (!closed) { freeInputs.addLast(index); feed() } }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = output(codec, index, info, false)
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { if (!closed) { audioFormat = format; begin() } }
        // Picture without sound is still a take; the gap is audible, not fatal.
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { audioEnded = true; if (finishing && videoEnded) close(null) }
    }

    init {
        var encoder: MediaCodec? = null; var sound: MediaCodec? = null; var container: MediaMuxer? = null
        try {
            encoder = videoEncoder(bitrate)
            surface = encoder.createInputSurface()
            if (audioChannels > 0) {
                sound = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                sound.setCallback(audioEvents, handler)
                sound.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, CaptureAudio.sampleRate, audioChannels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 96_000 * audioChannels)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            container = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder.start(); sound?.start()
        } catch (e: Exception) {
            try { encoder?.release() } catch (ignored: Exception) { }
            try { sound?.release() } catch (ignored: Exception) { }
            try { container?.release() } catch (ignored: Exception) { }
            thread.quitSafely(); file.delete()
            throw e
        }
        video = encoder; audio = sound; muxer = container
    }

    private fun videoEncoder(bitrate: Int): MediaCodec {
        fun format(high: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Say what the pixels are, or players guess from the frame size.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, if (width * height > 1920 * 1088) MediaCodecInfo.CodecProfileLevel.AVCLevel51 else MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        // High profile is worth a third of the bitrate, but not every encoder
        // accepts an explicit request for it. A failed configure spoils the codec.
        for (high in listOf(true, false)) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.setCallback(videoEvents, handler)
                codec.configure(format(high), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: Exception) { codec.release(); if (!high) throw e }
        }
        throw IllegalStateException()
    }

    /// Called by the drawing thread for each frame it submits; returns the
    /// presentation time to give that frame. `nanoseconds` is on the System.nanoTime clock.
    fun videoTime(nanoseconds: Long): Long {
        if (origin == 0L) origin = nanoseconds
        return nanoseconds - origin
    }

    /// Microphone samples, 16-bit interleaved, with the nanoTime of the first one. Any thread.
    fun audio(samples: ByteArray, count: Int, nanoseconds: Long) {
        if (audio == null || origin == 0L) return
        val copy = samples.copyOf(count)
        handler.post { accept(copy, nanoseconds) }
    }

    /// A mark at `nanoseconds`. The sidecar is rewritten at once: it must be
    /// complete on disk before the movie is, and survive if the movie is not.
    fun event(kind: String, detail: String, nanoseconds: Long = System.nanoTime()) {
        handler.post {
            if (closed) return@post
            events.add(CaptureEvent(time = if (origin == 0L) 0.0 else maxOf(0.0, (nanoseconds - origin) / 1e9), kind = kind, detail = detail))
            try {
                val sidecar = sidecar(file); val partial = File(file.parentFile, "." + sidecar.name + ".saving")
                partial.writeBytes(JSON.write(events.map { jsonObject("id" to it.id.text, "time" to it.time, "kind" to it.kind, "detail" to it.detail) }))
                if (!partial.renameTo(sidecar)) partial.delete()
            } catch (e: Exception) { }
        }
    }

    /// End the movie. Nothing may be drawn to `surface` after this call.
    fun finish() {
        handler.post {
            if (finishing || closed) return@post
            finishing = true
            try { video.signalEndOfInputStream() } catch (e: Exception) { videoEnded = true }
            if (audio == null || audioTrack < 0 && muxing) audioEnded = true else feed()
            // An encoder that never answers must not keep the file open; one that
            // is still delivering frames it had queued is given time to finish.
            finishDeadline = android.os.SystemClock.uptimeMillis() + 12_000
            handler.postDelayed(giveUp, 2500)
            if (videoEnded && audioEnded) close(null)
        }
    }
    /// Wait for the file to be finalized; false if it is still open after the timeout.
    fun await(milliseconds: Long): Boolean = try { done.await(milliseconds, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }

    private fun output(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        if (closed) return
        try {
            val buffer = codec.getOutputBuffer(index)
            // Parameter sets already travel in the track format.
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                if (muxing) write(isVideo, buffer, info)
                else {
                    val copy = ByteBuffer.allocate(info.size).apply { put(buffer); flip() }
                    waiting.add(Packet(isVideo, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }))
                    // The microphone was promised but stays quiet. Three seconds
                    // of picture are waiting: write them without a sound track.
                    if (audioFormat == null && videoFormat != null && waiting.count { it.video } > 90) begin(withoutAudio = true)
                }
            }
            codec.releaseOutputBuffer(index, false)
            // Each frame that still arrives after Stop pushes the forced close back, up to a limit.
            if (finishing && isVideo && android.os.SystemClock.uptimeMillis() < finishDeadline) { handler.removeCallbacks(giveUp); handler.postDelayed(giveUp, 2500) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                if (isVideo) videoEnded = true else audioEnded = true
                if (videoEnded && audioEnded) close(null)
            }
        } catch (e: Exception) { close("The recording could not be written. " + (e.message ?: "")) }
    }

    private fun begin(withoutAudio: Boolean = false) {
        val picture = videoFormat ?: return
        if (muxing || (audio != null && audioFormat == null && !withoutAudio)) return
        try {
            videoTrack = muxer.addTrack(picture)
            audioFormat?.let { audioTrack = muxer.addTrack(it) }
            muxer.start(); muxing = true
            for (packet in waiting) write(packet.video, packet.data, packet.info)
            waiting.clear()
            if (finishing && audioTrack < 0) { audioEnded = true; if (videoEnded) close(null) }
        } catch (e: Exception) { close("The recording could not be written. " + (e.message ?: "")) }
    }

    private fun write(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        // The muxer rejects time running backwards within a track.
        if (isVideo) {
            if (info.presentationTimeUs <= lastVideo) return
            muxer.writeSampleData(videoTrack, data, info); lastVideo = info.presentationTimeUs; videoWritten++
        } else if (audioTrack >= 0) {
            if (info.presentationTimeUs <= lastAudio) return
            muxer.writeSampleData(audioTrack, data, info); lastAudio = info.presentationTimeUs
        }
    }

    private fun accept(samples: ByteArray, nanoseconds: Long) {
        if (closed || finishing || audio == null) return
        val frame = audioChannels * 2
        var offset = 0
        var time = (nanoseconds - origin) / 1000
        if (time < 0) {
            val skip = (-time * CaptureAudio.sampleRate + 999_999) / 1_000_000
            if (skip * frame >= samples.size) return
            offset = (skip * frame).toInt(); time = 0
        }
        // Count samples instead of trusting each buffer's clock reading, which
        // jitters. Only a real hole, such as a replaced microphone, moves the anchor.
        val expected = audioAnchor + audioFrames * 1_000_000 / CaptureAudio.sampleRate
        if (audioAnchor < 0 || time - expected > 50_000) { audioAnchor = time; audioFrames = 0 } else time = expected
        audioFrames += (samples.size - offset) / frame
        // A stalled encoder must not grow this without bound; two seconds is a stall.
        if (pcm.size > 120) return
        pcm.addLast(Chunk(samples, offset, time))
        feed()
    }

    private fun feed() {
        val codec = audio ?: return
        try {
            while (freeInputs.isNotEmpty() && pcm.isNotEmpty()) {
                val index = freeInputs.removeFirst(); val chunk = pcm.first()
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                val frame = audioChannels * 2
                val count = minOf(buffer.remaining() / frame * frame, chunk.bytes.size - chunk.offset)
                buffer.put(chunk.bytes, chunk.offset, count)
                codec.queueInputBuffer(index, 0, count, chunk.time, 0)
                chunk.offset += count; chunk.time += count / frame * 1_000_000L / CaptureAudio.sampleRate
                if (chunk.offset >= chunk.bytes.size) pcm.removeFirst()
            }
            if (finishing && !audioEndSent && pcm.isEmpty() && freeInputs.isNotEmpty()) {
                audioEndSent = true
                codec.queueInputBuffer(freeInputs.removeFirst(), 0, 0, audioAnchor.coerceAtLeast(0) + audioFrames * 1_000_000 / CaptureAudio.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) { audioEnded = true; if (finishing && videoEnded) close(null) }
    }

    /// Finalize whatever exists. Runs once, on the media thread.
    private fun close(error: String?) {
        if (closed) return
        // Sound that never produced a format must not cost the picture already encoded.
        if (!muxing && error == null && waiting.any { it.video }) { begin(withoutAudio = true); if (closed) return }
        closed = true
        handler.removeCallbacksAndMessages(null)
        // One line per segment: enough to tell an encoder that fell behind from a clock fault.
        android.util.Log.w("YouRec", "segment ${file.name}: $videoWritten frames to ${lastVideo / 1000} ms (ended $videoEnded), sound to ${lastAudio / 1000} ms (ended $audioEnded)" + (error?.let { ", $it" } ?: ""))
        var failure = error
        if (muxing && lastVideo >= 0) {
            try { muxer.stop(); playable = true }
            catch (e: Exception) { failure = failure ?: "The recording could not be finalized. Free some storage and try again." }
        } else { empty = true; failure = failure ?: "No video was captured." }
        try { muxer.release() } catch (e: Exception) { }
        try { video.stop() } catch (e: Exception) { }
        try { video.release() } catch (e: Exception) { }
        try { audio?.stop() } catch (e: Exception) { }
        try { audio?.release() } catch (e: Exception) { }
        surface.release()
        if (empty) { file.delete(); sidecar(file).delete() }
        done.countDown()
        try { onFinish(this, failure) } finally { thread.quitSafely() }
    }

    companion object {
        /// Capture marks live beside the movie until the project indexes them.
        fun sidecar(movie: File) = File(movie.parentFile, movie.nameWithoutExtension + ".events.json")
    }
}
