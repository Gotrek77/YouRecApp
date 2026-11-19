package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

private class Tone(val hertz: Double, val amplitude: Float = 0.5f) : PCMSource {
    override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
        for (i in 0 until frames) {
            val value = if (position + i < 0) 0f else (amplitude * sin(2 * Math.PI * hertz * (position + i) / AudioMix.sampleRate)).toFloat()
            into[offset + i * 2] = value; into[offset + i * 2 + 1] = value
        }
    }
}
private fun near(a: Double, b: Double, tolerance: Double = 1e-6) = abs(a - b) <= tolerance

fun videoToolsTests() {
    framingTests()
    speedTests()
    stretchTests()
    placementTests()
}

private fun placementTests() {
    val hd = CanvasSize(1920.0, 1080.0)
    val a = SourceMedia(filename = "a.mp4", duration = 6.0); val b = SourceMedia(filename = "b.mp4", duration = 6.0)
    val edit = EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 6.0), TimelineClip(sourceID = b.id, start = 0.0, end = 4.0)))
    val under = edit.timeline.video[0].id; val over = edit.timeline.video[1].id
    edit.timeline.unlink(edit.timeline.entireSelection())
    edit.timeline.move(TimelineSelection(video = setOf(over)), EditTime.seconds(-5.0))     // b now covers 1…5 over a
    val both = setOf(under, over)
    require(RenderPlan(edit).layers(EditTime.seconds(2.0)).map { it.picture.item } == listOf(over), "Without an arrangement the front picture covers the frame")
    val seed = edit.timeline.placementSeed(both, EditTime.seconds(2.0))
    require(seed.videos[under] == VideoPlacement(order = 0) && seed.videos[over] == VideoPlacement(order = 1), "A new arrangement starts from the plain stack")
    val plain = edit.copy(); plain.timeline.setPlacement(seed)
    require(plain.timeline.placements.isEmpty(), "The plain stack is not stored")

    val side = edit.copy()
    side.timeline.setPlacement(seed.copy(videos = mapOf(under to VideoPlacement.layout(1, 0, 2), over to VideoPlacement.layout(1, 1, 2))))
    side.timeline.validate(listOf(a, b))
    val layers = RenderPlan(side).layers(EditTime.seconds(2.0))
    require(layers.map { it.picture.item } == listOf(under, over) && layers.all { it.placement != null } && layers.map { it.picture.sourceTime } == listOf(2.0, 1.0),
        "Arranged pictures are all drawn, back to front, each at its own source time")
    require(RenderPlan(side).layers(EditTime.seconds(0.5)).single().placement == null && RenderPlan(side).layers(EditTime.seconds(5.5)).single().picture.item == under,
        "Outside the overlap a picture has the whole frame again")
    val left = side.timeline.placements[0].videos[under]!!; val right = side.timeline.placements[0].videos[over]!!
    require(left.window(hd) == Rectangle(0.0, 0.0, 960.0, 1080.0) && right.window(hd) == Rectangle(960.0, 0.0, 960.0, 1080.0), "Side by side splits the frame in two")
    val fill = left.transform(hd)
    require(near(fill.x(960.0, 540.0), 480.0) && near(fill.y(960.0, 540.0), 540.0) && near(fill.a, 1.0), "Fill shows the middle of the picture at full size inside its half")
    val fit = left.copy(fill = false)
    require(near(fit.transform(hd).a, 0.5) && fit.pictureWindow(hd) == Rectangle(0.0, 270.0, 960.0, 540.0), "Fit shows the whole picture, smaller, inside its half")
    val cropped = left.copy(edgeCrop = PlacementEdgeCrop(left = 0.25, bottom = 0.5))
    require(cropped.visibleWindow(hd) == Rectangle(240.0, 0.0, 720.0, 540.0) && cropped.transform(hd) == left.transform(hd), "An edge crop hides part of the window without moving the picture")
    val corner = VideoPlacement.layout(3, 1, 2)
    require(near(corner.width, 0.32) && near(corner.x, 0.78) && near(corner.y, 0.78) && VideoPlacement.layout(3, 0, 2) == VideoPlacement(order = 0), "The inset preset puts the second picture in the lower right corner")
    require(VideoPlacement.ordered(listOf(under, over), mapOf(under to VideoPlacement(order = 1), over to VideoPlacement(order = 0))) == listOf(over, under), "Order in an arrangement decides which picture is in front")
    val swapped = side.copy()
    swapped.timeline.setPlacement(side.timeline.placements[0].let { it.copy(videos = it.videos.mapValues { (id, value) -> value.copy(order = if (id == under) 1 else 0) }) })
    require(RenderPlan(swapped).layers(EditTime.seconds(2.0)).map { it.picture.item } == listOf(over, under), "Bring to front changes the drawing order only")

    require(ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(side))))) == side, "Arrangements round-trip in the document")
    val json = String(JSON.write(ProjectCoding.encode(side.timeline)))
    require(json.contains("\"videos\":[\"") && json.contains("\"framing\":{"), "An arrangement is written as the iPhone app writes a dictionary keyed by identity")
    val cut = TimelineEditing.split(side, 3.0)
    require(cut.timeline.placements.size == 2 && RenderPlan(cut).layers(EditTime.seconds(2.0)).size == 2 && RenderPlan(cut).layers(EditTime.seconds(4.0)).size == 2
        && RenderPlan(cut).layers(EditTime.seconds(4.0)).all { it.placement != null }, "A split through an overlap keeps the arrangement on both sides")
    cut.timeline.validate(listOf(a, b))
    val apart = side.copy(); apart.timeline.move(TimelineSelection(video = setOf(over)), EditTime.seconds(30.0))
    require(apart.timeline.placements.isEmpty(), "An arrangement ends when its pictures no longer overlap")
    val gone = TimelineEditing.deleting(TimelineSelection(video = setOf(over)), side).first
    require(gone.timeline.placements.isEmpty() && RenderPlan(gone).layers(EditTime.seconds(2.0)).single().placement == null, "Deleting one of the pictures gives the other its frame back")
    // Two parts of the same recording on screen at once need a decoder each.
    val twin = EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 3.0), TimelineClip(sourceID = a.id, start = 3.0, end = 6.0)))
    twin.timeline.unlink(twin.timeline.entireSelection()); twin.timeline.move(TimelineSelection(video = setOf(twin.timeline.video[1].id)), EditTime.seconds(-3.0))
    twin.timeline.setPlacement(twin.timeline.placementSeed(twin.timeline.video.map { it.id }.toSet(), EditTime.zero).let { region ->
        region.copy(videos = region.videos.mapValues { (_, value) -> VideoPlacement.layout(2, value.order, 2) }) })
    require(RenderPlan(twin).layers(EditTime.seconds(1.0)).map { it.slot } == listOf(0, 1), "Two pictures from one recording are read separately")
    val project = Project("Placement"); project.sources = listOf(a, b); project.history = listOf(edit)
    val trimmed = side.copy(); trimmed.timeline.setPlacement(side.timeline.placements[0].let { it.copy(videos = it.videos + (under to cropped)) })
    project.commit(trimmed)
    require(project.schemaVersion == 7, "An edge crop raises the schema, so older readers do not drop it")
}

