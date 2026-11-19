package yourec

import java.io.File
import java.util.UUID
import kotlin.math.abs

fun require(condition: Boolean, message: String) {
    if (!condition) throw IllegalStateException("FAIL: $message")
}
inline fun <reified E : Throwable> requireFailure(message: String, operation: () -> Unit) {
    try { operation() } catch (e: Throwable) { if (e is E) return; throw IllegalStateException("FAIL: $message (${e.javaClass.simpleName})") }
    throw IllegalStateException("FAIL: $message")
}

fun main(arguments: Array<String>) {
    val root = File(arguments.firstOrNull() ?: throw IllegalArgumentException("usage: core-tests <empty directory>"))
    jsonTests()
    zoomTransitionTests()
    trackTimelineTests()
    recordingHistoryTests()
    persistenceTests(File(root, "Projects"))
    retentionTests(File(root, "Retention"))
    mixTests()
    journalTests(File(root, "Journal"))
    videoToolsTests()
    clipboardTests()
    archiveTests(File(root, "Archive"))
    censorTests()
    transitionTests()
    println("PASS: YouRec Android core")
}

private fun jsonTests() {
    val text = """{"b":[1,2.5,-3e2,true,null],"a":"q\"\\\né","c":{}}"""
    val value = JSON.parse(text.toByteArray())
    require(String(JSON.write(value)) == """{"a":"q\"\\\né","b":[1,2.5,-300,true,null],"c":{}}""", "JSON round-trips with sorted keys")
    require(JSON.write(JSON.parse(JSON.write(value))).contentEquals(JSON.write(value)), "Written JSON reads back to the same bytes")
    require(String(JSON.write(listOf(0.1, 1.0, 1e-7, 240000L))) == "[0.1,1,1.0E-7,240000]", "Numbers keep their exact value")
    for (bad in listOf("", "{", "[1,]", "{\"a\":1,}", "nul", "\"x", "1 2", "{\"a\" 1}")) {
        requireFailure<JSON.Malformed>("Malformed JSON is rejected: $bad") { JSON.parse(bad.toByteArray()) }
    }
    requireFailure<ProjectError.InvalidDocument>("A short UUID is not an identity") { Fields.uuid("1-2-3-4-5") }
}

