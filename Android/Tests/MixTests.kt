package yourec

import java.util.UUID
import kotlin.math.abs

/// A recording whose every sample states its own position.
private class Ramp(val frames: Long, val scale: Float = 1f) : PCMSource {
    override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        for (i in 0 until frames) {
            val p = position + i
            val value = if (p in 0 until this.frames) (p % 1000) / 1000f * scale else 0f
            into[offset + i * 2] = value; into[offset + i * 2 + 1] = -value
        }
    }
}

fun mixTests() {
    val rate = AudioMix.sampleRate
    val source = SourceMedia(filename = "source.mp4", duration = 4.0)
    val edit = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0)))
    val ramp = Ramp(4L * rate)
    fun mixed(timeline: TrackTimeline, start: Long, frames: Int, block: Int = frames): FloatArray {
        val mix = AudioMix(timeline) { ramp }
        val out = FloatArray(frames * 2); val part = FloatArray(block * 2)
        var done = 0
        while (done < frames) { val n = minOf(block, frames - done); mix.render(start + done, part, n); System.arraycopy(part, 0, out, done * 2, n * 2); done += n }
        return out
    }
    val whole = mixed(edit.timeline, 0, 4 * rate)
    require((0 until 4 * rate).all { whole[it * 2] == (it % 1000) / 1000f && whole[it * 2 + 1] == -whole[it * 2] }, "An uncut take mixes to exactly its own samples")
    require(mixed(edit.timeline, 0, 4 * rate, 777).contentEquals(whole), "The mix does not depend on block size")
    require(mixed(edit.timeline, 4L * rate - 10, 20).drop(20).all { it == 0f }, "Past the end of the project there is silence")

    // Cuts are sample-exact: a split changes nothing, a deletion joins the two sides.
    val split = TimelineEditing.split(edit, 1.5)
    require(mixed(split.timeline, 0, 4 * rate).contentEquals(whole), "A split alone is inaudible")
    val cut = TimelineEditing.removing(1.0, 2.0, edit).first
    val joined = mixed(cut.timeline, 0, 3 * rate)
    require(joined[(rate - 1) * 2] == whole[(rate - 1) * 2] && joined[rate * 2] == whole[2 * rate * 2] && joined[(3 * rate - 1) * 2] == whole[(4 * rate - 1) * 2],
        "A deleted range joins the sample before it to the sample after it")

    // Level, mute and fades.
    val quiet = edit.copy(); quiet.timeline.setVolume(-20.0, false, quiet.timeline.entireSelection())
    val soft = mixed(quiet.timeline, 0, rate)
    require((0 until rate).all { abs(soft[it * 2] - whole[it * 2] * 0.1f) < 1e-6f }, "Volume is a gain in decibels")
    val muted = edit.copy(); muted.timeline.setVolume(0.0, true, muted.timeline.entireSelection())
    require(mixed(muted.timeline, 0, rate).all { it == 0f }, "A muted part is silent")
    val faded = edit.copy()
    faded.timeline.setFade(AudioFadeEdge.FadeIn, 1.0, faded.timeline.entireSelection()); faded.timeline.setFade(AudioFadeEdge.FadeOut, 2.0, faded.timeline.entireSelection())
    val shaped = mixed(faded.timeline, 0, 4 * rate, 1024)
    require(shaped[0] == 0f && abs(shaped[(rate / 2 + 250) * 2] - whole[(rate / 2 + 250) * 2] * (0.5f + 250f / rate)) < 1e-5f, "Fade in rises linearly from silence")
    require(shaped[(rate + 500) * 2] == whole[(rate + 500) * 2] && abs(shaped[(3 * rate + 500) * 2] - whole[(3 * rate + 500) * 2] * (0.5f - 500f / rate / 2)) < 1e-5f, "Fade out falls to silence at the end")

    // Detached sound keeps playing under a moved picture, and overlapping sound sums.
    val layered = edit.copy()
    layered.timeline.unlink(layered.timeline.entireSelection())
    layered.timeline.audio += layered.timeline.audio[0].copy(id = UUID.randomUUID(), start = EditTime.seconds(1.0))
    val both = AudioMix(layered.timeline) { ramp }
    val sum = FloatArray(2 * rate * 2); both.render(0, sum, 2 * rate)
    require(sum[500 * 2] == whole[500 * 2] && abs(sum[(rate + 500) * 2] - (whole[(rate + 500) * 2] + whole[500 * 2])) < 1e-6f, "Overlapping sound is summed")
    require(both.peak > 1f, "The mix reports peaks above full scale instead of hiding them")

    // The picture plan agrees with the cuts.
    val plan = RenderPlan(cut)
    require(plan.duration == EditTime.seconds(3.0) && plan.frameCount(30) == 90L, "The plan covers exactly the edited duration")
    require(plan.picture(EditTime.seconds(0.5))?.sourceTime == 0.5 && plan.picture(EditTime.seconds(1.0))?.sourceTime == 2.0 && plan.picture(EditTime.seconds(3.0)) == null,
        "Each frame comes from its mapped source time, and none after the end")
    require(plan.pictureChange(EditTime.zero) == EditTime.seconds(1.0) && plan.pictureChange(EditTime.seconds(1.0)) == EditTime.seconds(3.0), "The plan reports where the picture changes")
    val gap = edit.copy(); gap.timeline.unlink(gap.timeline.entireSelection()); gap.timeline.video = emptyList()
    require(RenderPlan(gap).picture(EditTime.seconds(1.0)) == null && RenderPlan(gap).duration == EditTime.seconds(4.0), "Sound without picture is black, not missing time")
    val over = layered.copy(); over.timeline.move(TimelineSelection(over.timeline.video.map { it.id }.toSet()), EditTime.zero)
    require(RenderPlan.fit(1920, 1080, 1920, 1080) == RenderPlan.Fit(1f, 1f) && RenderPlan.fit(1080, 1920, 1920, 1080).scaleX == 0.31640625f
        && RenderPlan.fit(1920, 1080, 1080, 1920).scaleY == 0.31640625f, "Footage of another shape keeps its whole picture inside the canvas")
    require(ExportSettings.Resolution.FullHD.size(CanvasSize(2160.0, 3840.0)) == CanvasSize(1080.0, 1920.0) && ExportSettings.Resolution.Project.size(CanvasSize(1280.0, 720.0)) == CanvasSize(1280.0, 720.0)
        && ExportSettings.Resolution.HD.size(CanvasSize(1920.0, 1080.0)) == CanvasSize(1280.0, 720.0), "Export sizes keep the canvas shape and orientation")

    val builder = WaveformPeaks.Builder(100, rate)
    val block = FloatArray(rate * 2)
    for (i in 0 until rate) { val v = if (i < rate / 2) 0.25f else 1.5f; block[i * 2] = v; block[i * 2 + 1] = -v / 2 }
    builder.add(block, rate, 2); builder.add(block, 100, 2)
    val peaks = builder.build()
    require(peaks.peaks.size == 101 && abs(peaks.level(0.0, 0.4) - 0.25f) < 0.01f && peaks.level(0.6, 0.9) == 1f && peaks.level(0.45, 0.55) == 1f && peaks.level(9.0, 10.0) == 0f,
        "Waveform buckets hold the peak of their slice, clipped at full scale")
}
