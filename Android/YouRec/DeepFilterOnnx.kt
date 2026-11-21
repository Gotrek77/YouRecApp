package yourec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest

/// Upstream DeepFilterNet3's ONNX export (Rikorose/DeepFilterNet, models/
/// DeepFilterNet3_onnx.tar.gz) in ONNX Runtime, on the CPU. The three graphs
/// are checked against the hashes recorded in Android/ThirdParty/DeepFilterNet3/PROVENANCE.md.
class OnnxDeepFilterModel(encoder: ByteArray, erbDecoder: ByteArray, dfDecoder: ByteArray, threads: Int = 2) : DeepFilterModel {
    companion object {
        val hashes = mapOf(
            "enc.onnx" to "7c5399d3da8a50ebef1c1a0ae421b33376aa5e45d0e92df16da7e83c9c131916",
            "erb_dec.onnx" to "ab669a1d10afe20911728b33053a452071042317a90581092b325da7b2f9d895",
            "df_dec.onnx" to "23114ce3b0f6464b763ee62f7bb8aab6b2a129a21eabd5bcfe59413db05f278a")
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format("%02x", it) }
        /// The three graphs by name, refused unless each matches its recorded hash.
        fun load(read: (String) -> ByteArray): OnnxDeepFilterModel {
            val graphs = listOf("enc.onnx", "erb_dec.onnx", "df_dec.onnx").map { name ->
                read(name).also { if (sha256(it) != hashes[name]) throw AudioProcessingError.UnavailableModel() }
            }
            return OnnxDeepFilterModel(graphs[0], graphs[1], graphs[2])
        }
    }
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val encoder = environment.createSession(encoder, options)
    private val erbDecoder = environment.createSession(erbDecoder, options)
    private val dfDecoder = environment.createSession(dfDecoder, options)

    override fun run(erb: FloatArray, spec: FloatArray, frames: Int, mask: FloatArray, coefs: FloatArray) {
        val n = frames.toLong()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(erb, 0, frames * 32), longArrayOf(1, 1, n, 32)).use { featErb ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(spec, 0, frames * 96 * 2), longArrayOf(1, 2, n, 96)).use { featSpec ->
                encoder.run(mapOf("feat_erb" to featErb, "feat_spec" to featSpec)).use { encoded ->
                    fun tensor(name: String) = encoded.get(name).get() as OnnxTensor
                    val emb = tensor("emb")
                    erbDecoder.run(mapOf("emb" to emb, "e3" to tensor("e3"), "e2" to tensor("e2"), "e1" to tensor("e1"), "e0" to tensor("e0"))).use { result ->
                        val buffer = (result.get(0) as OnnxTensor).floatBuffer
                        if (buffer.remaining() != frames * 32) throw AudioProcessingError.InvalidFormat()
                        buffer.get(mask, 0, frames * 32)
                    }
                    dfDecoder.run(mapOf("emb" to emb, "c0" to tensor("c0"))).use { result ->
                        val buffer = (result.get("coefs").get() as OnnxTensor).floatBuffer
                        if (buffer.remaining() != frames * 96 * 10) throw AudioProcessingError.InvalidFormat()
                        buffer.get(coefs, 0, frames * 96 * 10)
                    }
                }
            }
        }
    }
    override fun close() { encoder.close(); erbDecoder.close(); dfDecoder.close(); options.close() }
}

/// The app's bundled networks. Set at launch.
object NeuralAssets {
    @Volatile var read: ((String) -> ByteArray)? = null
    /// Whether an asset is in this build: the converted LavaSR and VoiceFixer
    /// networks are present only when the build had them (scripts/neural).
    @Volatile var has: (String) -> Boolean = { false }
    /// Where assets are copied out, and downloaded networks kept.
    @Volatile var folder: java.io.File? = null
    /// An asset as a file of its own, copied once from the APK.
    @Volatile var file: ((String) -> java.io.File)? = null
    /// DeepFilterNet3's DSP data and network, each checked against its hash.
    fun deepFilter(): Pair<DeepFilterDSP, OnnxDeepFilterModel> {
        val read = read ?: throw AudioProcessingError.UnavailableModel()
        try {
            val dsp = DeepFilterDSP.parse(read("DeepFilterNet3/DeepFilterNet3-DSP.json"))
            return dsp to OnnxDeepFilterModel.load { read("DeepFilterNet3/$it") }
        } catch (e: AudioProcessingError) { throw e
        } catch (e: Exception) { throw AudioProcessingError.UnavailableModel() }
    }
}