private fun persistenceTests(root: File) {
    val disk = ProjectDisk(root)
    val a = SourceMedia(filename = "first.mp4", duration = 4.0, events = listOf(CaptureEvent(time = 1.0, kind = "bookmark", detail = "Bookmark")))
    val b = SourceMedia(filename = "second.mp4", duration = 2.5)
    val project = Project("Persistence")
    project.addRecording(a); project.addRecording(b)
    disk.save(project)
    val head = File(disk.folder(project.id), "project.json")
    val first = head.readBytes()
    disk.save(project)
    require(head.readBytes().contentEquals(first), "Saving an unchanged project writes the same document")
    val header = Fields.of(JSON.parse(first))
    require(header.int("automaticCanvasVersion") == 1 && header.int("recordingHistoryVersion") == 1 && header.list("snapshotFiles").size == 1
        && header.string("id") == project.id.text && header.fields("exportSettings").string("codec") == "H.264", "The manifest carries the iPhone app's header fields")

    val cut = TimelineEditing.split(project.edit, 1.0)
    project.commit(cut)
    val (removed, _) = TimelineEditing.removing(0.0, 1.0, project.edit)
    project.commit(removed)
    val volume = project.edit; volume.timeline.setVolume(-6.0, false, volume.timeline.entireSelection())
    volume.timeline.setFade(AudioFadeEdge.FadeIn, 0.5, volume.timeline.entireSelection())
    project.commit(volume)
    project.undo()
    disk.save(project)
    var reopened = disk.read(head)
    require(reopened.historyCursor == 2 && reopened.canUndo && reopened.canRedo && reopened.edit == project.edit, "Undo and redo survive closing after an undo")
    require(reopened.history == project.history && reopened.sources == project.sources, "Every edit state and source round-trips exactly")
    require(reopened.edit.timeline.resolvedBookmarks.size == 1, "Capture bookmarks survive in the reopened edit")
    reopened.redo()
    require(reopened.schemaVersion == 11 && reopened.edit.timeline.audio.all { it.volumeDB == -6.0 }, "Redo restores the volume edit; fades raise the schema")
    val summary = disk.read(head, summary = true)
    require(summary.deferredHistory?.count == 4 && summary.canUndo && summary.canRedo && summary.edit == project.edit, "A library summary loads only the current edit")
    requireFailure<ProjectError.InvalidDocument>("A summary cannot be saved over the full history") { disk.save(summary) }

    // A new edit after Undo replaces the abandoned redo path.
    reopened.undo(); reopened.undo()
    val branch = TimelineEditing.split(reopened.edit, 3.0); reopened.commit(branch)
    require(!reopened.canRedo && reopened.history.size == 3, "Conventional redo is no longer offered after a divergent edit")
    disk.save(reopened)
    require(disk.read(head).history == reopened.history, "The replaced branch reopens as saved")
    require(RecoveryFile.revisions(File(disk.folder(project.id), ".revisions")).size in 1..5, "Recent project indexes are kept, and bounded")

    // History files are immutable and named by content.
    val history = File(disk.folder(project.id), "History")
    // The snapshot of the current edit: directory order differs between machines.
    val victim = File(history, reopened.revision)
    val original = victim.readBytes()
    victim.writeBytes("{}".toByteArray())
    requireFailure<ProjectError.InvalidDocument>("A snapshot that no longer matches its hash is refused") { disk.read(head) }
    victim.writeBytes(original)
    reopened = disk.read(head)

    // Editing data from tools this build lacks is refused by name, never dropped.
    val snapshot = Fields.of(JSON.parse(original))
    @Suppress("UNCHECKED_CAST")
    fun tampered(change: (MutableMap<String, Any?>) -> Unit): ByteArray {
        val copy = JSON.parse(original) as MutableMap<String, Any?>
        change((copy["state"] as MutableMap<String, Any?>)["timeline"] as MutableMap<String, Any?>)
        return JSON.write(copy)
    }
    @Suppress("UNCHECKED_CAST")
    fun firstOf(timeline: MutableMap<String, Any?>, lane: String) = (timeline[lane] as MutableList<Any?>)[0] as MutableMap<String, Any?>
    require(snapshot.fields("state").fields("timeline").list("transitions").isEmpty(), "Unused relations are written as empty lists")
    for ((tool, change) in listOf<Pair<String, (MutableMap<String, Any?>) -> Unit>>(
        "Wipe transitions" to { it["transitions"] = ProjectCoding.encode(TrackTimeline().also { t -> t.transitions = listOf(TimelineTransition(null, null,
            ClipTransition(style = TransitionStyle.Wipe), EditTime.zero)) })["transitions"] },
        "Subtitles" to { it["subtitles"] = mapOf("x" to 1) },
        "Enhance audio" to { firstOf(it, "audio")["enhancements"] = listOf(mapOf("x" to 1)) })) {
        try { EditSnapshot.decode(tampered(change)); require(false, "$tool must be refused") }
        catch (e: ProjectError.UnsupportedTools) { require(e.message!!.contains(tool), "The refusal names $tool") }
    }
    // Fields added by later iPhone versions are recognised too, down to one clip or one sound part.
    @Suppress("UNCHECKED_CAST")
    for ((words, change) in listOf<Pair<String, (MutableMap<String, Any?>) -> Unit>>(
        "Color" to { (firstOf(it, "video")["content"] as MutableMap<String, Any?>)["colorRegions"] = listOf(mapOf("start" to 0)) },
        "range" to { (firstOf(it, "video")["content"] as MutableMap<String, Any?>)["effectWindows"] = listOf("A", listOf<Any?>()) },
        "range" to { (firstOf(it, "video")["content"] as MutableMap<String, Any?>)["framingVariants"] = listOf(mapOf("crop" to mapOf("x" to 0))) },
        "spanning" to { firstOf(it, "video")["spanningEffects"] = listOf("A") },
        "spanning" to { firstOf(it, "audio")["spanningEffects"] = listOf("A") })) {
        try { EditSnapshot.decode(tampered(change)); require(false, "$words must be refused") }
        catch (e: ProjectError.UnsupportedTools) { require(e.message!!.contains(words), "The refusal mentions $words") }
    }
    val current = JSON.parse(head.readBytes()) as MutableMap<String, Any?>
    current["schemaVersion"] = 16L
    require(Project.readManifest(JSON.write(current)) { File(history, it).readBytes() }.schemaVersion == 16, "A schema 16 project that uses only ported tools opens")
    fun warning(peak: Double?) = ExportReceipt(filename = "m.mp4", projectID = project.id, projectTitle = "", revision = "", sourceChecksums = emptyMap(), width = 2, height = 2,
        framesPerSecond = 30, codec = ExportSettings.Codec.H264, resolution = ExportSettings.Resolution.Project, audioPeak = peak).audioWarning
    require(warning(null) == null && warning(1.0) == null && warning(1.05) == null && warning(1.26)!!.contains("2.0 dB"),
        "An export warns only when the mix peaks at least half a decibel above full scale")
    val newer = JSON.parse(head.readBytes()) as MutableMap<String, Any?>
    newer["schemaVersion"] = 17L
    requireFailure<ProjectError.NewerProject>("A newer schema is refused") { Project.readManifest(JSON.write(newer)) { File(history, it).readBytes() } }

    // A document written by the iPhone app: uppercase identities, explicit optionals.
    val sourceID = "0E5F1C0A-7B1D-4C55-9A40-0C1F0B2D3E4F"; val clipID = "5B2A9E10-0000-4000-8000-00000000AB12"; val soundID = "5B2A9E10-0000-4000-8000-00000000AB92"
    val swiftSnapshot = """{"captureCursor":0,"state":{"timeline":{"audio":[{"enhancements":[],"followsVideo":"$clipID","id":"$soundID","localOrigin":{"ticks":0},"mapping":{"runs":[{"duration":{"ticks":720000},"silent":false,"sourceEnd":3,"sourceStart":0}]},"muted":false,"occurrenceID":"$clipID","sourceID":"$sourceID","start":{"ticks":0},"volumeDB":0}],"bookmarks":[{"anchor":{"project":{"_0":{"ticks":240000}}},"id":"11111111-2222-4333-8444-555555555555","label":"Intro"}],"generated":[],"links":[{"id":"$clipID","members":["$clipID","$soundID"]}],"placements":[],"transitions":[],"video":[{"content":{"end":3,"id":"$clipID","sourceID":"$sourceID","start":0},"layer":0,"localOrigin":{"ticks":0},"mapping":{"runs":[{"duration":{"ticks":720000},"silent":false,"sourceEnd":3,"sourceStart":0}]},"occurrenceID":"$clipID","start":{"ticks":0}}]}}}""".toByteArray()
    val name = RecoveryFile.hash(swiftSnapshot) + ".json"
    val swiftHeader = """{"automaticCanvasVersion":1,"captureLedger":[],"createdAt":784111111.5,"exportSettings":{"codec":"H.265","frameRate":60,"resolution":"1080p"},"historyCursor":0,"id":"AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE","recordingHistoryVersion":1,"schemaVersion":6,"snapshotFiles":["$name"],"sources":[{"duration":3,"filename":"take.mov","id":"$sourceID","recordedAt":784111100.25}],"title":"From iPhone","updatedAt":784111112}""".toByteArray()
    val imported = Project.readManifest(swiftHeader) { swiftSnapshot }
    require(imported.title == "From iPhone" && imported.edit.duration == 3.0 && imported.exportSettings.frameRate == ExportSettings.FrameRate.High
        && imported.edit.timeline.resolvedBookmarks.single().time == EditTime.seconds(1.0) && imported.sources[0].recordedAt.reference == 784111100.25,
        "An iPhone project document opens with its timeline, bookmark and settings")
    require(imported.revision == name, "An untouched snapshot keeps the bytes and name it arrived with")
    require(TrackTimeline.counterpart(UUID.fromString(clipID)).text == soundID, "Counterpart identities agree with the iPhone app")

    // Complete movies no document mentions are found again, and nothing else.
    val folder = disk.folder(project.id)
    for (file in listOf("first.mp4", "second.mp4", "orphan.mp4", ".hidden.mp4", "recovered-1.mp4", "notes.txt")) File(folder, file).writeText("x")
    require(disk.unindexedRecordings(reopened).map { it.name } == listOf("orphan.mp4"), "Only an unindexed finished take is offered for recovery")
}

