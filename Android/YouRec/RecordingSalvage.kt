package yourec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/// Rebuilds takes that were being recorded when the app died. Runs at launch,
/// before the library indexes finished movies that no document mentions yet.
object RecordingSalvage {
    /// Every journal in a project folder older than `before` (milliseconds):
    /// a newer one belongs to a take being recorded now. Returns what it did, for the user.
    fun recover(folder: File, before: Long): List<String> {
        val notices = ArrayList<String>()
        for (journal in folder.listFiles() ?: emptyArray()) {
            if (!TakeJournal.isJournal(journal) || journal.lastModified() >= before) continue
            val movie = TakeJournal.movie(journal)
            // The movie was closed and only the journal's removal was interrupted.
            if (movie.exists() && playable(movie)) { journal.delete(); continue }
            val rebuilt = File(folder, "." + movie.name + ".rebuilding")
            try {
                val seconds = rebuild(journal, rebuilt)
                if (seconds > 0 && playable(rebuilt)) {
                    movie.delete()
                    RecoveryFile.move(rebuilt, movie, replace = true)
                    journal.delete()
                    notices.add(String.format("Recovered %.1f s of an interrupted recording. The length of any missing ending is unknown.", seconds))
                    continue
                }
            } catch (e: Exception) { }
            rebuilt.delete()
            // Nothing playable can be made. Keep what exists, out of the way, rather than deleting footage.
            val kept = File(folder, "Recovery").apply { mkdirs() }
            if (movie.exists()) movie.renameTo(File(kept, movie.name))
            journal.renameTo(File(kept, journal.name.removePrefix(".")))
            notices.add("An interrupted recording could not be rebuilt. Its files were kept in the project's Recovery folder.")
        }
        return notices
    }

    private fun playable(movie: File) = try { MediaFiles.duration(movie, needsVideo = true) > 0 } catch (e: Exception) { false }

    /// Write the journal's samples into a new movie. Returns the seconds of picture written.
    private fun rebuild(journal: File, target: File): Double {
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val formats = arrayOfNulls<MediaFormat>(2); val tracks = intArrayOf(-1, -1); val last = longArrayOf(-1, -1)
        var started = false; var first = -1L
        val info = MediaCodec.BufferInfo()
        try {
            TakeJournal.read(journal, { video, track ->
                val format = if (video) MediaFormat.createVideoFormat(track.mime, track.width, track.height) else MediaFormat.createAudioFormat(track.mime, track.sampleRate, track.channels)
                track.configuration.forEachIndexed { i, block -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(block)) }
                formats[if (video) 0 else 1] = format
            }, { video, time, flags, data, size ->
                if (!started) {
                    // Both formats precede the first sample in a journal; a take without sound has one.
                    val picture = formats[0] ?: throw IllegalStateException("no picture")
                    tracks[0] = muxer.addTrack(picture)
                    formats[1]?.let { tracks[1] = muxer.addTrack(it) }
                    muxer.start(); started = true
                }
                val slot = if (video) 0 else 1
                if (tracks[slot] >= 0 && time > last[slot]) {
                    info.set(0, size, time, flags and (MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    muxer.writeSampleData(tracks[slot], ByteBuffer.wrap(data, 0, size), info)
                    last[slot] = time
                    if (video && first < 0) first = time
                }
            })
            if (!started || first < 0) return 0.0
            muxer.stop()
            return (last[0] - first) / 1e6
        } finally { try { muxer.release() } catch (e: Exception) { } }
    }
}
