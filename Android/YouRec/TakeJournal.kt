package yourec

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/// A movie file gets its index only when it is closed, so a take cut short by a
/// crash or a dead battery cannot be played. While a take is being written,
/// every encoded sample is also appended here, in the order it was produced.
/// If the movie was never closed, the journal holds all that is needed to
/// write it again, up to the last second that reached the disk. A take that
/// closes normally deletes its journal.
object TakeJournal {
    class Track(val mime: String, val width: Int, val height: Int, val sampleRate: Int, val channels: Int, val configuration: List<ByteArray>)

    /// Hidden beside the movie, so it never looks like footage.
    fun file(movie: File) = File(movie.parentFile, "." + movie.name + ".journal")
    fun movie(journal: File) = File(journal.parentFile, journal.name.removePrefix(".").removeSuffix(".journal"))
    fun isJournal(file: File) = file.name.startsWith(".") && file.name.endsWith(".journal")

    private const val magic = 0x59524A31          // "YRJ1"
    private const val trackRecord = 1
    private const val sampleRecord = 2
    private const val endOfRecord = 0x5A           // a record without it was torn by the interruption
    private const val largestSample = 64 * 1024 * 1024

    class Writer(private val file: File) {
        private val raw = FileOutputStream(file)
        private val out = DataOutputStream(BufferedOutputStream(raw, 1 shl 16))
        private var scratch = ByteArray(1 shl 16)
        init { out.writeInt(magic) }

        fun track(video: Boolean, track: Track) {
            out.writeByte(trackRecord); out.writeBoolean(video)
            out.writeUTF(track.mime); out.writeInt(track.width); out.writeInt(track.height); out.writeInt(track.sampleRate); out.writeInt(track.channels)
            out.writeInt(track.configuration.size)
            for (block in track.configuration) { out.writeInt(block.size); out.write(block) }
            out.writeByte(endOfRecord)
        }
        /// Appends the bytes between the buffer's position and limit, leaving both unchanged.
        fun sample(video: Boolean, time: Long, flags: Int, data: ByteBuffer) {
            val size = data.remaining()
            if (scratch.size < size) scratch = ByteArray(size)
            data.duplicate().get(scratch, 0, size)
            out.writeByte(sampleRecord); out.writeBoolean(video); out.writeLong(time); out.writeInt(flags); out.writeInt(size)
            out.write(scratch, 0, size)
            out.writeByte(endOfRecord)
        }
        /// Make everything appended so far survive a power loss.
        fun sync() { out.flush(); raw.fd.sync() }
        fun close(delete: Boolean) {
            try { if (delete) out.close() else { sync(); out.close() } } catch (e: Exception) { }
            if (delete) file.delete()
        }
    }

    /// Replay a journal in order. Reading stops quietly at the first record
    /// that is incomplete: that is where the take was interrupted. Returns the
    /// number of samples delivered.
    fun read(file: File, onTrack: (video: Boolean, Track) -> Unit, onSample: (video: Boolean, time: Long, flags: Int, data: ByteArray, size: Int) -> Unit): Int {
        var samples = 0
        var buffer = ByteArray(1 shl 16)
        DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
            try {
                if (input.readInt() != magic) return 0
                while (true) {
                    when (input.readUnsignedByte()) {
                        trackRecord -> {
                            val video = input.readBoolean()
                            val mime = input.readUTF(); val width = input.readInt(); val height = input.readInt(); val rate = input.readInt(); val channels = input.readInt()
                            val count = input.readInt()
                            if (count < 0 || count > 8) return samples
                            val blocks = ArrayList<ByteArray>()
                            repeat(count) {
                                val size = input.readInt()
                                if (size < 0 || size > 1 shl 20) return samples
                                blocks.add(ByteArray(size).also { input.readFully(it) })
                            }
                            if (input.readUnsignedByte() != endOfRecord) return samples
                            onTrack(video, Track(mime, width, height, rate, channels, blocks))
                        }
                        sampleRecord -> {
                            val video = input.readBoolean(); val time = input.readLong(); val flags = input.readInt(); val size = input.readInt()
                            if (size < 0 || size > largestSample) return samples
                            if (buffer.size < size) buffer = ByteArray(size)
                            input.readFully(buffer, 0, size)
                            if (input.readUnsignedByte() != endOfRecord) return samples
                            onSample(video, time, flags, buffer, size); samples++
                        }
                        else -> return samples
                    }
                }
            } catch (e: EOFException) { }
        }
        return samples
    }
}