private fun retentionTests(root: File) {
    val disk = ProjectDisk(File(root, "Projects"))
    val project = Project("Retention"); project.addRecording(SourceMedia(filename = "take.mp4", duration = 1.0))
    disk.save(project)
    val day = 86400.0; val now = Moment(800_000_000.0)
    disk.moveToDeleted(project, now)
    require(!disk.folder(project.id).exists() && File(disk.deletedRoot, project.id.text + "/project.json").exists(), "Deleting moves the complete project aside")
    val record = disk.deletionRecord(project.id)
    require(record.dateIsKnown && record.date == now, "The deletion time is recorded")
    require(!disk.eraseExpiredDeletion(record, Moment(now.reference + 29 * day)), "Nothing is erased before thirty days")
    disk.restoreDeleted(project)
    require(disk.read(File(disk.folder(project.id), "project.json")).edit == project.edit, "Restoring returns the same project")
    disk.moveToDeleted(project, Moment(now.reference + day))
    require(!disk.eraseExpiredDeletion(record, Moment(now.reference + 40 * day)), "A stale confirmation cannot erase a project deleted again later")
    val second = disk.deletionRecord(project.id)
    require(disk.eraseExpiredDeletion(second, Moment(now.reference + 40 * day)) && !File(disk.deletedRoot, project.id.text).exists(), "An expired confirmed deletion is erased")
    File(disk.deletedRoot, ".purging-" + UUID.randomUUID().text).mkdirs()
    disk.finishPendingDeletions()
    require(disk.deletedRoot.listFiles()!!.isEmpty(), "An interrupted erase finishes on the next launch")
}

