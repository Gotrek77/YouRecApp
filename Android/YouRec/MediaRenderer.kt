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
        // Hardware decoders are a scarce system resource: keep six at most.
        while (frames.size >= 6) { val oldest = frames.keys.first(); frames.remove(oldest)?.release() }
        val reader = try { FrameReader(file) } catch (e: Exception) {
            // A decoder that will not start is usually out of codec memory, not a bad
            // file: close the ones held here and try once more before giving up.
            android.util.Log.w("YouRec", "decoder for ${file.name} did not start with ${frames.size} open: ${e.message}")
            if (frames.isNotEmpty()) {
                for (open in frames.values) try { open.release() } catch (ignored: Exception) { }
                frames.clear()
                try { FrameReader(file) } catch (again: Exception) { null }
            } else null
        } ?: run {
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
        for (layer in layers) complete = drawLayer(layer, program, width, height, framesPerSecond = framesPerSecond) && complete
        return complete
    }

    /// Subtitles, over everything else and inside the recorded frame.
    fun drawCaptions(captions: List<TextOverlay>, width: Int, height: Int) { for (caption in captions) titles.draw(caption, width, height, null) }

    /// One picture with its grade, images and titles. False if it could not be shown.
    private fun drawLayer(layer: RenderPlan.Layer, program: FrameProgram, width: Int, height: Int, overlays: Boolean = true, framesPerSecond: Double = 30.0): Boolean {
        val frame = CanvasSize(width.toDouble(), height.toDouble())
        // Quantization's palette track advances before this picture's decoder is taken.
        analysePalettes(layer, program, width, height, framesPerSecond)
        val reader = frames(layer.picture.source, layer.slot) ?: return false
        val clip = layer.picture.clip
        // Time effects choose which source frames to show; the others show the frame at the time.
        val planned = VideoEffectSamplePlan.needed(clip)
        if (!planned && !reader.show(layer.picture.sourceTime)) return false
        val source = CanvasSize(reader.video.displayWidth.toDouble(), reader.video.displayHeight.toDouble())
        var placed = clip.pictureTransform(source, frame)
        val placement = layer.placement
        val tone = grade(clip.color)
        // Effects see the graded, framed picture at the canvas size, before it is
        // placed in a window and before its images and titles, as on the iPhone.
        if (planned || VideoEffectRendering.active(clip, layer.picture.sourceTime, framesPerSecond).isNotEmpty() || VideoEffectRendering.nightFix(clip, layer.picture.sourceTime) != null) {
            val shader = effectShader ?: VideoEffectShader().also { effectShader = it }
            val bound = IntArray(1); android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            val scissor = android.opengl.GLES20.glIsEnabled(android.opengl.GLES20.GL_SCISSOR_TEST)
            if (scissor) android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
            shader.reset(width, height)
            val result = effected(layer, reader, shader, program, placed, source, frame, tone, framesPerSecond)?.texture
            if (result == null) { android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, bound[0]); if (scissor) android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST); return false }
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, bound[0])
            android.opengl.GLES20.glViewport(0, 0, width, height)
            if (scissor) android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
            if (placement == null) {
                shader.draw(result, width, height, null)
                if (overlays) drawOverlays(layer, width, height, null)
                return true
            }
            val visible = placement.visibleWindow(frame)
            val left = Math.round(maxOf(0.0, visible.x)).toInt(); val right = Math.round(minOf(frame.width, visible.x + visible.width)).toInt()
            val top = Math.round(maxOf(0.0, visible.y)).toInt(); val bottom = Math.round(minOf(frame.height, visible.y + visible.height)).toInt()
            if (right <= left || bottom <= top) return true
            android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
            android.opengl.GLES20.glScissor(left, height - bottom, right - left, bottom - top)
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f); android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
            shader.draw(result, width, height, placement.transform(frame))
            drawOverlays(layer, width, height, placement.transform(frame))
            android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
            return true
        }
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
    /// One Quantization's palette track, made as the iPhone app's
    /// EffectPaletteAnalysis.palette: a frame every 0.2 s of the effect's part
    /// of the clip (and at each key of its colours), through the effects before
    /// it, at most 256 pixels on the long side, each palette eased from the last.
    private class PaletteJob(val targets: List<Double>, val start: Double, val end: Double, val file: File?) {
        val frames = ArrayList<EffectPaletteFrame>(); var index = 0; var previous: EffectPalette? = null; var wasLocked = false
        val done: Boolean get() = index >= targets.size
        var stored: EffectPaletteTrack? = null
        fun track(time: Double): EffectPalette? = stored?.palette(time)
    }
    private val paletteJobs = HashMap<String, PaletteJob>()
    private fun paletteKey(layer: RenderPlan.Layer, effect: VideoEffectSettings, width: Int, height: Int): String {
        val clip = layer.picture.clip; val file = file(layer.picture.source)
        val prefix = (clip.videoEffects ?: emptyList()).let { VideoEffectSettings.ordered(it) }.takeWhile { it.id != effect.id }
        val identity = listOf("palette-android-v1", file?.name, file?.length(), file?.lastModified(), effect.start, effect.end, clip.start, clip.end,
            effect.parameters.filter { it.id == "colors" || it.id == "lockPalette" }.toString(), prefix.map { String(JSON.write(ProjectCoding.encode(it))) },
            clip.color, clip.crop, clip.mirror, width, height).toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { String.format("%02x", it) }
    }
    private fun analysePalettes(layer: RenderPlan.Layer, program: FrameProgram, width: Int, height: Int, fps: Double) {
        val clip = layer.picture.clip
        val effects = clip.videoEffects?.filter { it.enabled && it.kind == VideoEffectID.quantization && it.start < clip.end && it.end > clip.start } ?: return
        for (effect in effects) {
            val key = paletteKey(layer, effect, width, height)
            val job = paletteJobs.getOrPut(key) {
                val start = maxOf(clip.start, effect.start); val end = minOf(clip.end, effect.end)
                val keys = effect.parameters.filter { it.id == "colors" || it.id == "lockPalette" }.flatMap { p -> p.keyframes.map { it.time } }.filter { it >= start && it < end }
                val targets = (generateSequence(start) { it + 0.2 }.takeWhile { it < end }.toList() + keys).toSortedSet().toList()
                PaletteJob(targets, start, end, file(layer.picture.source)).also { job ->
                    // A track made before, kept in the app's cache.
                    VideoEffectAnalysis.cacheFolder?.let { File(it, "palette-$key.json") }?.takeIf { it.exists() }?.let { stored ->
                        try { job.stored = decodePalettes(JSON.parse(stored.readBytes())).takeIf { it.valid }; if (job.stored != null) job.index = targets.size } catch (e: Exception) { stored.delete() }
                    }
                }
            }
            if (job.done) continue
            val bound = IntArray(1); android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            val scissor = android.opengl.GLES20.glIsEnabled(android.opengl.GLES20.GL_SCISSOR_TEST)
            if (scissor) android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_SCISSOR_TEST)
            val shader = effectShader ?: VideoEffectShader().also { effectShader = it }
            // An export makes the whole track first; the preview a few frames each time it draws.
            val began = System.nanoTime()
            while (!job.done && (strict || System.nanoTime() - began < 25_000_000L)) paletteStep(layer, effect, job, shader, program, width, height, fps)
            if (job.done) {
                job.stored = EffectPaletteTrack(job.frames.toList()).takeIf { it.valid }
                job.stored?.let { track -> VideoEffectAnalysis.cacheFolder?.let { folder -> folder.mkdirs(); File(folder, "palette-$key.json").writeBytes(JSON.write(encodePalettes(track))) } }
            }
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, bound[0])
            android.opengl.GLES20.glViewport(0, 0, width, height)
            if (scissor) android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_SCISSOR_TEST)
        }
    }
    private fun paletteStep(layer: RenderPlan.Layer, effect: VideoEffectSettings, job: PaletteJob, shader: VideoEffectShader, program: FrameProgram, width: Int, height: Int, fps: Double) {
        val clip = layer.picture.clip
        val target = job.targets[job.index]
        val index = job.file?.let { MediaFiles.frameIndex(it) }
        // The first source frame at or after the target; one frame can serve several targets.
        val selected = index?.let { it.times[minOf(it.times.size - 1, it.first(target))] } ?: target
        val time = maxOf(job.start, minOf(selected, job.end - 0.00001))
        while (job.index < job.targets.size && time + 0.00001 >= job.targets[job.index]) job.index++
        if (job.index == 0) job.index = 1
        val locked = effect.toggle("lockPalette", time); val count = effect.number("colors", time).toInt()
        if (locked && job.wasLocked && job.previous?.colors?.size == count) return
        job.wasLocked = locked
        if (job.frames.isNotEmpty() && time <= job.frames.last().time) return
        val reader = frames(layer.picture.source, 6) ?: run { job.index = job.targets.size; return }
        if (!reader.show(time)) return
        val scale = minOf(1.0, 256.0 / maxOf(width, height)); val w = maxOf(2, Math.round(width * scale).toInt()); val h = maxOf(2, Math.round(height * scale).toInt())
        val source = CanvasSize(reader.video.displayWidth.toDouble(), reader.video.displayHeight.toDouble()); val canvas = CanvasSize(w.toDouble(), h.toDouble())
        shader.reset(width, height)
        val picture = shader.picture(w, h)
        drawSource(clip, time, reader, clip.pictureTransform(source, canvas), source, canvas, grade(clip.color), shader, program)
        val prefix = VideoEffectRendering.active(clip, time, fps).takeWhile { it.id != effect.id }
        val placed = clip.pictureTransform(source, canvas)
        val result = shader.apply(prefix, time, fps, picture, person = { personMask(layer.picture.source, reader, time, placed, source, program, shader, w, h) })
        val palette = EffectPaletteAnalysis.analyze(shader.samples(result), count, job.previous, job.frames.lastOrNull()?.let { time - it.time } ?: 0.2,
            VideoEffectRendering.frameIndex(time, 30.0).toLong())
        shader.free(result)
        job.frames.add(EffectPaletteFrame(time, palette)); job.previous = palette
    }
    private fun encodePalettes(track: EffectPaletteTrack) = jsonObject("frames" to track.frames.map { f ->
        jsonObject("time" to f.time, "palette" to jsonObject("colors" to f.palette.colors.map { jsonObject("red" to it.red, "green" to it.green, "blue" to it.blue) }, "thresholds" to f.palette.thresholds))
    })
    private fun decodePalettes(value: Any?) = EffectPaletteTrack(Fields.of(value).list("frames").map { raw ->
        val f = Fields.of(raw)
        EffectPaletteFrame(f.double("time"), EffectPalettes.locked(f.raw("palette")) ?: throw ProjectError.InvalidDocument())
    })
    /// Raw mattes already made, by stream (source, clip, the effects up to the
    /// temporal one, canvas) and frame time: sequential frames find their
    /// history here; after a seek, missing frames are made again.
    private val mattes = LinkedHashMap<String, java.util.TreeMap<Double, VideoEffectShader.Matte>>(4, 0.75f, true)
    private fun matteStream(layer: RenderPlan.Layer, effect: VideoEffectSettings, width: Int, height: Int): String {
        val clip = layer.picture.clip; val file = file(layer.picture.source)
        val upstream = VideoEffectSettings.ordered(clip.videoEffects ?: emptyList()).let { all -> all.take(all.indexOfFirst { it.id == effect.id } + 1) }
        return listOf("matte-android-v1", file?.name, file?.length(), clip.id, clip.color, clip.crop, clip.mirror, width, height,
            upstream.map { String(JSON.write(ProjectCoding.encode(it))) }).toString()
    }
    /// The frame where an effect's matte history begins: its start, or the first
    /// frame after its intensity last came up from zero.
    private fun matteOrigin(effect: VideoEffectSettings, t: Double, frames: VideoEffectFrameIndex): Double? {
        if (!effect.isActive(t)) return null
        val zero = effect.parameters.firstOrNull { it.id == "intensity" }?.keyframes?.lastOrNull { it.time <= t && it.time >= effect.start && it.value == EffectValue.Number(0.0) }?.time ?: return effect.start
        var index = frames.first(zero)
        while (index < frames.times.size && frames.times[index] <= t) {
            if (frames.times[index] > zero && effect.isActive(frames.times[index])) return frames.times[index]
            index++
        }
        return null
    }
    private fun matteHistory(layer: RenderPlan.Layer, effect: VideoEffectSettings, time: Double, own: VideoEffectShader.Matte, shader: VideoEffectShader,
                             program: FrameProgram, width: Int, height: Int, fps: Double): List<VideoEffectShader.Matte> {
        val file = file(layer.picture.source) ?: return emptyList()
        val frames = MediaFiles.frameIndex(file) ?: return emptyList()
        val now = frames.times[frames.last(time)]
        val key = matteStream(layer, effect, width, height)
        val stream = mattes.getOrPut(key) { java.util.TreeMap() }
        // Kept for the frames after this one.
        if (stream[now] == null) { shader.pin(own.target, true); stream[now] = own; own.target.busy = true }
        val kind = VideoEffectMatteKind.of(effect.kind)
        val start = matteOrigin(effect, now, frames) ?: return emptyList()
        val window = VideoEffectTemporalWindow.make(frames.times, now, start, kind) ?: return emptyList()
        val result = ArrayList<VideoEffectShader.Matte>()
        for (index in window.indices.dropLast(1)) {
            val sample = maxOf(frames.times[index], start)
            if (!effect.isActive(sample)) continue
            val stored = stream[sample] ?: historic(layer, effect, sample, shader, program, width, height, fps)?.also { m -> shader.pin(m.target, true); stream[sample] = m }
            stored?.let { result.add(it) }
        }
        // Only a window's worth around this frame is kept (full-size textures):
        // the farthest from it go first, never one in use now.
        val used = result.map { it.target }.toSet() + own.target
        while (stream.size > kind.capacity + 1) {
            val far = stream.entries.filter { it.value.target !in used }.maxByOrNull { Math.abs(it.key - now) } ?: break
            stream.remove(far.key); shader.pin(far.value.target, false); shader.free(far.value.target)
        }
        while (mattes.size > 2) {
            val oldest = mattes.keys.first { it != key }
            mattes.remove(oldest)?.values?.forEach { shader.pin(it.target, false); shader.free(it.target) }
        }
        return result.reversed()
    }
    /// A raw matte of an earlier source frame: decoded on its own reader, framed
    /// and put through the effects before the temporal one.
    private fun historic(layer: RenderPlan.Layer, effect: VideoEffectSettings, time: Double, shader: VideoEffectShader, program: FrameProgram,
                         width: Int, height: Int, fps: Double): VideoEffectShader.Matte? {
        val clip = layer.picture.clip
        val reader = frames(layer.picture.source, 5) ?: return null
        if (!reader.show(time)) return null
        val frame = CanvasSize(width.toDouble(), height.toDouble())
        val source = CanvasSize(reader.video.displayWidth.toDouble(), reader.video.displayHeight.toDouble())
        val picture = shader.picture(width, height)
        drawSource(clip, time, reader, clip.pictureTransform(source, frame), source, frame, grade(clip.color), shader, program)
        val upstream = VideoEffectRendering.active(clip, time, fps).let { all -> all.take(all.indexOfFirst { it.id == effect.id } + 1) }
        if (upstream.none { it.id == effect.id }) { shader.free(picture); return null }
        val placed = clip.pictureTransform(source, frame)
        val raw = shader.apply(upstream, time, fps, picture, history = { e, own -> matteHistory(layer, e, time, own, shader, program, width, height, fps) }, rawOnly = effect.id,
            person = { personMask(layer.picture.source, reader, time, placed, source, program, shader, width, height) })
        return VideoEffectShader.Matte(raw, shader.guide(raw))
    }
    /// Person masks already found, by source file and frame time (256×256, top row first).
    private val personMasks = object : LinkedHashMap<Pair<String, Long>, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, FloatArray>?) = size > 24
    }
    /// The person in the frame `reader` holds, placed on a w×h canvas by `placed`.
    private fun personMask(source: UUID, reader: FrameReader, time: Double, placed: Affine, sourceSize: CanvasSize, program: FrameProgram,
                           shader: VideoEffectShader, w: Int, h: Int): VideoEffectShader.Target? {
        val file = file(source) ?: return null
        val key = (file.path + "|" + file.length()) to Math.round(time * 1_000_000)
        val values = personMasks[key] ?: run {
            // The upright source frame, whole, at the model's 256×256.
            val n = PersonSegmenter.size
            val small = shader.picture(n, n)
            program.draw(reader.texture, reader.matrix, Affine.scale(n / sourceSize.width, n / sourceSize.height), sourceSize, CanvasSize(n.toDouble(), n.toDouble()))
            val rgb = shader.pixels(small); shader.free(small)
            PersonSegmenter.mask(rgb)?.also { personMasks[key] = it }
        } ?: return null
        val uploaded = shader.upload(values, PersonSegmenter.size)
        val placedMask = shader.place(uploaded, placed, sourceSize, w, h)
        shader.free(uploaded)
        return placedMask
    }
    /// Effect media (Background and Chroma Key's Image or Video), in the project folder.
    private val mediaImages = LinkedHashMap<String, Pair<Int, CanvasSize>>()
    private val mediaReaders = LinkedHashMap<String, FrameReader>()
    private fun effectMedia(effect: VideoEffectSettings, time: Double, shader: VideoEffectShader, program: FrameProgram, w: Int, h: Int): VideoEffectShader.Target? {
        val name = (effect.value("media", time) as? EffectValue.Media)?.file ?: return null
        val file = File(folder, name); if (!file.exists()) { if (strict) throw ProjectError.MissingMedia(); return null }
        val canvas = CanvasSize(w.toDouble(), h.toDouble())
        fun placement(size: CanvasSize): Affine {
            val fill = effect.choice("fit", time) != "Fit"
            val scale = (if (fill) maxOf(w / size.width, h / size.height) else minOf(w / size.width, h / size.height)) * effect.number("backgroundZoom", time)
            return Affine(a = scale, d = scale, tx = (w - size.width * scale) * effect.number("backgroundX", time), ty = (h - size.height * scale) * effect.number("backgroundY", time))
        }
        if (effect.choice("background", time) == "Image") {
            val entry = mediaImages[file.path] ?: run {
                val bitmap = PhotoClip.decodeFile(file, 4096) ?: return null
                val ids = IntArray(1); android.opengl.GLES20.glGenTextures(1, ids, 0)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, ids[0])
                for ((k, v) in listOf(android.opengl.GLES20.GL_TEXTURE_MIN_FILTER to android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER to android.opengl.GLES20.GL_LINEAR,
                    android.opengl.GLES20.GL_TEXTURE_WRAP_S to android.opengl.GLES20.GL_CLAMP_TO_EDGE, android.opengl.GLES20.GL_TEXTURE_WRAP_T to android.opengl.GLES20.GL_CLAMP_TO_EDGE))
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, k, v)
                android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                val size = CanvasSize(bitmap.width.toDouble(), bitmap.height.toDouble()); bitmap.recycle()
                while (mediaImages.size >= 4) { val oldest = mediaImages.keys.first(); mediaImages.remove(oldest)?.let { android.opengl.GLES20.glDeleteTextures(1, intArrayOf(it.first), 0) } }
                (ids[0] to size).also { mediaImages[file.path] = it }
            }
            return shader.placed(entry.first, entry.second, placement(entry.second), w, h, topFirst = true)
        }
        // A video loops, its end dissolving into its start (EffectBackgroundLoop).
        fun reader(slot: String): FrameReader? = mediaReaders["${file.path}|$slot"] ?: try {
            while (mediaReaders.size >= 4) { val oldest = mediaReaders.keys.first(); mediaReaders.remove(oldest)?.release() }
            FrameReader(file).also { mediaReaders["${file.path}|$slot"] = it }
        } catch (e: Exception) { if (strict) throw ProjectError.InvalidMedia(); null }
        val head = reader("head") ?: return null
        val duration = MediaFiles.duration(file, true)
        val overlap = minOf(0.2, duration / 2); val period = duration - overlap
        val t = maxOf(0.0, time - effect.start); val cycle = Math.floor(t / period); val phase = t - cycle * period
        fun frame(r: FrameReader, at: Double): VideoEffectShader.Target? {
            if (!r.show(at)) return null
            val size = CanvasSize(r.video.displayWidth.toDouble(), r.video.displayHeight.toDouble())
            val target = shader.target(w, h)
            program.draw(r.texture, r.matrix, placement(size), size, canvas)
            return target
        }
        val incoming = frame(head, phase) ?: return null
        if (cycle > 0 && phase < overlap) {
            val tail = reader("tail") ?: return incoming
            val outgoing = frame(tail, period + phase) ?: return incoming
            return shader.blend(outgoing, incoming, minOf(1.0, phase / overlap).toFloat())
        }
        return incoming
    }
    /// The source frame `reader` holds, framed and graded into the target bound
    /// now; with Night fix, restored first on its own video values, as on iPhone.
    private fun drawSource(clip: TimelineClip, time: Double, reader: FrameReader, placed: Affine, source: CanvasSize, canvas: CanvasSize,
                           tone: FrameGrade?, shader: VideoEffectShader, program: FrameProgram) {
        val night = VideoEffectRendering.nightFix(clip, time)
        if (night == null) { program.draw(reader.texture, reader.matrix, placed, source, canvas, tone); return }
        val bound = IntArray(1); android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        val sw = source.width.toInt(); val sh = source.height.toInt()
        val raw = shader.picture(sw, sh)
        program.draw(reader.texture, reader.matrix, Affine(), source, source)
        val fixed = shader.nightFix(raw, night, time)
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, bound[0])
        android.opengl.GLES20.glViewport(0, 0, canvas.width.toInt(), canvas.height.toInt())
        program.drawFlat(fixed.texture, placed, source, canvas, tone)
        shader.free(fixed)
    }
    private val clocks = HashMap<TimelineClip, VideoEffectSequenceClock>()
    private val blocks = HashMap<Pair<UUID, Int>, Pair<Any, VideoEffectShader.Target>>()
    /// The layer's picture with its effects, as the iPhone app's compositor
    /// processes it: each source frame the time effects choose is graded,
    /// framed and put through the pixel effects at its own time; a Step
    /// printing block is smeared together and kept while the block lasts.
    private fun effected(layer: RenderPlan.Layer, reader: FrameReader, shader: VideoEffectShader, program: FrameProgram, placed: Affine,
                         source: CanvasSize, frame: CanvasSize, tone: FrameGrade?, fps: Double): VideoEffectShader.Target? {
        val clip = layer.picture.clip; val sourceTime = layer.picture.sourceTime; val frozen = clip.frozenFrame
        val width = frame.width.toInt(); val height = frame.height.toInt()
        val composition = layer.picture.interval.start.seconds + layer.picture.elapsed
        val index = if (VideoEffectSamplePlan.needed(clip)) file(layer.picture.source)?.let { MediaFiles.frameIndex(it) } else null
        val plan = if (VideoEffectSamplePlan.needed(clip)) {
            if (clocks.size > 32) clocks.clear()
            VideoEffectSamplePlan.make(clip, sourceTime, maxOf(1, reader.video.frameRate).toDouble(), clocks.getOrPut(clip) { VideoEffectSequenceClock(clip) }, index)
        } else null
        fun one(time: Double, firstAtOrAfter: Boolean, before: Double): VideoEffectShader.Target? {
            // The source frame itself: the first at or after a cadence boundary, never one past the clip.
            var selected = time
            if (index != null && plan != null) {
                var i = minOf(index.times.size - 1, if (firstAtOrAfter) index.first(time) else index.last(time))
                while (i > 0 && index.times[i] >= before) i--
                selected = index.times[i]
            }
            if (!reader.show(selected)) return null
            val picture = shader.picture(width, height)
            drawSource(clip, selected, reader, placed, source, frame, tone, shader, program)
            // A cadence hold freezes the whole look at the held frame; a freeze's
            // own grain animates on the timeline and its push-in on the hold.
            val effectTime = if (frozen == null) selected else sourceTime
            val frameTime = if (frozen == null) selected else composition
            val hold = frozen?.let { it.offset + layer.picture.elapsed }
            val effects = VideoEffectRendering.active(clip, effectTime, fps)
            // Follow face: the tracked subject, carried from the recording into the canvas by the clip's framing.
            val targets = HashMap<UUID, Pair<Double, Double>>()
            for (effect in effects) {
                if (!(effect.kind == VideoEffectID.dollyZoom || effect.kind == VideoEffectID.crashZoom && effect.toggle("trackFace", effectTime))) continue
                val file = file(layer.picture.source) ?: continue
                val subject = VideoEffectAnalysis.subject(file, clip, effect, strict)?.sample(selected) ?: continue
                val px = subject.centerX * source.width; val py = subject.centerY * source.height
                targets[effect.id] = (placed.x(px, py) / frame.width).coerceIn(0.0, 1.0) to (placed.y(px, py) / frame.height).coerceIn(0.0, 1.0)
            }
            return shader.apply(effects, effectTime, fps, picture, frameTime, hold, targets, history = { effect, own -> matteHistory(layer, effect, selected, own, shader, program, width, height, fps) },
                person = { personMask(layer.picture.source, reader, selected, placed, source, program, shader, width, height) },
                media = { effect, w, h -> effectMedia(effect, effectTime, shader, program, w, h) }, palettes = { effect, upstream ->
                // The analysed palette where the track has reached; until then, this frame's own (as the iPhone app while analysing).
                val analysed = paletteJobs[paletteKey(layer, effect, width, height)]?.track(effectTime)
                    ?: EffectPaletteAnalysis.analyze(shader.samples(upstream), effect.number("colors", effectTime).toInt(), seed = VideoEffectRendering.frameIndex(effectTime, 30.0).toLong())
                EffectPalettes.display(effect, effectTime, analysed, EffectPalettes.locked(effect.lockedPalette))
            })
        }
        if (plan == null) return one(sourceTime, false, Double.POSITIVE_INFINITY)
        if (plan.times.size == 1 || plan.smear <= 0) return one(plan.times.first(), plan.firstAtOrAfter, plan.before)
        val key = layer.picture.item to layer.slot
        val identity = Triple(clip, plan.times, fps)
        blocks[key]?.let { (kept, target) -> if (kept == identity) return target }
        var sum = one(plan.times.first(), plan.firstAtOrAfter, plan.before) ?: return null
        for ((time, amount) in plan.times.drop(1).zip(plan.mixes)) {
            val next = one(time, plan.firstAtOrAfter, plan.before) ?: return null
            sum = shader.blend(sum, next, amount.toFloat())
        }
        blocks[key]?.second?.let { shader.pin(it, false); it.busy = false }
        shader.pin(sum, true)
        blocks[key] = identity to sum
        return sum
    }
    private fun drawOverlays(layer: RenderPlan.Layer, width: Int, height: Int, placement: Affine?) {
        for (image in layer.picture.clip.visibleImages(layer.picture.sourceTime)) pictures.draw(image, width, height, placement)
        for (title in layer.picture.clip.visibleTexts(layer.picture.sourceTime)) titles.draw(title, width, height, placement)
    }

    // Each partner is drawn whole into a texture of its own, then the two are
    // mixed: the transition sees the finished pictures, titles included.
    private var compositor: TransitionCompositor? = null
    private var effectShader: VideoEffectShader? = null
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
            if (layer != null) complete = drawLayer(layer, program, width, height, overlays = !split, framesPerSecond = framesPerSecond) && complete
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
    /// The processed files `timeline` still needs, by name.
    fun pendingAudio(timeline: TrackTimeline): List<String> =
        AudioEnhancements.requests(timeline).mapNotNull { processedFile(it.source, it.effects)?.takeIf { f -> !f.exists() }?.name }
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
        compositor?.releaseProgram(); compositor = null; effectShader?.release(); effectShader = null; blocks.clear(); clocks.clear(); mattes.clear(); paletteJobs.clear()
        for (reader in mediaReaders.values) try { reader.release() } catch (e: Exception) { }; mediaReaders.clear()
        for (entry in mediaImages.values) android.opengl.GLES20.glDeleteTextures(1, intArrayOf(entry.first), 0); mediaImages.clear() }
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
                if (!sources.draw(plan.layers(time), program, width, height, plan.transition(time), fps.toDouble())) {
                    android.util.Log.w("YouRec", "export frame $n at ${time.seconds} s has no picture: " + plan.layers(time).joinToString { "${it.picture.source} @ ${it.picture.sourceTime} slot ${it.slot}" })
                    throw ProjectError.InvalidMedia()
                }
                sources.drawCaptions(plan.captions(time), width, height)
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
