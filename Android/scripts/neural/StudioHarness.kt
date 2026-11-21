package yourec

import java.io.File

/// LavaSR and VoiceFixer through the app's own pipeline, with the converted
/// networks in ONNX Runtime and the iPhone app's DSP built for this computer:
/// StudioHarness <neural-models folder> <work folder>. Checks that both run end
/// to end (names, shapes, windowing) and keep the voice; not a listening test.
fun main(args: Array<String>) {
    val models = File(args[0]); val work = File(args[1]).apply { mkdirs() }
    check(StudioNativeDSP.available) { "libyourec was not found on java.library.path" }
    val bankBytes = File("../ThirdParty/VoiceFixer/VoiceFixer-Mel.bin").readBytes()
    check(OnnxDeepFilterModel.sha256(bankBytes) == StudioAssets.bankSHA256)
    val bank = FloatArray(1025 * 128).also { java.nio.ByteBuffer.wrap(bankBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    val length = 48000L * 12 + 3001
    // A voiced, gliding harmonic signal with syllable-like envelope, plus noise.
    val random = java.util.Random(5)
    val clean = FloatArray(length.toInt()); var phase = 0.0
    for (i in clean.indices) {
        val t = i / 48000.0; phase += 2 * Math.PI * (140 + 30 * Math.sin(2 * Math.PI * 0.3 * t)) / 48000
        val envelope = (0.5 + 0.5 * Math.sin(2 * Math.PI * 3 * t)) * (if (Math.sin(2 * Math.PI * 0.2 * t) > -0.4) 1.0 else 0.0)
        clean[i] = (envelope * (0.25 * Math.sin(phase) + 0.1 * Math.sin(2 * phase) + 0.05 * Math.sin(3 * phase))).toFloat()
    }
    val noisy = FloatArray(clean.size) { clean[it] + (random.nextGaussian() * 0.01).toFloat() }
    // For scripts/neural/check-voicefixer.py: the input, mono, little-endian float at 48 kHz.
    java.io.File(work, "noisy.f32").writeBytes(java.nio.ByteBuffer.allocate(noisy.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(noisy) }.array())
    val source = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val p = position + i; val v = if (p in 0 until length) noisy[p.toInt()] else 0f; into[offset + i * 2] = v; into[offset + i * 2 + 1] = v }
        }
    }
    fun rms(values: FloatArray) = Math.sqrt(values.sumOf { it.toDouble() * it } / values.size)
    var failed = false
    for ((name, effect) in listOf("LavaSR" to AudioEffect.make(EffectKind.studioVoice).copy(amount = 1.0), "VoiceFixer" to AudioEffect.make(EffectKind.studioRestoration).copy(amount = 1.0))) {
        val lava = if (effect.kind == EffectKind.studioVoice) OnnxLavaNetwork(File(models, "LavaSRv2.onnx").path, 4) else null
        val voiceFixer = if (effect.kind == EffectKind.studioRestoration) OnnxVoiceFixerNetwork(File(models, "VoiceFixer.onnx").path, 4) else null
        val target = File(work, "$name.f32")
        val started = System.nanoTime()
        OfflineAudioPipeline.write(source, length, listOf(effect), { error("no RNNoise") }, target, studio = StudioModels(StudioNativeDSP, lava, voiceFixer, bank))
        val seconds = (System.nanoTime() - started) / 1e9
        lava?.close(); voiceFixer?.close()
        val out = FloatArray((length * 2).toInt()); ProcessedAudio(target).use { it.read(0, out, 0, length.toInt()) }
        val left = FloatArray(length.toInt()) { out[it * 2] }
        val finite = out.all { it.isFinite() }
        // Correlation with the clean voice: the restored voice must still be this voice.
        var dot = 0.0; var a = 0.0; var b = 0.0
        for (i in clean.indices) { dot += clean[i] * left[i]; a += clean[i] * clean[i]; b += left[i].toDouble() * left[i] }
        val correlation = dot / Math.sqrt(a * b)
        val ratio = rms(left) / rms(noisy)
        // VoiceFixer resynthesises the voice with a vocoder: its waveform is not
        // the input's, so it is compared with upstream's own restore (check-voicefixer.py).
        val voiced = if (effect.kind == EffectKind.studioVoice) correlation > 0.9 else true
        val ok = finite && target.length() == length * 8 && voiced && ratio in 0.05..20.0 && out.indices.step(2).all { out[it] == out[it + 1] }
        println(String.format("%s: %.1f s for %.1f s of sound, level ×%.2f, correlation with the clean voice %.3f, dual mono kept %b → %s",
            name, seconds, length / 48000.0, ratio, correlation, out.indices.step(2).all { out[it] == out[it + 1] }, if (ok) "ok" else "FAIL"))
        failed = failed || !ok
    }
    if (failed) throw IllegalStateException("LavaSR or VoiceFixer failed end to end")
    println("PASS: LavaSR and VoiceFixer end to end")
}
