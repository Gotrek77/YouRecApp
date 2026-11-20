package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private fun t(seconds: Double) = EditTime.seconds(seconds)

/// Enhance audio: the iPhone app's chain model, its filters, the RNNoise
/// framing (with stand-in models) and the mixer reading processed sound.
fun enhanceTests() {
    // Filters.
    val boost = AudioChainProcessor(listOf(AudioEffect.make(EffectKind.gain).copy(amount = 6.0)))
    require(abs(boost.sample(0.25f, 0) - 0.25f * 10.0.pow(6.0 / 20).toFloat()) < 1e-6, "Output gain scales by its decibels")
    val flat = AudioChainProcessor(listOf(AudioEffect.make(EffectKind.equalizer)))
    require((0 until 100).all { val x = sin(it * 0.3).toFloat(); abs(flat.sample(x, 0) - x) < 1e-6 }, "A 0 dB EQ band leaves the sound exactly as it was")
    val lowCut = AudioChainProcessor(listOf(AudioEffect.make(EffectKind.highPass)))
    var last = 1f; repeat(48000) { last = lowCut.sample(0.5f, 0) }
    require(abs(last) < 1e-4, "The low-cut removes a constant offset")
    val bypassed = AudioChainProcessor(listOf(AudioEffect.make(EffectKind.gain).copy(amount = -24.0, enabled = false)))
    require(bypassed.sample(0.3f, 1) == 0.3f, "A bypassed stage does nothing")
    val made = AudioEffect.make(EffectKind.voiceIsolation)
    require(made.amount == 1.0 && made.model == AudioModel.rnnoise && made.available, "Noise reduction defaults to full RNNoise")
    require(AudioEffect.make(EffectKind.highPass).frequency == 80.0 && AudioEffect.make(EffectKind.highPass).settingPrimary(1.0).frequency == 500.0, "Low-cut runs from 20 Hz to 500 Hz")
    require(AudioEffect.make(EffectKind.gain).settingPrimary(0.75).amount == 12.0 && AudioEffect.equalizerFrequency(1.0) == 12000.0, "Gain and EQ controls use the iPhone scales")
    require(AudioEffect.make(EffectKind.studioVoice).available && AudioEffect.make(EffectKind.studioRestoration).available, "LavaSR and VoiceFixer are offered as on iPhone")
    require(made.copy(model = AudioModel.deepFilter).available, "DeepFilterNet3 runs here")
    require(!made.copy(model = AudioModel.rnnoise.copy(runtime = "other")).supported, "A model is identified by weights and runtime too")
    require(!AudioEffect(kind = EffectKind("future")).supported, "An unknown kind is not processed")

    // Pipeline framing. A stand-in model one frame late: output = the frame before.
    class Late : FrameDenoiser {
        private val held = FloatArray(OfflineAudioPipeline.frame)
        override fun process(input: FloatArray, output: FloatArray) { System.arraycopy(held, 0, output, 0, held.size); System.arraycopy(input, 0, held, 0, held.size) }
        override fun close() { }
    }
    class Silent : FrameDenoiser {
        override fun process(input: FloatArray, output: FloatArray) { java.util.Arrays.fill(output, 0f) }
        override fun close() { }
    }
    val length = 1000L   // two whole frames and a partial one
    val voice = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val p = position + i; val v = if (p in 0 until length) sin(p * 0.05).toFloat() * 0.5f else 0f
                into[offset + i * 2] = v; into[offset + i * 2 + 1] = -v }
        }
    }
    fun run(effects: List<AudioEffect>, model: () -> FrameDenoiser): FloatArray {
        val out = ArrayList<Float>()
        OfflineAudioPipeline.process(voice, length, effects, model, { block, count -> for (i in 0 until count * 2) out.add(block[i]) })
        return out.toFloatArray()
    }
    val original = FloatArray((length * 2).toInt()).also { voice.read(0, it, 0, length.toInt()) }
    val identity = run(listOf(made), { Late() })
    require(identity.size == original.size && identity.indices.all { identity[it] == original[it] }, "Wet frame n pairs with dry frame n−1: a perfect model gives back every sample, length kept")
    val twice = run(listOf(made, made.copy(id = UUID.randomUUID()), AudioEffect.make(EffectKind.gain)), { Late() })
    require(twice.size == original.size && twice.indices.all { twice[it] == original[it] }, "Repeated models and filters keep alignment")
    val blended = run(listOf(made.copy(amount = 0.25)), { Silent() })
    require(blended.indices.all { abs(blended[it] - 0.75f * original[it]) < 1e-6 }, "Amount blends the model output with the recording")
    val zero = run(listOf(made.copy(amount = 0.0), AudioEffect.make(EffectKind.studioVoice).copy(amount = 0.0)), { throw IllegalStateException("no model at zero amount") })
    require(zero.indices.all { zero[it] == original[it] }, "Zero amount is an exact bypass, without loading a model")
    requireFailure<AudioProcessingError.UnavailableModel>("A stage without its network is refused, not skipped") { run(listOf(AudioEffect.make(EffectKind.studioVoice)), { Late() }) }
    studioTests()
    requireFailure<AudioProcessingError.UnavailableModel>("DeepFilterNet3 never plays through RNNoise") { run(listOf(made.copy(model = AudioModel.deepFilter)), { Late() }) }
    deepFilterTests()
    val file = java.io.File.createTempFile("enhanced", ".f32"); file.delete()
    OfflineAudioPipeline.write(voice, length, listOf(AudioEffect.make(EffectKind.gain).copy(amount = -6.0)), { Late() }, file)
    val readBack = FloatArray(1200 * 2); ProcessedAudio(file).use { it.read(-100, readBack, 0, 1200) }
    val half = 10.0.pow(-6.0 / 20).toFloat()
    require(readBack[0] == 0f && abs(readBack[100 * 2 + 20] - original[20] * half) < 1e-6 && readBack[1100 * 2] == 0f, "Processed sound reads back in source frames, silent outside")
    file.delete()
    require(OfflineAudioPipeline.key("a", listOf(made)) != OfflineAudioPipeline.key("a", listOf(made.copy(amount = 0.5)))
        && OfflineAudioPipeline.key("a", listOf(made)) == OfflineAudioPipeline.key("a", listOf(made, made.copy(id = UUID.randomUUID(), enabled = false))), "The cache name follows the active chain")

    // Timeline model.
    val take = SourceMedia(filename = "voice.wav", duration = 4.0, audioOnly = true)
    val project = Project("Enhance QA"); project.addAudioSource(take)
    val baseline = project.edit
    val id = baseline.timeline.insertAudio(take, EditTime.zero)
    project.commit(baseline)
    val edit = baseline.copy()
    val chain = listOf(made, AudioEffect.make(EffectKind.highPass), AudioEffect(kind = EffectKind("futureFilter"), amount = 3.0, frequency = 900.0, implementationVersion = 2,
        model = AudioModel("future-model", "00", "future-runtime")))
    val effectID = UUID.randomUUID()
    edit.timeline.enhance(chain, TimelineSelection(audio = setOf(id), range = EditInterval(t(1.0), t(3.0))), effectID)
    project.commit(edit); project.validate()
    val json = String(JSON.write(ProjectCoding.encode(project.edit)))
    val decoded = ProjectCoding.editState(Fields.of(JSON.parse(json.toByteArray())))
    require(decoded == project.edit && json.contains("\"futureFilter\"") && json.contains("\"weightsSHA256\""), "Chains, unknown filters and models survive persistence as written")
    require(AudioEnhancements.unavailable(project.edit.timeline)?.kind == EffectKind("futureFilter"), "The unknown filter is named as what cannot be processed")
    val inner = edit.timeline.copy()
    inner.enhance(listOf(AudioEffect.make(EffectKind.gain)), TimelineSelection(audio = setOf(id), range = EditInterval(t(1.5), t(2.0))))
    val regions = inner.audio[0].enhancements
    require(regions.size == 3 && regions[0].id == effectID && regions[2].id == effectID && regions[0].interval.end == t(1.5) && regions[2].interval.start == t(2.0),
        "A new chain inside another splits it, both sides keeping the old chain")
    val cleared = inner.copy(); cleared.enhance(emptyList(), TimelineSelection(audio = setOf(id)))
    require(cleared.audio[0].enhancements.isEmpty(), "An empty chain removes Enhance audio")
    val split = edit.timeline.copy(); split.split(t(2.0), TimelineSelection(audio = setOf(id)))
    require(split.audio.size == 2 && split.audio.all { it.enhancements.size == 1 } && split.audio[1].enhancements[0].interval == EditInterval(EditTime.zero, t(1.0)),
        "Splitting keeps the chain on both halves, in each half's own time")
    split.validate(project.sources)
    require(split.compile().any { it.interval.start == t(1.0) } && split.compile().any { it.interval.end == t(3.0) }, "Chain edges are interval edges")
    val pasted = baseline.timeline.copy()
    TimelinePaste.paste(pasted, TimelinePaste.copied(edit.timeline, TimelineSelection(audio = setOf(id), range = EditInterval(t(1.0), t(2.0)))), t(3.0))
    pasted.validate(project.sources)
    require(pasted.audio.last().enhancements.single().id != effectID, "A pasted chain has its own identity")
    requireFailure<ProjectError.InvalidDocument>("An out-of-range gain is refused") {
        val wrong = edit.timeline.copy()
        wrong.audio = wrong.audio.map { it.copy(enhancements = it.enhancements.map { r -> r.copy(effects = listOf(AudioEffect.make(EffectKind.gain).copy(amount = 40.0))) }) }
        wrong.validate(project.sources)
    }

    // Mixer: inside the chain the processed version plays, outside the original.
    val mixEdit = baseline.copy()
    mixEdit.timeline.enhance(listOf(AudioEffect.make(EffectKind.gain).copy(amount = -12.0)), TimelineSelection(audio = setOf(id), range = EditInterval(t(1.0), t(2.0))))
    fun constant(level: Float) = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) { java.util.Arrays.fill(into, offset, offset + frames * 2, level) } }
    var asked: List<AudioEffect>? = null
    val mix = AudioMix(mixEdit.timeline, { _, effects -> asked = effects; constant(0.125f) }) { constant(0.5f) }
    val out = FloatArray(4 * 48000 * 2); mix.render(0, out, 4 * 48000)
    require(out[(0.5 * 48000).toInt() * 2] == 0.5f && out[(1.5 * 48000).toInt() * 2] == 0.125f && out[(2.5 * 48000).toInt() * 2] == 0.5f, "The chain plays only over its range")
    require(asked?.single()?.amount == -12.0, "The mixer asks for the item's chain")
    val unprepared = AudioMix(mixEdit.timeline, { _, _ -> null }) { constant(0.5f) }
    val plain = FloatArray(48000 * 2); unprepared.render(48000, plain, 48000)
    require(plain.all { it == 0.5f }, "Without processed sound the preview plays the original")
    println("Enhance audio: ok")
}

