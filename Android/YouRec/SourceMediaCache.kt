package yourec

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.LruCache
import android.view.Surface
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors

/// Decodes one source movie into an external texture, showing exactly the frame
/// asked for. Preview and export use the same reader, so both show the same frame
/// for the same edit time. Call everything on the thread that owns the GL context.
class FrameReader(file: File) {
    val video: MediaFiles.Video = MediaFiles.video(file) ?: throw ProjectError.InvalidMedia()
    val texture = FrameProgram.externalTexture()
    val matrix = FrameProgram.identity.copyOf()
    private val surfaceTexture = SurfaceTexture(texture)
    private val surface = Surface(surfaceTexture)
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val lock = Object()
    private var available = false
    private var pending = -1
    private var pendingTime = 0L
    private var shown = Long.MIN_VALUE
    private var inputDone = false
    private var outputDone = false
    private var fed = false

    init {
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = MediaFiles.track(extractor, "video/")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // The listener must not run on this thread: show() waits here for it.
            surfaceTexture.setOnFrameAvailableListener({ synchronized(lock) { available = true; lock.notifyAll() } }, Handler(arrivals.looper))
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            // With a Surface the decoder applies the recorded rotation itself: the
            // texture matrix then yields an upright picture of the display size.
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            // An unsupported codec, or none free: give back everything already taken.
            try { codec?.release() } catch (ignored: Exception) { }
            extractor.release(); surface.release(); surfaceTexture.release(); FrameProgram.deleteTexture(texture)
            throw e
        }
        decoder = codec
    }

    /// Put the frame covering `time` (source seconds) in the texture: the last
    /// frame at or before it. Frame times come from the file, not from a nominal
    /// rate, so variable-rate footage and dropped frames show the right picture.
    /// False only when the file yields no picture at all; at the end the last frame stays.
    fun show(time: Double): Boolean {
        val target = maxOf(0L, (time * 1_000_000).toLong())
        var have = shown != Long.MIN_VALUE
        // The frame after the one on screen is kept decoded; until the target reaches it, nothing changes.
        if (have && target >= shown && (if (pending >= 0) target < pendingTime else outputDone)) return true
        // Decoding forward through a second of footage is cheaper than a seek
        // to the previous key frame; anything else, or going back, needs one.
        if (!have || target < shown || (target > shown + 1_000_000 && !(pending >= 0 && target < pendingTime))) { seek(target); have = false }
        var best = -1; var bestTime = 0L
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (pending < 0 && !next()) break
            if (pending < 0) continue
            if (best >= 0 && pendingTime > target) break
            // Frames that end before the target are passed over without drawing.
            if (best >= 0) decoder.releaseOutputBuffer(best, false)
            best = pending; bestTime = pendingTime; pending = -1
        }
        if (best < 0) return have
        synchronized(lock) { available = false }
        decoder.releaseOutputBuffer(best, true)
        val arrived = synchronized(lock) {
            val limit = System.currentTimeMillis() + 1000
            while (!available && System.currentTimeMillis() < limit) lock.wait(maxOf(1, limit - System.currentTimeMillis()))
            available
        }
        if (!arrived) return have
        surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(matrix)
        // A first frame later than the target covers it too.
        shown = minOf(bestTime, target)
        return true
    }

    private fun seek(target: Long) {
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (pending >= 0) { decoder.releaseOutputBuffer(pending, false); pending = -1 }
        // A flush before anything was decoded would discard the parameter sets
        // given at configuration, and the decoder would then reject every frame.
        if (fed) decoder.flush()
        fed = false
        inputDone = false; outputDone = false; shown = Long.MIN_VALUE
    }
    /// Advance until one decoded frame is pending. False once the stream has ended.
    private fun next(): Boolean {
        var idle = 0
        while (!outputDone && idle < 400) {
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(2000)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 5000)
            // Output of any kind means the configuration data has been consumed.
            if (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) fed = true
            if (index < 0) {
                idle++
                // Not every decoder announces the end of the stream. With all input
                // given and nothing coming back, the last frame has been seen;
                // otherwise every later request would wait here again.
                if (inputDone && idle >= 60) outputDone = true
                continue
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            if (info.size == 0) { decoder.releaseOutputBuffer(index, false); continue }
            pending = index; pendingTime = info.presentationTimeUs
            return true
        }
        return false
    }

    fun release() {
        try { decoder.stop() } catch (e: IllegalStateException) { }
        decoder.release(); extractor.release()
        surface.release(); surfaceTexture.release()
        FrameProgram.deleteTexture(texture)
    }

    private companion object {
        val arrivals: HandlerThread by lazy { HandlerThread("yourec-frames").apply { start() } }
    }
}

