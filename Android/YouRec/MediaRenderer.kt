package yourec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/// Sources shared by one preview or one export: at most a few open decoders,
/// reused across cuts, closed together.
class RenderSources(private val project: Project, private val folder: File, private val strict: Boolean = false) {
    private val failed = HashSet<UUID>()
    private val frames = LinkedHashMap<UUID, FrameReader>(4, 0.75f, true)
    private val sound = HashMap<UUID, DecodedAudio>()
    /// Two edits of the same originals can share open decoders.
    val key: Any = folder.path to project.sources.map { it.id to it.filename }
    fun file(source: UUID): File? = project.sources.firstOrNull { it.id == source }?.let { File(folder, it.filename) }
    /// GL thread. In the preview a source that cannot be decoded shows black; an
    /// export (strict) fails instead of publishing a movie with missing pictures.
    fun frames(source: UUID): FrameReader? {
        frames[source]?.let { return it }
        if (source in failed) return null
        val file = file(source) ?: return null
        // Hardware decoders are a scarce system resource: keep three at most.
        while (frames.size >= 3) { val oldest = frames.keys.first(); frames.remove(oldest)?.release() }
        val reader = try { FrameReader(file) } catch (e: Exception) {
            if (strict) throw ProjectError.InvalidMedia()
            // Remember the failure: retrying on every frame would stall the preview.
            failed.add(source); return null
        }
        frames[source] = reader
        return reader
    }
    /// Close one reader whose decoder failed or was reclaimed; the next frame reopens it.
    fun drop(source: UUID) { try { frames.remove(source)?.release() } catch (e: Exception) { } }
    /// Audio thread.
    fun sound(source: UUID): PCMSource? = sound.getOrPut(source) { DecodedAudio(file(source) ?: return null) }
    fun releaseFrames() { for (reader in frames.values) try { reader.release() } catch (e: Exception) { }; frames.clear(); failed.clear() }
    fun releaseSound() { for (reader in sound.values) reader.release(); sound.clear() }

    /// The project picture size: a fixed shape if one was chosen, otherwise
    /// the displayed size of the reference take.
    fun canvas(edit: EditState): CanvasSize {
        edit.outputSize?.let { return it }
        val video = edit.canvasSourceID?.let { file(it) }?.let { MediaFiles.video(it) }
        return if (video != null) CanvasSize(video.displayWidth.toDouble(), video.displayHeight.toDouble()) else CanvasSize(1920.0, 1080.0)
    }
}

