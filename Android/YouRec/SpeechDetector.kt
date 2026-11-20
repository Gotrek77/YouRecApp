package yourec

/// The iPhone app's RNNoise voice detector, built from ThirdParty/RNNoise by
/// the NDK (see native/). One detector per channel; frames are 10 ms at 48 kHz.
class SpeechDetector : AutoCloseable {
    companion object {
        /// Whether the native library could be loaded; without it Pauses decides by loudness alone.
        val available: Boolean by lazy { try { System.loadLibrary("yourec"); true } catch (e: Throwable) { false } }
        @JvmStatic external fun create(): Long
        @JvmStatic external fun destroy(state: Long)
        @JvmStatic external fun probability(state: Long, samples: FloatArray, offset: Int, stride: Int): Float
    }
    private var state = create()
    /// Speech probability, 0–1, of 480 samples of one channel starting at `offset`, every `stride` values.
    fun probability(samples: FloatArray, offset: Int, stride: Int): Float = if (state == 0L) 0f else probability(state, samples, offset, stride)
    override fun close() { if (state != 0L) destroy(state); state = 0L }
}