/// One source's sound as 48 kHz stereo float, readable at any position. Reads
/// in order cost one decode; a jump costs a seek. One reader, one thread.
class DecodedAudio(file: File) : PCMSource {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var sourceRate = AudioMix.sampleRate
    private var sourceChannels = 2
    private var float = false
    private var chunk = FloatArray(0)
    private var chunkStart = 0L          // position of chunk[0], in output frames
    private var chunkFrames = 0
    private var runStart = 0L            // output position of the first frame decoded since the last seek
    private var runInput = 0L            // source frames decoded since then
    private var positioned = false
    private var sought = Long.MIN_VALUE  // where the last seek was asked to land
    private var endAt = Long.MAX_VALUE   // first position after the file's last sample, once known
    private var fed = false
    private var inputDone = false
    private var ended = false

    init {
        try {
            extractor.setDataSource(file.path)
            val track = MediaFiles.track(extractor, "audio/")
            if (track >= 0) {
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            } else ended = true
        } catch (e: Exception) { ended = true }   // A silent or unreadable track plays as silence; the picture still renders.
    }

    override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        var done = 0
        while (done < frames) {
            val p = position + done
            if (p < 0) { into[offset + done * 2] = 0f; into[offset + done * 2 + 1] = 0f; done++; continue }
            if (p >= chunkStart && p < chunkStart + chunkFrames) {
                val n = minOf(frames - done, (chunkStart + chunkFrames - p).toInt())
                System.arraycopy(chunk, ((p - chunkStart) * 2).toInt(), into, offset + done * 2, n * 2)
                done += n; continue
            }
            // A track can begin after the position asked for (sound that starts later
            // than the picture). Nothing was recorded there: silence, not another seek.
            if (positioned && chunkFrames > 0 && p < chunkStart && p >= sought) {
                val n = minOf((frames - done).toLong(), chunkStart - p).toInt()
                java.util.Arrays.fill(into, offset + done * 2, offset + (done + n) * 2, 0f); done += n; continue
            }
            // Past the last sample the file holds there is only silence. Asking the
            // decoder again would cost a seek and a wait on every block.
            if (p >= endAt) break
            // Before the decoded window, or more than half a second ahead of it.
            if (!positioned || p < chunkStart || p > chunkStart + chunkFrames + AudioMix.sampleRate / 2) seek(p)
            if (ended || !decode()) break
        }
        if (done < frames) java.util.Arrays.fill(into, offset + done * 2, offset + frames * 2, 0f)
    }

    private fun seek(position: Long) {
        val codec = decoder ?: run { ended = true; return }
        // Start a little early: a decoder needs a frame or two after a flush
        // before its output is exact again. Those frames are decoded and passed over.
        extractor.seekTo(maxOf(0L, position * 1_000_000 / AudioMix.sampleRate - 100_000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // As for pictures: never flush away the configuration data before first use.
        if (fed) codec.flush()
        fed = false
        inputDone = false; ended = false; positioned = false; chunkFrames = 0; chunkStart = position; sought = position
    }
    private fun decode(): Boolean {
        val codec = decoder ?: return false
        var idle = 0
        while (idle < 400) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(2000)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 5000)
            if (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) fed = true
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                continue
            }
            if (index < 0) { idle++; continue }
            val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val got = info.size > 0
            if (got) convert(codec, index)
            codec.releaseOutputBuffer(index, false)
            if (last) { ended = true; endAt = minOf(endAt, if (positioned) chunkStart + chunkFrames else sought) }
            if (got) return true
            if (last) return false
        }
        // A decoder that stays silent with all input given has ended, announced or not.
        ended = true
        if (inputDone) endAt = minOf(endAt, if (positioned) chunkStart + chunkFrames else sought)
        return false
    }
    /// Deinterleave, bring to stereo and to the mix rate. Positions come from a
    /// running count of source frames, so chunk edges never drift or overlap.
    private fun convert(codec: MediaCodec, index: Int) {
        val bytes = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
        bytes.position(info.offset); bytes.limit(info.offset + info.size)
        val inFrames = info.size / ((if (float) 4 else 2) * sourceChannels)
        if (!positioned) {
            runStart = Math.round(info.presentationTimeUs * AudioMix.sampleRate / 1e6); runInput = 0; positioned = true
        }
        val source = FloatArray(inFrames * 2)
        if (float) { val samples = bytes.asFloatBuffer(); for (i in 0 until inFrames) { val l = samples.get(i * sourceChannels); source[i * 2] = l; source[i * 2 + 1] = if (sourceChannels > 1) samples.get(i * sourceChannels + 1) else l } }
        else { val samples = bytes.asShortBuffer(); for (i in 0 until inFrames) { val l = samples.get(i * sourceChannels) / 32768f; source[i * 2] = l; source[i * 2 + 1] = if (sourceChannels > 1) samples.get(i * sourceChannels + 1) / 32768f else l } }
        val start = runStart + runInput * AudioMix.sampleRate / sourceRate
        val end = runStart + (runInput + inFrames) * AudioMix.sampleRate / sourceRate
        val outFrames = (end - start).toInt()
        if (sourceRate == AudioMix.sampleRate) chunk = source
        else {
            if (chunk.size < outFrames * 2) chunk = FloatArray(outFrames * 2)
            for (i in 0 until outFrames) {
                val at = (start + i - runStart).toDouble() * sourceRate / AudioMix.sampleRate - runInput
                val a = at.toInt().coerceIn(0, inFrames - 1); val b = minOf(inFrames - 1, a + 1); val f = (at - a).toFloat().coerceIn(0f, 1f)
                chunk[i * 2] = source[a * 2] + (source[b * 2] - source[a * 2]) * f
                chunk[i * 2 + 1] = source[a * 2 + 1] + (source[b * 2 + 1] - source[a * 2 + 1]) * f
            }
        }
        chunkStart = start; chunkFrames = outFrames; runInput += inFrames
    }

    fun release() {
        try { decoder?.stop() } catch (e: IllegalStateException) { }
        decoder?.release(); extractor.release()
    }
}