/// DeepFilterNet3's DSP around a stand-in network. Parity with upstream's own
/// network and DSP is checked by scripts/dfn3-parity (it needs ONNX Runtime).
private fun deepFilterTests() {
    // FFT of 960 points against the plain DFT.
    val n = 960; val random = java.util.Random(3)
    val re = FloatArray(n) { random.nextFloat() - 0.5f }; val im = FloatArray(n) { random.nextFloat() - 0.5f }
    val outRe = FloatArray(n); val outIm = FloatArray(n)
    MixedRadixFFT(n).transform(re, im, outRe, outIm, -1)
    var worst = 0.0
    for (k in listOf(0, 1, 7, 240, 479, 480, 481, 959)) {
        var sr = 0.0; var si = 0.0
        for (j in 0 until n) { val a = -2 * Math.PI * k * j / n; sr += re[j] * Math.cos(a) - im[j] * Math.sin(a); si += re[j] * Math.sin(a) + im[j] * Math.cos(a) }
        worst = maxOf(worst, abs(sr - outRe[k]), abs(si - outIm[k]))
    }
    require(worst < 1e-3, "The 960-point FFT agrees with the DFT ($worst)")

    // A network that passes the spectrum through: mask 1 everywhere, deep filter
    // weight 1 on the frame itself. The sound must come back unchanged, in place.
    val dsp = DeepFilterDSP(FloatArray(481 * 32) { 1f / 481 }, FloatArray(32 * 481) { 1f / 32 }, FloatArray(32), FloatArray(96) { 1f })
    val calls = ArrayList<Int>()
    class Pass : DeepFilterModel {
        override fun run(erb: FloatArray, spec: FloatArray, frames: Int, mask: FloatArray, coefs: FloatArray) {
            calls.add(frames)
            java.util.Arrays.fill(mask, 0, frames * 32, 1f); java.util.Arrays.fill(coefs, 0, frames * 96 * 10, 0f)
            for (j in 0 until frames) for (k in 0 until 96) coefs[((j * 96 + k) * 5 + 2) * 2] = 1f
        }
        override fun close() { }
    }
    val length = 48000L * 3 + 123
    val voice = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val p = position + i; val v = if (p in 0 until length) (sin(p * 0.031) * 0.4 + sin(p * 0.0071) * 0.2).toFloat() else 0f
                into[offset + i * 2] = v; into[offset + i * 2 + 1] = v * 0.5f }
        }
    }
    val original = FloatArray((length * 2).toInt()).also { voice.read(0, it, 0, length.toInt()) }
    for (amount in listOf(1.0, 0.4)) {
        calls.clear()
        val out = ArrayList<Float>()
        OfflineAudioPipeline.process(voice, length, listOf(AudioEffect.make(EffectKind.voiceIsolation).copy(model = AudioModel.deepFilter, amount = amount)), { error("no RNNoise") },
            { block, count -> for (i in 0 until count * 2) out.add(block[i]) }, deepFilter = { DeepFilterStream(dsp, Pass(), chunk = 100, warm = 30) })
        require(out.size == original.size && out.indices.all { abs(out[it] - original[it]) < 1e-4 }, "A pass-through network gives back the recording in place, at amount $amount")
        require(calls.take(2) == listOf(100, 100) && calls.drop(2).all { it == 130 }, "Each channel's chunks run after their warm-up context ($calls)")
    }
}

