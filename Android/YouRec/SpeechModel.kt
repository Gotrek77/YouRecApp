package yourec

/// Antirez's Qwen3-ASR in C (ThirdParty/qwen-asr), built by the NDK: the
/// iPhone app's speech model for subtitles, Qwen3-ASR-0.6B, on the CPU. One
/// model per job, released afterwards; nothing leaves the device.
class SpeechModel private constructor(private var context: Long) : SpeechRecognizer {
    companion object {
        val available: Boolean get() = SpeechDetector.available   // loads libyourec
        @JvmStatic private external fun load(folder: String, threads: Int): Long
        @JvmStatic private external fun free(context: Long)
        @JvmStatic private external fun language(context: Long, name: String?): Boolean
        @JvmStatic private external fun transcribe(context: Long, samples: FloatArray): String?
        @JvmStatic external fun languages(): String

        /// The model in `folder` (config.json, model.safetensors, vocab.json, merges.txt).
        fun open(folder: java.io.File, threads: Int = 0): SpeechModel {
            if (!available) throw SubtitleError("The speech engine is not available in this build.")
            val context = load(folder.path, threads)
            if (context == 0L) throw SubtitleError("The speech model could not be loaded. Download it again.")
            return SpeechModel(context)
        }
    }
    /// 16 kHz mono samples to text. `language` is a name such as "Italian", or null to detect it.
    override fun transcribe(samples: FloatArray, language: String?): String {
        check(context != 0L)
        if (!language(context, language)) throw SubtitleError("This language is not supported by the speech model.")
        return transcribe(context, samples) ?: throw SubtitleError("The speech model could not transcribe this section.")
    }
    override fun close() { if (context != 0L) free(context); context = 0L }
}