private fun framingTests() {
    val hd = CanvasSize(1920.0, 1080.0); val tall = CanvasSize(1080.0, 1920.0)
    val whole = CropSettings().transform(hd, hd)
    require(near(whole.x(0.0, 0.0), 0.0) && near(whole.y(0.0, 0.0), 0.0) && near(whole.x(1920.0, 1080.0), 1920.0) && near(whole.y(1920.0, 1080.0), 1080.0), "An untouched crop of matching footage is the whole picture")
    require(CropSettings().isFullFrame(hd, hd) && !CropSettings(zoom = 1.01).isFullFrame(hd, hd) && !CropSettings().isFullFrame(hd, tall)
        && !CropSettings(rotation = 180.0).isFullFrame(hd, hd), "Only an unzoomed, unrotated crop of the same shape is the full frame")
    require(CropSettings().isFullFrame(CanvasSize(1919.0, 1079.0), hd), "Even-pixel rounding of odd imported media does not create a crop")
    val centre = CropSettings(zoom = 2.0).rect(hd, hd)
    require(centre == Rectangle(480.0, 270.0, 960.0, 540.0), "Zoom 2 shows the middle half of each side")
    val corner = CropSettings(x = 0.98, y = 0.02, zoom = 2.0)
    val area = corner.rect(hd, hd)
    require(near(area.x + area.width, 1920.0) && near(area.y, 0.0) && near(corner.clamped(hd, hd).x, 0.75) && near(corner.clamped(hd, hd).y, 0.25), "A crop panned past an edge stops at the edge")
    // A crop fills the frame at every shape, zoom and angle without showing anything outside the source.
    for (shape in OutputShape.values()) for (zoom in listOf(1.0, 1.7, 8.0)) for (angle in listOf(0.0, 17.0, 90.0, -135.0, 180.0)) {
        val canvas = OutputShape.canvas(hd, shape.aspect)
        val crop = CropSettings(x = 0.98, y = 0.02, zoom = zoom, rotation = angle)
        val map = crop.transform(hd, canvas)
        // Invert by solving for the source point under each canvas corner.
        val det = map.a * map.d - map.b * map.c
        for ((cx, cy) in listOf(0.0 to 0.0, canvas.width to 0.0, 0.0 to canvas.height, canvas.width to canvas.height)) {
            val dx = cx - map.tx; val dy = cy - map.ty
            val sx = (map.d * dx - map.c * dy) / det; val sy = (-map.b * dx + map.a * dy) / det
            require(sx > -0.001 && sx < hd.width + 0.001 && sy > -0.001 && sy < hd.height + 0.001, "Crop ${shape.title} ×$zoom at $angle° stays inside the source")
        }
    }
    require(OutputShape.canvas(hd, OutputShape.Portrait.aspect) == tall && OutputShape.canvas(hd, OutputShape.Square.aspect) == CanvasSize(1080.0, 1080.0)
        && OutputShape.canvas(hd, OutputShape.Feed.aspect) == CanvasSize(1080.0, 1350.0) && OutputShape.canvas(tall, null) == tall, "Output shapes keep the short-edge resolution")
    require(CropSettings.normalizedRotation(190.0) == -170.0 && CropSettings.normalizedRotation(180.0) == 180.0 && CropSettings.normalizedRotation(-540.0) == -180.0
        && CropSettings.normalizedRotation(Double.NaN) == 0.0, "Angles are kept between −180° and 180°")
    val fit = Affine.fit(tall, hd)
    require(near(fit.x(0.0, 0.0), (1920 - 607.5) / 2) && near(fit.y(0.0, 0.0), 0.0) && near(fit.x(1080.0, 1920.0), (1920 + 607.5) / 2) && near(fit.y(1080.0, 1920.0), 1080.0),
        "Footage of another shape keeps its whole picture, centred")
    val source = UUID.randomUUID()
    val flipped = TimelineClip(sourceID = source, start = 0.0, end = 1.0, mirror = MirrorSettings(horizontal = true)).pictureTransform(hd, hd)
    require(near(flipped.x(0.0, 0.0), 1920.0) && near(flipped.x(1920.0, 0.0), 0.0) && near(flipped.y(0.0, 1080.0), 1080.0), "A horizontal mirror swaps left and right of the finished frame")
    val both = TimelineClip(sourceID = source, start = 0.0, end = 1.0, crop = CropSettings(zoom = 2.0), mirror = MirrorSettings(vertical = true)).pictureTransform(hd, hd)
    require(near(both.x(480.0, 270.0), 0.0) && near(both.y(480.0, 270.0), 1080.0), "A mirror acts after the crop")
}

