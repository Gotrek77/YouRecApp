package yourec

import java.util.UUID
import kotlin.math.abs

private fun t(seconds: Double) = EditTime.seconds(seconds)

/// Ported from the iPhone app's audioImportCensorTests (the model part), plus
/// a check of the samples the mixer makes.
fun censorTests() {
    val voice = SourceMedia(filename = "voice.wav", duration = 4.0, audioOnly = true)
    val project = Project("Audio censor QA"); project.addAudioSource(voice)
    val baseline = project.edit
    val id = baseline.timeline.insertAudio(voice, EditTime.zero)
    project.commit(baseline)
    val scope = TimelineSelection(audio = setOf(id), range = EditInterval(t(1.0), t(2.0)))
    val settings = AudioCensorSettings()
    val edit = baseline.copy()
    val effectID = UUID.randomUUID()
    AudioCensor.censor(edit.timeline, settings, scope, effectID)
    project.commit(edit); project.validate()
    require(project.schemaVersion >= 9, "Older builds must reject rather than silently drop censor edits")
    val decoded = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(project.edit)))))
    require(decoded.timeline.audio[0].censors == edit.timeline.audio[0].censors && decoded == project.edit, "Censor survives project persistence")
    require(String(JSON.write(ProjectCoding.encode(project.edit))).contains("\"censorRegions\"") && !String(JSON.write(ProjectCoding.encode(baseline))).contains("censorRegions"),
        "Censor regions use the iPhone key, and are absent when there are none")
    val split = edit.timeline.copy()
    split.split(t(1.5), TimelineSelection(audio = setOf(id)))
    require(split.audio.size == 2 && split.audio.all { it.censors.size == 1 }, "Splitting preserves censor on both halves")
    val right = split.audio[1].id
    AudioCensor.censor(split, null, TimelineSelection(audio = setOf(right)), removingOnly = effectID)
    require(split.audio[0].censors.size == 1 && split.audio[1].censors.isEmpty(), "Removing a split censor affects only its own audio section")
    split.move(TimelineSelection(audio = setOf(id)), t(2.0))
    require(AudioCensor.spans(split, split.audio[0])[0].start == 3.0, "Censor follows moved audio")
    split.validate(project.sources)
    val clipboard = TimelinePaste.copied(edit.timeline, scope)
    val pasted = baseline.timeline.copy(); TimelinePaste.paste(pasted, clipboard, t(2.0))
    pasted.validate(project.sources)
    require(pasted.audio.last().censors.first().id != effectID, "Pasted censor has independent effect identity")
    val layered = baseline.timeline.copy()
    layered.insertAudio(voice, EditTime.zero)
    layered.audio = layered.audio.mapIndexed { i, item -> if (i == 1) item.copy(volumeDB = 3.0) else item }
    AudioCensor.censor(layered, settings, TimelineSelection(audio = layered.audio.map { it.id }.toSet(), range = EditInterval(t(1.0), t(2.0))), effectID)
    require(layered.audio.flatMap { AudioCensor.spans(layered, it) }.count { it.emitsTone } == 1, "One censor across overlapping selected tracks emits one beep")
    require(!AudioCensor.spans(layered, layered.audio[0])[0].emitsTone && AudioCensor.spans(layered, layered.audio[1])[0].emitsTone,
        "The loudest selected track carries a shared beep, independent of identity order")
    requireFailure<ProjectError.InvalidDocument>("An out-of-range tone is refused") {
        val wrong = edit.timeline.copy(); wrong.audio = wrong.audio.map { it.copy(censors = it.censors.map { r -> r.copy(settings = r.settings.copy(frequency = 20.0)) }) }
        wrong.validate(project.sources)
    }

    // Samples: a constant voice falls silent inside the range and a 1 kHz sine at -18 dBFS replaces it.
    val constant = object : PCMSource { override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) { java.util.Arrays.fill(into, offset, offset + frames * 2, 0.25f) } }
    val mix = AudioMix(edit.timeline) { constant }
    val out = FloatArray(4 * 48000 * 2); mix.render(0, out, 4 * 48000)
    fun at(seconds: Double) = out[(seconds * 48000).toInt() * 2]
    require(abs(at(0.5) - 0.25f) < 1e-6 && abs(at(3.0) - 0.25f) < 1e-6, "Outside the range the voice is untouched")
    val inside = (48000 + 960 until 96000 - 960).map { out[it * 2] }
    val peak = inside.maxOf { abs(it) }; val amplitude = Math.pow(10.0, -18.0 / 20)
    require(abs(peak - amplitude) < 0.01 && inside.count { abs(it - 0.25f) < 1e-4 } < 50, "Inside the range only the tone is heard, at its level")
    val crossings = inside.zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
    require(crossings in 958..961, "The tone is the chosen 1 kHz ($crossings cycles in 0.96 s)")
    require(abs(out[(48000 - 480) * 2] - 0.25f) < 1e-6 && abs(out[48000 * 2 - 2]) < 0.26f, "The voice is removed sample-exactly, with a short fade only outside the range")
    for (wave in CensorWaveform.values()) for (envelope in CensorEnvelope.values()) {
        val tone = AudioCensorSettings(waveform = wave, envelope = envelope, frequency = 3000.0)
        val values = (0 until 4800).map { tone.oscillator(it / 48000.0, 48000.0) * tone.envelopeValue(it / 48000.0, 0.1) }
        require(values.all { it.isFinite() && abs(it) <= 1.2 }, "The ${wave.raw} ${envelope.raw} tone stays bounded")
    }
}