/// Thumbnails and waveforms for the timeline. Both are replaceable caches made
/// off the main thread; a long recording never has to be decoded to open it.
object SourceMediaCache {
    private val main = Handler(Looper.getMainLooper())
    private val pictures = Executors.newSingleThreadExecutor()
    private val sound = Executors.newSingleThreadExecutor()
    private val thumbnails = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private val requested = HashSet<String>()
    private val waveforms = HashMap<String, WaveformPeaks>()
    private val analysing = HashSet<String>()

    /// The cached frame near `time`, or null after scheduling it; `ready` then runs on the main thread.
    fun thumbnail(file: File, time: Double, height: Int, ready: () -> Unit): Bitmap? {
        // Half-second buckets: neighbouring timeline cells can share a decode.
        val bucket = Math.round(time * 2)
        val key = file.path + "@" + bucket + "/" + height
        thumbnails.get(key)?.let { return it }
        if (!requested.add(key)) return null
        pictures.execute {
            var image: Bitmap? = null
            try {
                val retriever = retrievers.getOrPut(file.path) { MediaMetadataRetriever().apply { setDataSource(file.path) } }
                val full = retriever.getFrameAtTime(bucket * 500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (full != null && full.height > 0) {
                    image = Bitmap.createScaledBitmap(full, maxOf(1, full.width * height / full.height), height, true)
                    if (image !== full) full.recycle()
                }
            } catch (e: Exception) { }
            main.post { requested.remove(key); image?.let { thumbnails.put(key, it); ready() } }
        }
        return null
    }

    /// The cached waveform, or null after scheduling its analysis.
    fun waveform(file: File, cache: File, ready: () -> Unit): WaveformPeaks? {
        waveforms[file.path]?.let { return it }
        if (!analysing.add(file.path)) return null
        sound.execute {
            var result: WaveformPeaks? = null
            try {
                val saved = File(cache, "waveform-" + file.nameWithoutExtension + ".bin")
                if (saved.exists() && saved.lastModified() >= file.lastModified()) result = WaveformPeaks(bucketsPerSecond, saved.readBytes())
                else {
                    val audio = DecodedAudio(file)
                    try {
                        val total = (MediaFiles.duration(file, needsVideo = false) * AudioMix.sampleRate).toLong()
                        val builder = WaveformPeaks.Builder(bucketsPerSecond, AudioMix.sampleRate)
                        val block = FloatArray(8192 * 2); var position = 0L
                        while (position < total) {
                            val n = minOf(8192L, total - position).toInt()
                            audio.read(position, block, 0, n); builder.add(block, n, 2); position += n
                        }
                        result = builder.build()
                        RecoveryFile.write(result.peaks, saved)
                    } finally { audio.release() }
                }
            } catch (e: Exception) { result = WaveformPeaks(bucketsPerSecond, ByteArray(0)) }   // No sound track: a flat line, not a retry loop.
            main.post { analysing.remove(file.path); result?.let { waveforms[file.path] = it; ready() } }
        }
        return null
    }
    private const val bucketsPerSecond = 100

    /// Drop decoders held for a project that is no longer on screen.
    fun close() {
        pictures.execute { for (retriever in retrievers.values) try { retriever.release() } catch (e: Exception) { }; retrievers.clear() }
    }
}