private fun speedTests() {
    val source = SourceMedia(filename = "speed.mp4", duration = 4.0)
    val a = TimelineClip(sourceID = source.id, start = 0.0, end = 2.0); val b = TimelineClip(sourceID = source.id, start = 2.0, end = 4.0)
    val original = EditState(listOf(a, b))
    val first = original.timeline.selection(a.id)
    val fast = TimelineEditing.settingSpeed(2.0, first, original)
    require(fast.duration == 3.0 && fast.timeline.video[0].mapping.duration == EditTime.seconds(1.0) && fast.timeline.video[1].start == EditTime.seconds(1.0)
        && fast.timeline.audio.map { it.start } == fast.timeline.video.map { it.start } && fast.timeline.audio[0].mapping == fast.timeline.video[0].mapping,
        "Doubling a clip's speed halves it, and everything linked after it follows")
    fast.timeline.validate(listOf(source))
    require(original.duration == 4.0, "A tool edits a draft, never the edit it came from")
    require(RenderPlan(fast).picture(EditTime.seconds(0.5))?.sourceTime == 1.0 && RenderPlan(fast).picture(EditTime.seconds(1.5))?.sourceTime == 2.5, "A retimed clip shows the source frame for its own clock")
    require(TimelineEditing.settingSpeed(1.0, first, fast) == original, "Returning to normal speed restores the edit exactly")
    val slow = TimelineEditing.settingSpeed(0.1, TimelineSelection(), original)
    require(slow.duration == 16.0 && slow.clips.all { it.speed?.rate == 0.25 }, "Speed is limited to a quarter, and with nothing selected applies to every clip")
    val cut = TimelineEditing.split(fast, 0.5)
    require(cut.clips[0].end == 1.0 && cut.clips[1].start == 1.0 && cut.clips[0].speed == cut.clips[1].speed && cut.duration == 3.0, "A split inside a retimed clip cuts at the frame under the playhead")
    val (longer, _) = TimelineEditing.trimming(cut.timeline.video[0].id, false, 0.25, TimelineEditing.deleting(cut.timeline.selection(cut.timeline.video[1].id), cut).first, listOf(source))
    require(longer.clips[0].end == 1.5 && longer.timeline.video[0].mapping.duration == EditTime.seconds(0.75), "Revealing trimmed footage on a retimed clip uses its speed")
    // A picture laid over another is not downstream of it.
    val layered = original.copy(); layered.timeline.unlink(layered.timeline.selection(b.id))
    layered.timeline.move(TimelineSelection(video = setOf(b.id)), EditTime.seconds(-1.5))
    val under = TimelineEditing.settingSpeed(2.0, layered.timeline.selection(a.id), layered)
    require(under.timeline.video.first { it.id == b.id }.start == EditTime.seconds(0.5), "Retiming a clip leaves an overlay and detached parts where they are")
    val bookmark = original.copy(); bookmark.timeline.addBookmark(EditTime.seconds(3.0))
    require(TimelineEditing.settingSpeed(2.0, first, bookmark).timeline.resolvedBookmarks.single().time == EditTime.seconds(2.0), "Bookmarks stay on their frame through a speed change")
    val faded = original.copy(); faded.timeline.setFade(AudioFadeEdge.FadeIn, 1.5, faded.timeline.selection(a.id))
    require(near(TimelineEditing.settingSpeed(2.0, first, faded).timeline.audio[0].fadeSeconds(AudioFadeEdge.FadeIn), 1.0), "A fade longer than its retimed part keeps its shape, compressed")

    val tools = TimelineEditing.settingCrop(CropSettings(x = 0.3, y = 0.6, zoom = 2.5, rotation = 12.0), first, TimelineEditing.settingMirror(true, false, first, fast))
    val restored = ProjectCoding.editState(Fields.of(JSON.parse(JSON.write(ProjectCoding.encode(tools)))))
    require(restored == tools && restored.clips[0].crop?.zoom == 2.5 && restored.clips[0].mirror?.horizontal == true && restored.clips[1].crop == null, "Speed, Crop and Mirror round-trip in the document")
    require(TimelineEditing.settingMirror(false, false, first, tools).clips[0].mirror == null && TimelineEditing.settingCrop(null, first, tools).clips[0].crop == null, "Turning a tool off removes it from the clip")
    val again = TimelineEditing.settingCrop(CropSettings(zoom = 3.0), first, tools)
    require(again.clips[0].crop?.id == tools.clips[0].crop?.id, "Editing a crop keeps its identity")
    @Suppress("UNCHECKED_CAST")
    for ((key, words) in listOf("motion" to "animated Crop", "activeRange" to "Crop on a range")) {
        val json = JSON.parse(JSON.write(ProjectCoding.encode(tools))) as MutableMap<String, Any?>
        val clip = (((json["timeline"] as MutableMap<String, Any?>)["video"] as List<Any?>)[0] as MutableMap<String, Any?>)["content"] as MutableMap<String, Any?>
        (clip["crop"] as MutableMap<String, Any?>)[key] = mapOf("x" to 1)
        try { ProjectCoding.editState(Fields.of(json)); require(false, "$words must be refused") } catch (e: ProjectError.UnsupportedTools) { require(e.message!!.contains(words), "The refusal names $words") }
    }
    val shaped = TimelineEditing.settingShape(CanvasSize(1080.0, 1920.0), original)
    require(shaped.outputSize == CanvasSize(1080.0, 1920.0) && original.outputSize == null && TimelineEditing.settingShape(null, shaped) == original, "A project shape is one undoable setting")
    val project = Project("Tools"); project.sources = listOf(source); project.history = listOf(original)
    project.commit(TimelineEditing.settingCrop(CropSettings(zoom = 9.0), first, original))
    requireFailure<ProjectError.InvalidDocument>("A crop outside its limits is not a valid document") { project.validate() }
}