/// LavaSR's and VoiceFixer's windowing, reflection, cropping and blending, with
/// stand-ins for the iPhone app's DSP and the networks; and the resampler.
private fun studioTests() {
    val length = 48000L * 9 + 777   // several windows, a partial last one
    fun tone(p: Long) = (sin(p * 2 * Math.PI * 440 / 48000) * 0.3 + sin(p * 2 * Math.PI * 1700 / 48000) * 0.1).toFloat()
    val stereo = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val p = position + i; val v = if (p in 0 until length) tone(p) else 0f
                into[offset + i * 2] = v; into[offset + i * 2 + 1] = if (p in 0 until length) v * 0.5f else 0f }
        }
    }
    // Resampler: 48 → 44.1 → 48 kHz keeps a band-limited signal.
    val down = Resampler(48000, 44100); val up = Resampler(44100, 48000)
    val low = ArrayList<Float>(); down.convert(stereo, length, { b, c -> for (i in 0 until c * 2) low.add(b[i]) })
    require(low.size.toLong() == down.outputLength(length) * 2 && down.outputLength(length) == Math.ceil(length * 44100.0 / 48000).toLong(), "44.1 kHz length")
    val lowSource = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        for (i in 0 until frames * 2) { val k = position * 2 + i; into[offset + i] = if (k >= 0 && k < low.size) low[k.toInt()] else 0f } } }
    val back = ArrayList<Float>(); up.convert(lowSource, down.outputLength(length), { b, c -> for (i in 0 until c * 2) back.add(b[i]) })
    var signal = 0.0; var error = 0.0
    for (p in 4800 until length - 4800) { val a = tone(p).toDouble(); signal += a * a; val e = back[(p * 2).toInt()] - a; error += e * e }
    require(10 * Math.log10(signal / error) > 60, "A round trip through 44.1 kHz stays within -60 dB (${10 * Math.log10(signal / error)} dB)")

    // LavaSR: the merge halves its input; the result must be half the recording, in place.
    var lastWindow = FloatArray(0)
    val dsp = object : StudioDSP {
        override fun lavaFeatures(audio: FloatArray, mel: FloatArray) { }
        override fun lavaReconstruct(real: FloatArray, imaginary: FloatArray, audio: FloatArray) { }
        override fun lavaMerge(original: FloatArray, restored: FloatArray, output: FloatArray) { for (i in original.indices) output[i] = original[i] * 0.5f }
        override fun voiceFixerFeatures(audio: FloatArray, bank: FloatArray, mel: FloatArray) { lastWindow = audio.copyOf() }
    }
    var lavaRuns = 0
    val lava = object : LavaNetwork { override fun run(mel: FloatArray, real: FloatArray, imaginary: FloatArray) { lavaRuns++ }; override fun close() { } }
    val half = ArrayList<Float>()
    StudioVoice.process(stereo, length, 1f, dsp, lava, { b, c -> for (i in 0 until c * 2) half.add(b[i]) })
    require(half.size.toLong() == length * 2 && (0 until length.toInt()).all { half[it * 2] == tone(it.toLong()) * 0.5f && half[it * 2 + 1] == tone(it.toLong()) * 0.25f },
        "LavaSR windows land in place, every sample once")
    require(lavaRuns == 2 * Math.ceil(length.toDouble() / StudioVoice.stride).toInt(), "Each window runs once per channel")
    val dual = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        stereo.read(position, into, offset, frames); for (i in 0 until frames) into[offset + i * 2 + 1] = into[offset + i * 2] } }
    lavaRuns = 0
    StudioVoice.process(dual, length, 1f, dsp, lava, { _, _ -> })
    require(lavaRuns == Math.ceil(length.toDouble() / StudioVoice.stride).toInt(), "Dual mono runs the network once")

    // VoiceFixer: a vocoder that returns its input window gives back the
    // recording, through 44.1 kHz and the crossfades, at full amount.
    val voiceFixer = object : VoiceFixerNetwork {
        override fun run(mel: FloatArray, audio: FloatArray) {
            val trim = (audio.size - StudioRestoration.context) / 2
            for (j in audio.indices) { val k = j - trim; audio[j] = if (k in lastWindow.indices) lastWindow[k] else 0f }
        }
        override fun close() { }
    }
    val folder = java.nio.file.Files.createTempDirectory("studio").toFile()
    val target = java.io.File(folder, "out.f32")
    OfflineAudioPipeline.write(stereo, length, listOf(AudioEffect.make(EffectKind.studioRestoration).copy(amount = 1.0), AudioEffect.make(EffectKind.gain).copy(amount = -6.0)),
        { error("no RNNoise") }, target, studio = StudioModels(dsp, null, voiceFixer, FloatArray(1025 * 128)))
    val restored = FloatArray((length * 2).toInt()); ProcessedAudio(target).use { it.read(0, restored, 0, length.toInt()) }
    require(target.length() == length * 8 && folder.listFiles()!!.size == 1, "VoiceFixer then gain leaves one file of the recording's length")
    val gain = Math.pow(10.0, -6.0 / 20)
    signal = 0.0; error = 0.0
    for (p in 4800 until length - 4800) { val a = tone(p) * gain; signal += a * a; val e = restored[(p * 2).toInt()] - a; error += e * e }
    require(10 * Math.log10(signal / error) > 50, "VoiceFixer windows land in place across crossfades (${10 * Math.log10(signal / error)} dB)")
    folder.deleteRecursively()
}