private fun journalTests(root: File) {
    root.mkdirs()
    val movie = File(root, "take-1.mp4"); val file = TakeJournal.file(movie)
    require(TakeJournal.isJournal(file) && TakeJournal.movie(file) == movie && !TakeJournal.isJournal(movie), "A journal is hidden beside its movie and names it")
    val writer = TakeJournal.Writer(file)
    writer.track(true, TakeJournal.Track("video/avc", 1080, 1920, 0, 0, listOf(byteArrayOf(1, 2, 3), byteArrayOf(4))))
    writer.track(false, TakeJournal.Track("audio/mp4a-latm", 0, 0, 48000, 2, listOf(byteArrayOf(9, 8))))
    val payloads = (0 until 50).map { n -> ByteArray(10 + n * 37) { (it * 7 + n).toByte() } }
    for ((n, bytes) in payloads.withIndex()) {
        // A window into a larger buffer, as an encoder hands samples over.
        val buffer = java.nio.ByteBuffer.allocate(bytes.size + 8); buffer.position(5); buffer.put(bytes); buffer.position(5); buffer.limit(5 + bytes.size)
        writer.sample(n % 3 != 0, n * 1000L, if (n % 10 == 0) 1 else 0, buffer)
        require(buffer.position() == 5 && buffer.remaining() == bytes.size, "Journaling leaves the encoder's buffer as it was")
    }
    writer.close(delete = false)
    class Replay(journal: File) {
        val tracks = ArrayList<Pair<Boolean, TakeJournal.Track>>(); val samples = ArrayList<Pair<Long, ByteArray>>(); var keys = 0
        val count = TakeJournal.read(journal, { video, track -> tracks.add(video to track) }, { video, time, flags, data, size ->
            require(video == ((time / 1000) % 3 != 0L), "Each sample returns on its own track")
            samples.add(time to data.copyOf(size)); keys += flags and 1
        })
    }
    val whole = Replay(file)
    require(whole.count == 50 && whole.keys == 5 && whole.samples.map { it.first } == (0 until 50).map { it * 1000L }
        && whole.samples.zip(payloads).all { (a, b) -> a.second.contentEquals(b) }, "Every sample replays in order, byte for byte")
    require(whole.tracks.size == 2 && whole.tracks[0].first && whole.tracks[0].second.width == 1080 && whole.tracks[0].second.configuration[0].contentEquals(byteArrayOf(1, 2, 3))
        && whole.tracks[1].second.sampleRate == 48000 && whole.tracks[1].second.channels == 2, "Both track descriptions replay before the samples")
    // An interruption can cut the file anywhere: everything complete before the cut is kept.
    val bytes = file.readBytes()
    var previous = 50
    for (cut in listOf(bytes.size - 1, bytes.size - 400, bytes.size / 2, 200, 5, 3, 0)) {
        val torn = File(root, ".torn-$cut.journal"); torn.writeBytes(bytes.copyOf(cut))
        val part = Replay(torn)
        require(part.count <= previous && part.count < 50 && part.samples.zip(payloads).all { (a, b) -> a.second.contentEquals(b) }, "A journal cut at byte $cut replays only whole samples")
        previous = part.count
    }
    require(previous == 0, "An empty or headless journal yields nothing")
    val damaged = bytes.copyOf(); damaged[bytes.size - 1] = 0
    File(root, ".damaged.journal").writeBytes(damaged)
    require(Replay(File(root, ".damaged.journal")).count == 49, "A record without its end mark is not delivered")
    val closing = TakeJournal.Writer(File(root, ".gone.journal")); closing.close(delete = true)
    require(!File(root, ".gone.journal").exists(), "A take that closes normally leaves no journal")
}
