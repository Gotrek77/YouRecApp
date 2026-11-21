package yourec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/// The app's Qwen3-ASR path on this computer: SpeechHarness <model folder> <16-bit PCM WAV>.
/// Prints the transcription on stdout.
fun main(args: Array<String>) {
    val bytes = ByteBuffer.wrap(File(args[1]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    // A plain RIFF/WAVE file: find "fmt " and "data".
    var rate = 0; var channels = 0; var bits = 0; var data: ShortArray? = null
    bytes.position(12)
    while (bytes.remaining() >= 8) {
        val id = ByteArray(4).also { bytes.get(it) }.toString(Charsets.US_ASCII); val size = bytes.int
        val at = bytes.position()
        when (id) {
            "fmt " -> { bytes.short; channels = bytes.short.toInt(); rate = bytes.int; bytes.int; bytes.short; bits = bytes.short.toInt() }
            "data" -> data = ShortArray(size / 2).also { bytes.asShortBuffer().get(it) }
        }
        bytes.position(at + size + (size and 1))
    }
    check(rate == 16000 && channels == 1 && bits == 16 && data != null) { "expected 16 kHz mono 16-bit PCM, got $rate Hz, $channels channels, $bits bits" }
    val samples = FloatArray(data!!.size) { data[it] / 32768f }
    val started = System.nanoTime()
    SpeechModel.open(File(args[0])).use { model ->
        val text = model.transcribe(samples, null)
        System.err.println(String.format("%.1f s of speech in %.1f s", samples.size / 16000.0, (System.nanoTime() - started) / 1e9))
        println(text.trim())
    }
}
