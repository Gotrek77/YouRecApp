package yourec

import android.graphics.Bitmap
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/// Analysis some effects need before they can be drawn, made on the phone
/// from the recording and kept in the app's cache (never in the project).
/// Subject tracks follow the iPhone app's EffectSubjectAnalysis: the effect's
/// part of the clip, 15 times a second, at most 512 pixels on the long side.
object VideoEffectAnalysis {
    @Volatile var cacheFolder: File? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "effect analysis").apply { isDaemon = true } }
    private val pending = HashSet<String>()
    private val tracks = object : LinkedHashMap<String, EffectSubjectTrack>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EffectSubjectTrack>?) = size > 16
    }

    fun subjectKey(file: File, start: Double, end: Double, x: Double, y: Double): String {
        val identity = "subject-android-v1|${file.name}|${file.length()}|${file.lastModified()}|$start|$end|$x|$y"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { String.format("%02x", it) }
    }

    /// The track for `effect` on `clip`, from the cache. In an export (`wait`)
    /// it is made here when missing; in the preview it is made in the
    /// background and null is returned until it is ready (the effect then aims
    /// at its Target X/Y, as the iPhone app does while analysis is pending).
    fun subject(file: File, clip: TimelineClip, effect: VideoEffectSettings, wait: Boolean, ready: () -> Unit = {}): EffectSubjectTrack? {
        val start = maxOf(clip.start, effect.start); val end = minOf(clip.end, effect.end)
        if (end <= start) return null
        val x = effect.number("targetX", start); val y = effect.number("targetY", start)
        val key = subjectKey(file, start, end, x, y)
        synchronized(tracks) { tracks[key]?.let { return it } }
        val stored = cacheFolder?.let { File(it, "subject-$key.json") }
        if (stored != null && stored.exists()) try {
            val track = decode(JSON.parse(stored.readBytes()))
            if (track.valid) { synchronized(tracks) { tracks[key] = track }; return track }
        } catch (e: Exception) { stored.delete() }
        fun make(): EffectSubjectTrack {
            val track = analyse(file, start, end, x, y)
            stored?.let { it.parentFile?.mkdirs(); val partial = File(it.path + ".partial"); partial.writeBytes(JSON.write(encode(track))); partial.renameTo(it) }
            synchronized(tracks) { tracks[key] = track }
            return track
        }
        if (wait) return make()
        synchronized(pending) { if (!pending.add(key)) return null }
        worker.execute {
            try { make(); ready() } catch (e: Exception) { android.util.Log.w("YouRec", "subject analysis failed", e) }
            finally { synchronized(pending) { pending.remove(key) } }
        }
        return null
    }

    /// Faces on frames of [start, end), chosen and smoothed as on iPhone.
    fun analyse(file: File, start: Double, end: Double, x: Double, y: Double): EffectSubjectTrack {
        val retriever = MediaMetadataRetriever()
        val samples = ArrayList<EffectSubjectSample>()
        try {
            retriever.setDataSource(file.path)
            val chooser = EffectSubjectChooser(x, y)
            var time = start
            while (time < end) {
                val frame = retriever.getFrameAtTime((time * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val scale = minOf(1.0, 512.0 / maxOf(frame.width, frame.height))
                    // FaceDetector wants RGB 565 and an even width.
                    val w = maxOf(2, (frame.width * scale).toInt() and 1.inv()); val h = maxOf(2, (frame.height * scale).toInt())
                    val small = Bitmap.createScaledBitmap(frame, w, h, true).copy(Bitmap.Config.RGB_565, false)
                    val faces = arrayOfNulls<FaceDetector.Face>(8)
                    val found = FaceDetector(w, h, faces.size).findFaces(small, faces)
                    val candidates = (0 until found).mapNotNull { i ->
                        val face = faces[i] ?: return@mapNotNull null
                        val mid = android.graphics.PointF(); face.getMidPoint(mid)
                        // A face box from the eyes: about 2.4 eye distances wide and 2.8 high, centred a little below them.
                        val d = face.eyesDistance().toDouble()
                        val bw = d * 2.4 / w; val bh = d * 2.8 / h
                        val cx = mid.x / w.toDouble(); val cy = (mid.y + d * 0.35) / h.toDouble()
                        val left = (cx - bw / 2).coerceIn(0.0, 1.0); val top = (cy - bh / 2).coerceIn(0.0, 1.0)
                        EffectSubjectSample(time, left, top, minOf(1.0, cx + bw / 2) - left, minOf(1.0, cy + bh / 2) - top, minOf(1.0, face.confidence().toDouble() * 2), true)
                    }
                    chooser.update(candidates, time)?.let { samples.add(it) }
                    small.recycle(); frame.recycle()
                }
                time += 1.0 / 15
            }
        } finally { retriever.release() }
        return EffectSubjectTrack(samples)
    }

    fun encode(track: EffectSubjectTrack) = jsonObject("samples" to track.samples.map {
        jsonObject("time" to it.time, "box" to listOf(listOf(it.x, it.y), listOf(it.width, it.height)), "confidence" to it.confidence, "face" to it.face, "lost" to it.lost)
    }, "stepwise" to track.stepwise)
    fun decode(value: Any?): EffectSubjectTrack {
        val fields = Fields.of(value)
        return EffectSubjectTrack(fields.list("samples").map { raw ->
            val s = Fields.of(raw)
            val box = s.list("box").map { pair -> (pair as List<*>).map { Fields.double(it) } }
            EffectSubjectSample(s.double("time"), box[0][0], box[0][1], box[1][0], box[1][1], s.double("confidence"), s.bool("face"), if (s.has("lost")) s.bool("lost") else null)
        }, if (fields.has("stepwise")) fields.bool("stepwise") else null)
    }
}
