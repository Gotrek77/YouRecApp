package yourec

import java.io.File

/// The speech model for subtitles, downloaded on request like the iPhone
/// app's SubtitleModelPack: Qwen/Qwen3-ASR-0.6B as antirez's qwen-asr reads
/// it, pinned by revision, size and SHA-256, resumable, verified before use.
/// Only weights are downloaded; the engine ships with the app.
object SubtitleModelPack {
    const val repository = "Qwen/Qwen3-ASR-0.6B"
    const val revision = "5eb144179a02acc5e5ba31e748d22b0cf3e303b0"
    private const val folderName = "qwen3-asr-0.6b"
    val files = listOf(
        StudioModelPack.Entry("$folderName/config.json", 6193, "76d3ae4601ce939830b2517f4a6cadb86cc51316c3900af6b020b051c21a478c"),
        StudioModelPack.Entry("$folderName/generation_config.json", 142, "1da527824d81e07118facff437e03f2e24a23311e3bdeb2368973fe77e5f275c"),
        StudioModelPack.Entry("$folderName/vocab.json", 2776833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        StudioModelPack.Entry("$folderName/merges.txt", 1671853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        StudioModelPack.Entry("$folderName/model.safetensors", 1876091704, "79d6cbd4c98c7bbffe9db2edac07f56cd6637d0d5944b27f6c2b8353840323ea"))
    val byteCount: Long get() = files.sumOf { it.bytes }
    private const val base = "https://huggingface.co/$repository/resolve/$revision"

    fun directory(root: File) = File(root, folderName)
    fun installed(root: File) = files.all { StudioModelPack.installed(root, it) != null }

    /// Download every file not yet verified; progress is over all bytes.
    fun download(root: File, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        // A folder that does not exist yet reports no free space at all.
        root.mkdirs()
        if (root.usableSpace < byteCount + 150_000_000L - files.sumOf { StudioModelPack.installed(root, it)?.length() ?: 0L })
            throw SubtitleError("Free at least 2 GB to download the speech model.")
        var done = 0L
        for (entry in files) {
            StudioModelPack.download(root, entry, { progress((done + it * entry.bytes) / byteCount) }, cancelled, base)
            done += entry.bytes
        }
        progress(1.0)
    }
    fun remove(root: File) { for (entry in files) StudioModelPack.remove(root, entry) }
}