/// Render the current edit to a new movie. The export works from a copy of the
/// project taken when it starts, writes to a temporary file, and publishes it
/// under a new name only after success. Sources, earlier exports and the
/// editable project are never written.
class MovieExport(project: Project, private val folder: File, private val exports: File,
                  private val progress: (Double) -> Unit, private val completion: (ExportReceipt?, String?) -> Unit) {
    private val project = project.copy()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private val thread = Thread({ run() }, "yourec-export")
    private class Cancelled : Exception()

    fun start() { thread.start() }
    fun cancel() { cancelled = true }

    private fun run() {
        val temp = File(exports, ".exporting-" + UUID.randomUUID().text + ".mp4")
        var receipt: ExportReceipt? = null; var failure: String? = null
        try {
            exports.mkdirs()
            receipt = render(temp)
        } catch (e: Cancelled) {
        } catch (e: ProjectError) { failure = e.message
        } catch (e: Exception) { failure = "The export could not be completed. " + (e.message ?: e.javaClass.simpleName) + " Your project and recordings are unchanged."
        } finally { temp.delete() }
        main.post { completion(receipt, failure) }
    }

    private fun render(temp: File): ExportReceipt {
        val edit = project.edit
        val plan = RenderPlan(edit)
        if (plan.duration <= EditTime.zero) throw ProjectError.EmptyTimeline()
        if (project.sources.any { it.id in plan.sourcesInUse && !File(folder, it.filename).exists() }) throw ProjectError.MissingMedia()
        val sources = RenderSources(project, folder, strict = true)
        val settings = project.exportSettings
        val size = settings.resolution.size(sources.canvas(edit))
        val width = RenderPlan.even(size.width); val height = RenderPlan.even(size.height)
        val fps = settings.frameRate.value
        val report = MediaProgress { value -> main.post { progress(value) } }
        report.report(0.0)

        var egl: EGLCore? = null; var window = EGL14.EGL_NO_SURFACE; var program: FrameProgram? = null
        var videoEncoder: MediaCodec? = null; var audioEncoder: MediaCodec? = null; var muxer: MediaMuxer? = null
        var input: android.view.Surface? = null
        try {
            val videoFormat = MediaFormat.createVideoFormat(settings.codec.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // About 12 Mbit/s at 1080p30 for H.264; HEVC needs roughly two thirds of that.
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps * (if (settings.codec == ExportSettings.Codec.HEVC) 0.13 else 0.19)).toInt().coerceIn(1_000_000, 80_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(settings.codec.mime).apply { configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            input = videoEncoder.createInputSurface()
            videoEncoder.start()
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMix.sampleRate, AudioMix.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, blockFrames * AudioMix.channels * 2)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            muxer = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val writer = TrackWriter(muxer)
            egl = EGLCore(); window = egl.windowSurface(input); egl.makeCurrent(window); program = FrameProgram()

            val mix = AudioMix(plan.timeline) { sources.sound(it) }
            val totalFrames = plan.frameCount(fps); val totalAudio = AudioMix.frame(plan.duration)
            val block = FloatArray(blockFrames * AudioMix.channels)
            var audioFed = 0L
            for (n in 0 until totalFrames) {
                if (cancelled) throw Cancelled()
                // Frame n shows the edit at n / fps, whatever the sources' own cadence.
                val time = EditTime.seconds(n.toDouble() / fps)
                val t0 = System.nanoTime()
                FrameProgram.clear(width, height)
                val picture = plan.picture(time)
                val reader = picture?.let { sources.frames(it.source) }
                if (picture != null && reader != null) {
                    // A take that yields no picture must not become a black movie.
                    if (!reader.show(picture.sourceTime)) throw ProjectError.InvalidMedia()
                    val fit = RenderPlan.fit(reader.video.displayWidth, reader.video.displayHeight, width, height)
                    program.draw(reader.texture, reader.matrix, fit.scaleX, fit.scaleY)
                }
                val t1 = System.nanoTime()
                egl.setPresentationTime(window, n * 1_000_000_000L / fps)
                egl.swap(window)
                writer.drain(videoEncoder, video = true, end = false)
                val t2 = System.nanoTime()
                // Keep sound just ahead of picture so the muxer can interleave them.
                val audioTarget = minOf(totalAudio, (n + 1) * AudioMix.sampleRate.toLong() / fps + blockFrames)
                while (audioFed < audioTarget) {
                    val count = minOf(blockFrames.toLong(), totalAudio - audioFed).toInt()
                    mix.render(audioFed, block, count)
                    feed(audioEncoder, writer, block, count, audioFed, end = false)
                    audioFed += count
                }
                // A frame that takes a second names the stage that held it up.
                val t3 = System.nanoTime()
                if (t3 - t0 > 1_000_000_000L) android.util.Log.w("YouRec", "export frame $n of $totalFrames: picture ${(t1 - t0) / 1_000_000} ms, encode ${(t2 - t1) / 1_000_000} ms, sound ${(t3 - t2) / 1_000_000} ms")
                report.report((n + 1).toDouble() / totalFrames * 0.99)
            }
            while (audioFed < totalAudio) {
                val count = minOf(blockFrames.toLong(), totalAudio - audioFed).toInt()
                mix.render(audioFed, block, count); feed(audioEncoder, writer, block, count, audioFed, end = false); audioFed += count
            }
            feed(audioEncoder, writer, block, 0, audioFed, end = true)
            videoEncoder.signalEndOfInputStream()
            writer.drain(videoEncoder, video = true, end = true)
            writer.drain(audioEncoder, video = false, end = true)
            writer.finish()
            muxer.stop(); muxer.release(); muxer = null

            // Publish under a name that has never been used; an export is never overwritten.
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var name = "YouRec-$stamp.mp4"; var attempt = 1
            while (File(exports, name).exists()) name = "YouRec-$stamp-${++attempt}.mp4"
            RecoveryFile.move(temp, File(exports, name))
            val result = ExportReceipt(filename = name, projectID = project.id, projectTitle = project.title, revision = project.revision,
                sourceChecksums = project.sources.filter { it.id in plan.sourcesInUse }.mapNotNull { source -> source.sha256?.let { source.filename to it } }.toMap(),
                width = width, height = height, framesPerSecond = fps, codec = settings.codec, resolution = settings.resolution, audioPeak = mix.peak.toDouble())
            RecoveryFile.write(JSON.write(ProjectCoding.encode(result)), File(exports, name.removeSuffix(".mp4") + ".json"))
            report.report(1.0)
            return result
        } finally {
            try { muxer?.release() } catch (e: Exception) { }
            try { videoEncoder?.stop() } catch (e: Exception) { }
            videoEncoder?.release()
            try { audioEncoder?.stop() } catch (e: Exception) { }
            audioEncoder?.release()
            sources.releaseFrames(); sources.releaseSound()
            program?.release()
            egl?.let { it.release(window); it.release() }
            input?.release()
        }
    }

    private fun feed(encoder: MediaCodec, writer: TrackWriter, block: FloatArray, frames: Int, position: Long, end: Boolean) {
        while (true) {
            if (cancelled) throw Cancelled()
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) { writer.drain(encoder, video = false, end = false); continue }
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.clear()
            // The encoder takes 16-bit samples. Peaks above full scale are clipped here and reported in the receipt.
            for (i in 0 until frames * AudioMix.channels) buffer.putShort((block[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            encoder.queueInputBuffer(index, 0, frames * AudioMix.channels * 2, position * 1_000_000 / AudioMix.sampleRate, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            writer.drain(encoder, video = false, end = false)
            return
        }
    }

    /// The muxer can start only when both encoders have announced their formats,
    /// which happens after they have produced data. Early samples wait in memory.
    private inner class TrackWriter(private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private val tracks = intArrayOf(-1, -1)
        private val early = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()
        private var started = false
        private val last = longArrayOf(-1, -1)
        private val ended = booleanArrayOf(false, false)

        fun drain(encoder: MediaCodec, video: Boolean, end: Boolean) {
            val slot = if (video) 0 else 1
            // The end-of-stream buffer can arrive during an ordinary drain. An
            // encoder says nothing more after it, so never wait for it twice.
            if (ended[slot]) return
            var idle = 0
            while (true) {
                if (cancelled) throw Cancelled()
                val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return
                    if (++idle > 1000) throw IllegalStateException("The encoder stopped responding.")
                    continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    tracks[slot] = muxer.addTrack(encoder.outputFormat)
                    if (tracks[0] >= 0 && tracks[1] >= 0) { muxer.start(); started = true; flush() }
                    continue
                }
                if (index < 0) continue
                idle = 0
                val data = encoder.getOutputBuffer(index)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    // A muxer rejects timestamps that go backwards within a track.
                    if (info.presentationTimeUs <= last[slot]) info.presentationTimeUs = last[slot] + 1
                    last[slot] = info.presentationTimeUs
                    data.position(info.offset); data.limit(info.offset + info.size)
                    if (started) muxer.writeSampleData(tracks[slot], data, info)
                    else {
                        val copy = ByteBuffer.allocate(info.size); copy.put(data); copy.flip()
                        early.add(Triple(slot, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }))
                    }
                }
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (done) { ended[slot] = true; return }
            }
        }
        private fun flush() { for ((slot, data, details) in early) muxer.writeSampleData(tracks[slot], data, details); early.clear() }
        fun finish() { if (!started) throw IllegalStateException("The encoders produced no output.") }
    }

    private companion object { const val blockFrames = 1024 }
}

/// Finished movies of one project, newest first, read from their receipts.
object Exports {
    fun list(exports: File): List<ExportReceipt> = (exports.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { file ->
        try { ProjectCoding.receipt(Fields.of(JSON.parse(file.readBytes()))).takeIf { File(exports, it.filename).exists() } } catch (e: Exception) { null }
    }.sortedByDescending { it.createdAt }
    /// An unchanged completed edit offers sharing instead of a duplicate export.
    fun matching(project: Project, exports: File): ExportReceipt? {
        if (project.deferredHistory != null) return null
        val settings = project.exportSettings
        return list(exports).firstOrNull { it.projectID == project.id && it.revision == project.revision && it.codec == settings.codec && it.resolution == settings.resolution && it.framesPerSecond == settings.frameRate.value }
    }
    /// Leftovers of an export that was interrupted. Only our own working files.
    fun clean(exports: File) { for (file in exports.listFiles() ?: emptyArray()) if (file.name.startsWith(".exporting-")) file.delete() }
}
