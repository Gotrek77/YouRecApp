package yourec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/// The person mask some effects need (Background, Dolly zoom, Color Isolation
/// on the subject, Quantization's face detail, Chroma Key's person hint). The
/// iPhone app asks Apple Vision; Android runs MediaPipe's Selfie Segmenter
/// (ThirdParty/MediaPipe, Apache 2.0) through ONNX Runtime on the upright
/// source frame, 256×256, and the renderer places the mask with the clip's
/// framing as the iPhone app places Vision's.
object PersonSegmenter {
    const val size = 256
    const val sha256 = "7ff6a1bab263a6df7420129b96b3857880feb1ca799ff72adafe6221a0121600"
    private var session: OrtSession? = null
    private var failed = false
    @Synchronized private fun session(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return try {
            val bytes = NeuralAssets.read?.invoke("MediaPipe/SelfieSegmenter.onnx") ?: throw IllegalStateException("no assets")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format("%02x", it) }
            check(digest == sha256) { "the segmentation model does not match" }
            OrtEnvironment.getEnvironment().createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).also { session = it }
        } catch (e: Exception) { android.util.Log.w("YouRec", "person segmentation unavailable", e); failed = true; null }
    }
    val available: Boolean get() = session() != null
    /// Person confidence (0…1, row-major, top row first) for a 256×256 RGB picture of 0…1 values, top row first.
    @Synchronized fun mask(rgb: FloatArray): FloatArray? {
        val session = session() ?: return null
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(rgb), longArrayOf(1, size.toLong(), size.toLong(), 3)).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val output = result[0] as OnnxTensor
                val values = FloatArray(size * size); output.floatBuffer.get(values)
                return values
            }
        }
    }
}
