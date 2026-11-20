package yourec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/// Runs DeepFilterStream with upstream's ONNX graphs over a raw float file:
/// Harness <graphs folder> <DSP json> <chunk> <warm> <input.f32> <output.f32>
fun main(args: Array<String>) {
    val dsp = DeepFilterDSP.parse(File(args[1]).readBytes())
    val model = OnnxDeepFilterModel.load { File(args[0], it).readBytes() }
    val raw = ByteBuffer.wrap(File(args[4]).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val input = FloatArray(raw.remaining()).also { raw.get(it) }
    val stream = DeepFilterStream(dsp, model, args[2].toInt(), args[3].toInt())
    val output = FloatArray(input.size)
    val hop = FloatArray(480); val result = FloatArray(480)
    var k = 0L
    val started = System.nanoTime()
    while (true) {
        for (i in 0 until 480) { val p = k * 480 + i; hop[i] = if (p < input.size) input[p.toInt()] else 0f }
        stream.process(hop, result)
        val h = k - stream.latency
        if (h >= 0) for (i in 0 until 480) { val p = h * 480 + i; if (p < input.size) output[p.toInt()] = result[i] }
        if (h >= 0 && (h + 1) * 480 >= input.size) break
        k++
    }
    model.close()
    System.err.println(String.format("%.1f s of sound in %.1f s", input.size / 48000.0, (System.nanoTime() - started) / 1e9))
    val bytes = ByteBuffer.allocate(output.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(output) }
    File(args[5]).writeBytes(bytes.array())
}