private fun stretchTests() {
    val rate = AudioMix.sampleRate
    fun crossings(samples: FloatArray, frames: Int): Int { var count = 0; for (i in 1 until frames) if (samples[(i - 1) * 2] < 0 && samples[i * 2] >= 0) count++; return count }
    fun level(samples: FloatArray, frames: Int): Double { var sum = 0.0; for (i in 0 until frames) sum += samples[i * 2] * samples[i * 2]; return Math.sqrt(sum / frames) }
    val tone = Tone(220.0)
    val same = FloatArray(rate * 2); TimeStretch(tone, 0, 1.0).read(0, same, 0, rate)
    val plain = FloatArray(rate * 2); tone.read(0, plain, 0, rate)
    require((2000 until rate).all { abs(same[it * 2] - plain[it * 2]) < 1e-3 }, "At unchanged speed the stretcher returns the recording")
    for (speed in listOf(0.25, 0.5, 1.5, 2.0, 4.0)) {
        val out = FloatArray(rate * 2); TimeStretch(tone, 0, speed).read(0, out, 0, rate)
        require(abs(crossings(out, rate) - 220) <= 4, "At ×$speed a 220 Hz tone still sounds at 220 Hz (${crossings(out, rate)} cycles)")
        require(abs(level(out, rate) - 0.5 / Math.sqrt(2.0)) < 0.05, "At ×$speed the level is unchanged")
        var jump = 0f; for (i in 1 until rate) jump = maxOf(jump, abs(out[i * 2] - out[(i - 1) * 2]))
        require(jump < 0.02, "At ×$speed the windows join without clicks")
        val pieces = FloatArray(rate * 2); val reader = TimeStretch(tone, 0, speed); var done = 0
        while (done < rate) { val n = minOf(777, rate - done); reader.read(done.toLong(), pieces, done * 2, n); done += n }
        require(pieces.contentEquals(out), "At ×$speed the result does not depend on block size")
    }
    // A sweep tells whether the right part of the recording is heard at the right time.
    val sweep = object : PCMSource {
        override fun read(position: Long, into: FloatArray, offset: Int, frames: Int) {
            for (i in 0 until frames) { val t = (position + i).toDouble() / rate; val v = (0.5 * sin(2 * Math.PI * (200 * t + 100 * t * t))).toFloat(); into[offset + i * 2] = v; into[offset + i * 2 + 1] = v }
        }
    }
    val doubled = FloatArray(rate * 2); TimeStretch(sweep, 0, 2.0).read(0, doubled, 0, rate)
    // After half a second of output at ×2 the source is one second in, where the sweep is at 400 Hz.
    val window = FloatArray(4800 * 2); System.arraycopy(doubled, (rate / 2 - 2400) * 2, window, 0, 4800 * 2)
    require(abs(crossings(window, 4800) - 40) <= 2, "At ×2 the output is twice as far into the recording (${crossings(window, 4800)} cycles in 0.1 s)")

    // In the project mix a retimed part lasts its new length, at its own pitch, and what follows is untouched.
    val source = SourceMedia(filename = "tone.mp4", duration = 4.0)
    val edit = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 2.0), TimelineClip(sourceID = source.id, start = 2.0, end = 4.0)))
    val fast = TimelineEditing.settingSpeed(2.0, edit.timeline.selection(edit.timeline.video[0].id), edit)
    val mix = AudioMix(fast.timeline) { tone }
    val out = FloatArray(3 * rate * 2); mix.render(0, out, 3 * rate)
    val head = FloatArray(rate * 2); System.arraycopy(out, 0, head, 0, rate * 2)
    require(abs(crossings(head, rate) - 220) <= 4, "A clip at double speed keeps its pitch in the mix")
    require((0 until rate).all { abs(out[(rate + it) * 2] - plain[it * 2] * 0f - (0.5 * sin(2 * Math.PI * 220 * (2 * rate + it) / rate)).toFloat()) < 1e-4 }, "The clip after a retimed one plays its own samples exactly")
}
