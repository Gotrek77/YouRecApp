package yourec

import java.util.UUID
import kotlin.math.abs

private fun t(seconds: Double) = EditTime.seconds(seconds)

/// The iPhone app's subtitle model: estimated word times, grouping into
/// captions, corrections that keep surviving words, and words that follow
/// their sound through edits. Plus the speech plan for the model.
fun subtitleTests() {
    require(SubtitleTiming.tokens("Hello, world! It's fine.") == listOf("Hello,", "world!", "It's", "fine."), "Punctuation stays with its word (${SubtitleTiming.tokens("Hello, world! It's fine.")})")
    val words = SubtitleTiming.estimate("one two three", listOf(1.0..2.0, 3.0..4.0))
    require(words.size == 3 && words.first().start == 1.0 && words.last().end == 4.0 && words.none { it.start < 3.0 && it.end > 2.0 }, "Words fill voiced time only, never the silence between")
    val grouped = SubtitleTiming.group(SubtitleTiming.estimate("a b c d e f g h", listOf(0.0..4.0)), maxWords = 3)
    require(grouped.groupBy { it.phraseID }.values.map { it.size } == listOf(3, 3, 2), "Captions hold at most the chosen number of words")
    val sentence = SubtitleTiming.group(SubtitleTiming.estimate("Stop. Go on", listOf(0.0..2.0)), 6)
    require(sentence[0].phraseID != sentence[1].phraseID, "A sentence end starts a new caption")
    val original = SubtitleTiming.group(SubtitleTiming.estimate("the quick brown fox", listOf(0.0..2.0)), 6)
    val fixed = SubtitleTiming.corrected(original, "the quick red fox")
    require(fixed.map { it.text } == listOf("the", "quick", "red", "fox") && fixed[0] == original[0] && fixed[3].start == original[3].start && fixed[3].end == original[3].end,
        "A correction keeps the surviving words' times and ids")
    require(SubtitleTiming.corrected(original, "the quick brown fox.").map { it.start } == original.map { it.start }, "Punctuation-only edits keep every time")

    // Speech plan: islands of speech, merged into bounded sections.
    val levels = FloatArray(1500) { i -> if ((i in 50..300) || (i in 330..600) || (i in 1000..1400)) 0.1f else 0.0005f }
    val chunks = SubtitleSpeechPlan.chunks(levels, 30.0)
    require(chunks.size == 2 && chunks[0].speech.size == 2 && chunks.all { it.range.endInclusive - it.range.start <= 12.001 } && abs(chunks[1].range.start - (1000 * 0.02 - 0.08)) < 1e-9,
        "Close islands share a section; a distant one has its own (${chunks.map { it.range }})")
    val long = SubtitleSpeechPlan.chunks(FloatArray(2000) { 0.1f }.also { for (i in 700..705) it[i] = 0.001f }, 40.0)
    require(long.all { it.range.endInclusive - it.range.start <= 12.001 } && long.size >= 3, "Long unbroken speech is split under 12 s")

    // Timeline: words in source time follow their sound; captions in project time.
    val take = SourceMedia(filename = "talk.mov", duration = 10.0)
    val project = Project("Subtitles QA"); project.addRecording(take)
    val edit = project.edit.copy()
    val transcribed = SubtitleTiming.estimate("hello there general kenobi", listOf(1.0..3.0))
    SubtitleEditing.install(edit.timeline, transcribed, 0.0..10.0, "Auto")
    require(edit.timeline.subtitles != null && edit.timeline.audio.single().subtitleWords.size == 4, "Installed words belong to the sound")
    val cues = SubtitleEditing.cues(edit.timeline)
    require(cues.size == 1 && cues[0].text == "hello there general kenobi" && abs(cues[0].start - 1.0) < 1e-6 && abs(cues[0].end - 3.0) < 1e-6, "One caption, at its time (${cues.map { it.text to it.start }})")
    project.commit(edit); project.validate()
    val decoded = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(project.edit)))))
    require(decoded == project.edit && project.schemaVersion >= 8, "Subtitles survive persistence; older readers refuse them")
    val moved = edit.timeline.copy()
    val cut = TimelineEditing.rangeSelection(0.0, 0.5, edit)
    require(cut != null, "A range before the speech")
    SubtitleEditing.regroup(moved, 2)
    require(SubtitleEditing.cues(moved).map { it.text } == listOf("hello there", "general kenobi"), "Regrouping before any edit changes the caption size")
    SubtitleEditing.correct(moved, SubtitleEditing.cues(moved)[1], "General Kenobi!")
    require(SubtitleEditing.cues(moved)[1].text == "General Kenobi!", "A corrected caption shows the new words")
    val split = edit.timeline.copy(); split.split(t(2.0), TimelineSelection(video = split.video.map { it.id }.toSet(), audio = split.audio.map { it.id }.toSet()))
    val halves = SubtitleEditing.cues(split)
    // As on iPhone, a word the cut runs through shows on both sides, each clipped at the cut.
    require(halves.map { it.text } == listOf("hello there general", "general kenobi") && abs(halves[0].end - 2.0) < 1e-6 && abs(halves[1].start - 2.0) < 1e-6
        && !SubtitleEditing.groupingAvailable(split), "A split keeps every word at its time; regrouping waits for re-transcription")
    val pasted = edit.timeline.copy()
    TimelinePaste.paste(pasted, TimelinePaste.copied(edit.timeline, TimelineSelection(audio = edit.timeline.audio.map { it.id }.toSet(), video = edit.timeline.video.map { it.id }.toSet())), t(10.0))
    require(pasted.audio.last().subtitleWords.map { it.id }.intersect(pasted.audio.first().subtitleWords.map { it.id }.toSet()).isEmpty(), "Pasted words have their own identity")
    SubtitleEditing.remove(moved)
    require(moved.subtitles == null && moved.audio.all { it.subtitleWords.isEmpty() }, "Removing subtitles removes every word")
    // Title designs and animations in subtitles, as on iPhone: kept, drawn on every caption, their fonts kept.
    val designed = project.edit.copy()
    val look = TitleDesign.looks.first().design.copy(face = "import:" + "a".repeat(64) + ":0:Demo-Bold")
    val motion = TitleMotion(entrance = TitleMove.Drift, exit = TitleMove.Fade)
    designed.timeline.subtitles = designed.timeline.subtitles!!.copy(appearance = look, motion = motion)
    project.commit(designed); project.validate()
    val reread = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(project.edit)))))
    require(reread.timeline.subtitles?.appearance == look && reread.timeline.subtitles?.motion == motion, "Subtitle designs and animations survive persistence")
    val shown = RenderPlan(project.edit).captions(t(2.0)).single()
    require(shown.appearance == look && shown.motion == motion && shown.design == look, "Every caption is drawn with the subtitle design and animation")
    require(RenderPlan(project.edit).captions(t(2.0)).single().id == shown.id, "A caption keeps its id from frame to frame, so its drawing is reused")
    val caption = shown
    val fading = TitleMotion(exit = TitleMove.Fade, outSeconds = 0.35)
    val still = SubtitleCue.pose(caption.copy(motion = fading), t((caption.start + caption.end) / 2))
    val leaving = SubtitleCue.pose(caption.copy(motion = fading), t(caption.end - 0.1))
    require(still != null && abs(still.opacity - 1.0) < 1e-9 && leaving != null && leaving.opacity < 1.0,
        "A caption's animation runs on its own cue: whole mid-cue, fading near its end (${still?.opacity}, ${leaving?.opacity})")
    require(SubtitleCue.pose(caption.copy(motion = null), t(2.0)) == null, "A caption without animation has no pose")
    require(project.fontFiles.contains("font-" + "a".repeat(64) + ".sfnt"), "A font used only by subtitles is kept with the project (${project.fontFiles})")
    requireFailure<ProjectError.InvalidDocument>("An invalid subtitle animation is refused") {
        @Suppress("UNCHECKED_CAST") val raw = JSON.parse(JSON.write(ProjectCoding.encode(project.edit))) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") (((raw["timeline"] as MutableMap<String, Any?>)["subtitles"] as MutableMap<String, Any?>)["motion"] as MutableMap<String, Any?>)["inSeconds"] = -1.0
        ProjectCoding.editState(Fields.of(raw))
    }
    transcriberTests()
    println("Subtitles: ok")
}

