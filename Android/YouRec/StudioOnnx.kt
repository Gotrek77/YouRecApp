package yourec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/// JNI entries to the iPhone app's LavaSR and VoiceFixer DSP (native/yourec_jni.c).
class NativeStudioDSP private constructor() {
    companion object {
        @JvmStatic external fun lavaFeatures(audio: FloatArray, mel: FloatArray): Boolean
        @JvmStatic external fun lavaReconstruct(real: FloatArray, imaginary: FloatArray, audio: FloatArray): Boolean
        @JvmStatic external fun lavaMerge(original: FloatArray, restored: FloatArray, output: FloatArray): Boolean
        @JvmStatic external fun voiceFixerFeatures(audio: FloatArray, bank: FloatArray, mel: FloatArray): Boolean
    }
}

/// The iPhone app's DSP, built by the NDK into the same library as RNNoise.
object StudioNativeDSP : StudioDSP {
    private fun check(ok: Boolean) { if (!ok) throw AudioProcessingError.InvalidFormat() }
    val available: Boolean get() = SpeechDetector.available   // loads libyourec
    override fun lavaFeatures(audio: FloatArray, mel: FloatArray) = check(NativeStudioDSP.lavaFeatures(audio, mel))
    override fun lavaReconstruct(real: FloatArray, imaginary: FloatArray, audio: FloatArray) = check(NativeStudioDSP.lavaReconstruct(real, imaginary, audio))
    override fun lavaMerge(original: FloatArray, restored: FloatArray, output: FloatArray) = check(NativeStudioDSP.lavaMerge(original, restored, output))
    override fun voiceFixerFeatures(audio: FloatArray, bank: FloatArray, mel: FloatArray) = check(NativeStudioDSP.voiceFixerFeatures(audio, bank, mel))
}

/// One ONNX graph with one float input, in ONNX Runtime on the CPU.
/// Loaded from a file: VoiceFixer's graph is too large to hold as a byte array.
private class OnnxGraph(path: String, threads: Int) : AutoCloseable {
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    val session: OrtSession = environment.createSession(path, options)
    fun <T> run(input: FloatArray, shape: LongArray, use: (OrtSession.Result) -> T): T =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor -> session.run(mapOf("mel" to tensor)).use(use) }
    override fun close() { session.close(); options.close() }
}
private fun OrtSession.Result.copy(name: String, into: FloatArray) {
    val buffer = (get(name).get() as OnnxTensor).floatBuffer
    if (buffer.remaining() != into.size) throw AudioProcessingError.InvalidFormat()
    buffer.get(into)
}

/// LavaSR v2's spectral network, converted to ONNX by scripts/neural/convert-lavasr.py.
class OnnxLavaNetwork(path: String, threads: Int = 2) : LavaNetwork {
    private val graph = OnnxGraph(path, threads)
    override fun run(mel: FloatArray, real: FloatArray, imaginary: FloatArray) =
        graph.run(mel, longArrayOf(1, 80, 375)) { result -> result.copy("real", real); result.copy("imaginary", imaginary) }
    override fun close() = graph.close()
}

/// VoiceFixer's analysis network and vocoder, converted by scripts/neural/convert-voicefixer.py.
class OnnxVoiceFixerNetwork(path: String, threads: Int = 2) : VoiceFixerNetwork {
    private val graph = OnnxGraph(path, threads)
    override fun run(mel: FloatArray, audio: FloatArray) = graph.run(mel, longArrayOf(1, 1, 256, 128)) { result -> result.copy("audio", audio) }
    override fun close() = graph.close()
}

/// What a chain needs for LavaSR and VoiceFixer, loaded from the app's assets.
object StudioAssets {
    const val lava = "LavaSR/LavaSRv2.onnx"
    const val voiceFixer = "VoiceFixer/VoiceFixer.onnx"
    const val bank = "VoiceFixer/VoiceFixer-Mel.bin"
    // The iPhone app's checked mel bank (ThirdParty/VoiceFixer/PROVENANCE.md).
    const val bankSHA256 = "ea5f198b442761e300d9f270090b5ed8c7a9936126b110b54cff03767198dcd9"

    /// Whether this build carries the converted networks for `kind`.
    fun carries(kind: EffectKind): Boolean = when (kind) {
        EffectKind.studioVoice -> NeuralAssets.has(lava)
        EffectKind.studioRestoration -> NeuralAssets.has(voiceFixer) && NeuralAssets.has(bank)
        else -> true
    }

    fun load(effects: List<AudioEffect>): StudioModels? {
        val wantsLava = effects.any { it.kind == EffectKind.studioVoice }
        val wantsVoiceFixer = effects.any { it.kind == EffectKind.studioRestoration }
        if (!wantsLava && !wantsVoiceFixer) return null
        val read = NeuralAssets.read ?: throw AudioProcessingError.UnavailableModel()
        if (!StudioNativeDSP.available) throw AudioProcessingError.UnavailableModel()
        try {
            val file = NeuralAssets.file ?: throw AudioProcessingError.UnavailableModel()
            val lava = if (wantsLava) OnnxLavaNetwork(file(lava).path) else null
            var bank: FloatArray? = null
            val voiceFixer = if (wantsVoiceFixer) {
                val bytes = read(this.bank)
                if (OnnxDeepFilterModel.sha256(bytes) != bankSHA256 || bytes.size != 1025 * 128 * 4) throw AudioProcessingError.UnavailableModel()
                bank = FloatArray(1025 * 128).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                OnnxVoiceFixerNetwork(file(voiceFixer).path)
            } else null
            return StudioModels(StudioNativeDSP, lava, voiceFixer, bank)
        } catch (e: AudioProcessingError) { throw e
        } catch (e: Exception) { throw AudioProcessingError.UnavailableModel() }
    }
}
