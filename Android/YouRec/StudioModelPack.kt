package yourec

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/// The converted LavaSR and VoiceFixer networks, downloadable on request when
/// the build does not carry them, after the iPhone app's SubtitleModelPack:
/// pinned files, sizes and SHA-256, resumable, verified before use, nothing
/// sent but the request. Only weights are downloaded; the code ships with the app.
object StudioModelPack {
    class Entry(val asset: String, val bytes: Long, val sha256: String) {
        /// The repository holds the files at its top level.
        val remoteName: String get() = asset.substringAfterLast('/')
    }
    /// Public Hugging Face repository and revision holding the files of
    /// scripts/neural (see its README). Null until they are published.
    val repository: String? = null
    val revision: String? = null
    // Filled from the CI conversion (scripts/neural, job neural-models).
    val lava = Entry("LavaSR/LavaSRv2.onnx", 55970417, "50b59eae6f4276af5ea12df8c90cdb509dd4798b374a2dd76086ae91add1d65a")
    val voiceFixer: Entry? = null

    fun entry(kind: EffectKind): Entry? = when (kind) { EffectKind.studioVoice -> lava; EffectKind.studioRestoration -> voiceFixer; else -> null }
    val available: Boolean get() = repository != null && revision != null

    fun url(entry: Entry, base: String? = null): URL =
        URL((base ?: "https://huggingface.co/$repository/resolve/$revision") + "/" + entry.remoteName)

    /// The downloaded file, once complete and verified.
    fun installed(folder: File, entry: Entry): File? {
        val target = File(folder, entry.asset)
        val marker = File(folder, entry.asset + ".verified")
        return if (target.length() == entry.bytes && marker.exists() && marker.readText() == entry.sha256) target else null
    }

    /// Download `entry` into `folder`, resuming a partial file. The file is used
    /// only after its size and SHA-256 match.
    fun download(folder: File, entry: Entry, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }, base: String? = null): File {
        installed(folder, entry)?.let { progress(1.0); return it }
        if (base == null && !available) throw AudioProcessingError.UnavailableModel()
        val target = File(folder, entry.asset); target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".download")
        if (partial.length() > entry.bytes) partial.delete()
        val connection = url(entry, base).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000; connection.readTimeout = 60000
        connection.useCaches = false
        val already = partial.length()
        if (already > 0) connection.setRequestProperty("Range", "bytes=$already-")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("The model download was refused ($code). Please retry.")
            val resumed = code == 206
            RandomAccessFile(partial, "rw").use { file ->
                if (!resumed) file.setLength(0)
                file.seek(file.length())
                val buffer = ByteArray(1 shl 20)
                connection.inputStream.use { input ->
                    while (true) {
                        if (cancelled()) throw AudioProcessingError.Cancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (file.length() + n > entry.bytes) throw java.io.IOException("The downloaded model is larger than expected.")
                        file.write(buffer, 0, n)
                        progress(file.length().toDouble() / entry.bytes)
                    }
                }
            }
        } finally { connection.disconnect() }
        if (partial.length() != entry.bytes) throw java.io.IOException("The model download was interrupted. Please retry.")
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input -> val buffer = ByteArray(1 shl 20); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val hex = digest.digest().joinToString("") { String.format("%02x", it) }
        if (hex != entry.sha256) { partial.delete(); throw java.io.IOException("The downloaded model did not pass verification. Please retry.") }
        target.delete()
        if (!partial.renameTo(target)) throw java.io.IOException("cannot store the model")
        File(folder, entry.asset + ".verified").writeText(entry.sha256)
        progress(1.0)
        return target
    }

    fun remove(folder: File, entry: Entry) {
        File(folder, entry.asset).delete(); File(folder, entry.asset + ".verified").delete(); File(folder, entry.asset + ".download").delete()
    }
}