/// The transcriber with a stand-in model: the project's sound at 16 kHz,
/// sections found by level, words timed within the speech of each.
private fun transcriberTests() {
    val take = SourceMedia(filename = "talk.mov", duration = 20.0)
    val project = Project("Transcriber QA"); project.addRecording(take)
    // Speech-like bursts at 2–4 s and 10–12 s of the source; silence elsewhere.
    val voice = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val t = (position + i) / 48000.0
                val v = if (t in 2.0..4.0 || t in 10.0..12.0) (Math.sin(2 * Math.PI * 220 * t) * 0.3).toFloat() else 0f
                into[offset + i * 2] = v; into[offset + i * 2 + 1] = v }
        }
    }
    val mix = AudioMix(project.edit.timeline) { voice }
    val scratch = java.io.File.createTempFile("speech", ".f32")
    val range = 1.0..15.0
    val levels = SubtitleTranscriber.extract(mix, range, scratch)
    require(scratch.length() == Math.ceil(14.0 * 16000).toLong() * 4 && levels.size == Math.ceil(14.0 * 16000 / 320).toInt(), "16 kHz mono and 20 ms levels for the range")
    val heard = ArrayList<Int>()
    val recognizer = object : SpeechRecognizer {
        override fun transcribe(samples: FloatArray, language: String?): String { heard.add(samples.size); require(language == "Italian"); return "ciao a tutti" }
        override fun close() { }
    }
    val words = SubtitleTranscriber.transcribe(scratch, levels, range, "Italian", recognizer)
    require(heard.size == 2 && words.size == 6, "Each speech section is transcribed once (${heard.size} sections, ${words.size} words)")
    require(words.take(3).all { it.start >= 1.9 && it.end <= 4.2 } && words.drop(3).all { it.start >= 9.9 && it.end <= 12.2 }, "Words fall within their speech, in project time (${words.map { it.start to it.end }})")
    scratch.delete()
}
