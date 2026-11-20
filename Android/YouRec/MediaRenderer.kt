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
    private val titles = TitleTextures()
    private val pictures = ImageTextures(folder)
    private val looks = HashMap<Any, IntArray>()   // look or LUT file -> texture, dimension, columns, rows
    private val domains = HashMap<Any, Pair<FloatArray, FloatArray>>()
    private val frames = LinkedHashMap<Pair<UUID, Int>, FrameReader>(4, 0.75f, true)
    private val sound = HashMap<UUID, DecodedAudio>()
    /// Two edits of the same originals can share open decoders.
    val key: Any = folder.path to project.sources.map { it.id to it.filename }
    fun file(source: UUID): File? = project.sources.firstOrNull { it.id == source }?.let { File(folder, it.filename) }
    /// GL thread. In the preview a source that cannot be decoded shows black; an
    /// export (strict) fails instead of publishing a movie with missing pictures.
    fun frames(source: UUID, slot: Int = 0): FrameReader? {
        val key = source to slot
        frames[key]?.let { return it }
        if (source in failed) return null
        val file = file(source) ?: return null
        // Hardware decoders are a scarce system resource: keep four at most.
        while (frames.size >= 4) { val oldest = frames.keys.first(); frames.remove(oldest)?.release() }
        val reader = try { FrameReader(file) } catch (e: Exception) {
            if (strict) throw ProjectError.InvalidMedia()
            // Remember the failure: retrying on every frame would stall the preview.
            failed.add(source); return null
        }
        frames[key] = reader
        return reader
    }
    /// Close the readers of a source whose decoder failed or was reclaimed; the next frame reopens them.
    fun drop(source: UUID) { for (key in frames.keys.filter { it.first == source }) try { frames.remove(key)?.release() } catch (e: Exception) { } }
    /// GL thread. The look of a grade as a texture, made once per look or LUT file.
    /// An imported LUT that cannot be read fails an export; the preview shows the
    /// picture with its balance only.
    private fun grade(color: ColorSettings?): FrameGrade? {
        if (color == null || color.isIdentity) return null
        val key: Any? = color.imported?.filename ?: color.look.takeIf { it != ColorLook.Natural }
        var entry = key?.let { looks[it] }
        if (key != null && entry == null) {
            val cube = try { color.imported?.let { ColorCube.load(it, folder) } ?: ColorCube.of(color.look) } catch (e: Exception) {
                if (strict) throw ProjectError.InvalidMedia()
                android.util.Log.w("YouRec", "color: ${color.imported?.filename} cannot be read", e); null
            }
            entry = IntArray(4)
            if (cube != null) {
                val atlas = cube.atlas()
                val ids = IntArray(1); android.opengl.GLES20.glGenTextures(1, ids, 0)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, ids[0])
                for ((name, value) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE))
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, name, value)
                android.opengl.GLES20.glPixelStorei(android.opengl.GLES20.GL_UNPACK_ALIGNMENT, 1)
                android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA, atlas.width, atlas.height, 0,
                    android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(atlas.rgba))
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, 0)
                entry = intArrayOf(ids[0], atlas.dimension, atlas.columns, atlas.rows)
                domains[key] = FloatArray(3) { cube.domainMin[it].toFloat() } to FloatArray(3) { (cube.domainMax[it] - cube.domainMin[it]).toFloat() }
            }
            looks[key] = entry
        }
        val domain = key?.let { domains[it] }
        return FrameGrade(entry?.get(0) ?: 0, entry?.get(1) ?: 2, entry?.get(2) ?: 1, entry?.get(3) ?: 1, color.amount.toFloat(), color.gains,
            domain?.first ?: floatArrayOf(0f, 0f, 0f), domain?.second ?: floatArrayOf(1f, 1f, 1f))
    }
    private fun releaseLooks() {
        val ids = looks.values.map { it[0] }.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) android.opengl.GLES20.glDeleteTextures(ids.size, ids, 0)
        looks.clear(); domains.clear()
    }

    /// Draw one moment of the edit into the current surface, already cleared to
    /// black. Preview and export both come through here. False if a picture
    /// that should be there could not be shown.
    fun draw(layers: List<RenderPlan.Layer>, program: FrameProgram, width: Int, height: Int, transition: RenderPlan.TransitionFrame? = null,
             framesPerSecond: Double = 30.0): Boolean {
        if (transition != null && layers.none { it.placement != null }) return drawTransition(transition, program, width, height, framesPerSecond)
        var complete = true
        for (layer in layers) complete = drawLayer(layer, program, width, height) && complete
        return complete
    }

    /// One picture with its grade, images and titles. False if it could not be shown.
    private fun drawLayer(layer: RenderPlan.Layer, program: FrameProgram, width: Int, height: Int, overlays: Boolean = true): Boolean {
        val frame = CanvasSize(width.toDouble(), height.toDouble())
        val reader = frames(layer.picture.source, layer.slot)
        if (reader == null || !reader.show(layer.picture.sourceTime)) return false
        val source = CanvasSize(reader.video.displayWidth.toDouble(), reader.video.displayHeight.toDouble())
        var placed = layer.picture.clip.pictureTransform(source, frame)
        val placement = layer.placement
        val tone = grade(layer.picture.clip.color)
        if (placement == null) {
            program.draw(reader.texture, reader.matrix, placed, source, frame, tone)
            // Images and titles are placed on the finished frame, so a mirrored picture keeps them readable.
            if (overlays) drawOverlays(layer, width, height, null)
            return true
        }
        // Within its window a picture is a small frame of its own: black behind it, nothing outside it.
        val visible = placement.visibleWindow(frame)
        val left = Math.round(maxOf(0.0, visible.x)).toInt(); val right = Math.round(minOf(frame.width, visible.x + visible.width)).toInt()
        val top = Math.round(maxOf(0.0, visible.y)).toInt(); val bottom = Math.round(minOf(frame.height, visible.y + visible.height)).toInt()
        if (right <= left || bottom <= top) return true
        placed = placed.then(placement.transform(frame))
        android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
        android.opengl.GLES20.glScissor(left, height - bottom, right - left, bottom - top)
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f); android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
        program.draw(reader.texture, reader.matrix, placed, source, frame, tone)
        // A picture in a window carries its titles into that window with it.
        drawOverlays(layer, width, height, placement.transform(frame))
        android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
        return true
    }
    private fun drawOverlays(layer: RenderPlan.Layer, width: Int, height: Int, placement: Affine?) {
        for (image in layer.picture.clip.visibleImages(layer.picture.sourceTime)) pictures.draw(image, width, height, placement)
        for (title in layer.picture.clip.visibleTexts(layer.picture.sourceTime)) titles.draw(title, width, height, placement)
    }

    // Each partner is drawn whole into a texture of its own, then the two are
    // mixed: the transition sees the finished pictures, titles included.
    private var compositor: TransitionCompositor? = null
    private fun drawTransition(frame: RenderPlan.TransitionFrame, program: FrameProgram, width: Int, height: Int, framesPerSecond: Double): Boolean {
        val mixer = compositor ?: TransitionCompositor().also { compositor = it }
        val bound = IntArray(1); android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        // A split screen shows each picture's own titles over the pair, not cropped with it.
        val split = frame.settings.style == TransitionStyle.Split
        var complete = true
        for ((index, layer) in listOf(frame.first, frame.second).withIndex()) {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, mixer.target(index, width, height))
            android.opengl.GLES20.glViewport(0, 0, width, height)
            // Where the transition meets the edge of the edit, its colour stands in for the missing picture.
            val fill = if (layer == null) frame.fill else TransitionColor.black
            android.opengl.GLES20.glClearColor(fill.red.toFloat(), fill.green.toFloat(), fill.blue.toFloat(), 1f)
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
            if (layer != null) complete = drawLayer(layer, program, width, height, overlays = !split) && complete
        }
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, bound[0])
        mixer.composite(frame, width, height, framesPerSecond)
        if (split) for (layer in listOfNotNull(frame.first, frame.second)) drawOverlays(layer, width, height, null)
        return complete
    }
    /// Audio thread.
    fun sound(source: UUID): PCMSource? = sound.getOrPut(source) { DecodedAudio(file(source) ?: return null) }

    private val processedSound = HashMap<String, ProcessedAudio>()
    /// Where one source through one chain is kept, named by both. A changed
    /// recording or chain gets a new name; the old result is simply unused.
    private fun processedFile(source: UUID, effects: List<AudioEffect>): File? {
        val file = file(source) ?: return null
        return File(OfflineAudioPipeline.cacheFolder, OfflineAudioPipeline.key(file.name + "|" + file.length() + "|" + file.lastModified(), effects) + ".f32")
    }
    /// The source as processed by `effects`, once prepared. An export never
    /// plays the original in place of a chain it was asked for.
    fun processed(source: UUID, effects: List<AudioEffect>): PCMSource? = synchronized(processedSound) {
        val file = processedFile(source, effects)
        if (file == null || !file.exists()) { if (strict) throw AudioProcessingError.UnavailableModel(); return null }
        processedSound.getOrPut(file.name) { ProcessedAudio(file) }
    }
    /// Whether every chain of `timeline` is already processed.
    fun audioPrepared(timeline: TrackTimeline) = AudioEnhancements.unavailable(timeline) == null &&
        AudioEnhancements.requests(timeline).all { processedFile(it.source, it.effects)?.exists() != false }
    /// Process every chain of `timeline` not yet processed. Slow: run it off the
    /// main thread and the playback thread. Throws for a stage this build cannot process.
    fun prepareAudio(timeline: TrackTimeline, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        AudioEnhancements.unavailable(timeline)?.let { throw AudioProcessingError.UnavailableModel() }
        val requests = AudioEnhancements.requests(timeline).filter { processedFile(it.source, it.effects)?.exists() == false }
        for ((index, request) in requests.withIndex()) {
            val source = project.sources.firstOrNull { it.id == request.source } ?: throw ProjectError.MissingMedia()
            val file = File(folder, source.filename)
            if (!file.exists()) throw ProjectError.MissingMedia()
            val active = OfflineAudioPipeline.active(request.effects)
            if (active.any { it.kind == EffectKind.voiceIsolation && it.model == AudioModel.rnnoise } && !SpeechDetector.available) throw AudioProcessingError.UnavailableModel()
            // One DeepFilterNet3 network serves every channel of every DeepFilterNet3 stage of this chain.
            val network = if (active.any { it.kind == EffectKind.voiceIsolation && it.model == AudioModel.deepFilter }) NeuralAssets.deepFilter() else null
            val studio = try { StudioAssets.load(active) } catch (e: Exception) { network?.second?.close(); throw e }
            val decoded = DecodedAudio(file)
            try {
                OfflineAudioPipeline.write(decoded, Math.ceil(source.duration * AudioMix.sampleRate).toLong(), request.effects, { RNNoiseDenoiser() },
                    processedFile(request.source, request.effects)!!, { progress((index + it) / requests.size) }, cancelled,
                    network?.let { (dsp, model) -> { DeepFilterStream(dsp, model) } }, studio)
            } finally { decoded.release(); network?.second?.close(); studio?.lava?.close(); studio?.voiceFixer?.close() }
        }
        progress(1.0)
    }
    fun releaseFrames() { for (reader in frames.values) try { reader.release() } catch (e: Exception) { }; frames.clear(); failed.clear(); titles.release(); pictures.release(); releaseLooks()
        compositor?.releaseProgram(); compositor = null }
    fun releaseSound() { for (reader in sound.values) reader.release(); sound.clear()
        synchronized(processedSound) { for (reader in processedSound.values) reader.close(); processedSound.clear() } }

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
        } catch (e: AudioProcessingError) { failure = e.message
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
        // Enhanced sound is processed over whole sources first: the first fifth of the bar.
        val share = if (sources.audioPrepared(edit.timeline)) 0.0 else 0.2
        try { sources.prepareAudio(edit.timeline, { report.report(it * share) }, { cancelled }) }
        catch (e: AudioProcessingError.Cancelled) { throw Cancelled() }

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

            val mix = AudioMix(plan.timeline, { id, effects -> sources.processed(id, effects) }) { sources.sound(it) }
            val totalFrames = plan.frameCount(fps); val totalAudio = AudioMix.frame(plan.duration)
            val block = FloatArray(blockFrames * AudioMix.channels)
            var audioFed = 0L
            for (n in 0 until totalFrames) {
                if (cancelled) throw Cancelled()
                // Frame n shows the edit at n / fps, whatever the sources' own cadence.
                val time = EditTime.seconds(n.toDouble() / fps)
                val t0 = System.nanoTime()
                FrameProgram.clear(width, height)
                // A take that yields no picture must not become a black movie.
                if (!sources.draw(plan.layers(time), program, width, height, plan.transition(time), fps.toDouble())) throw ProjectError.InvalidMedia()
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
                report.report(share + (n + 1).toDouble() / totalFrames * (0.99 - share))
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
