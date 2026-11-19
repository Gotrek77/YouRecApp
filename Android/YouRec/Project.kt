package yourec

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.log10

/// Seconds since 1 January 2001 UTC, the form the iPhone app writes. Keeping
/// the number itself avoids rounding another device's timestamps on every save.
@JvmInline
value class Moment(val reference: Double) : Comparable<Moment> {
    companion object {
        private const val epochOffset = 978_307_200.0
        fun now() = Moment(System.currentTimeMillis() / 1000.0 - epochOffset)
        fun ofMillis(millis: Long) = Moment(millis / 1000.0 - epochOffset)
    }
    val millis: Long get() = ((reference + epochOffset) * 1000).toLong()
    override fun compareTo(other: Moment) = reference.compareTo(other.reference)
}

data class CaptureEvent(val id: UUID = UUID.randomUUID(), val time: Double, val kind: String, val detail: String) {
    companion object {
        fun recovered(events: List<CaptureEvent>, duration: Double) = events.filter { it.time.isFinite() && it.time >= 0 && it.time < duration }
    }
}

data class SourceMedia(val id: UUID = UUID.randomUUID(), val filename: String, val duration: Double, val recordedAt: Moment = Moment.now(),
                       val events: List<CaptureEvent>? = null, val sha256: String? = null, val audioOnly: Boolean? = null,
                       val isStillPhoto: Boolean? = null, val originalName: String? = null)

data class TimelineClip(val id: UUID = UUID.randomUUID(), val sourceID: UUID, val start: Double, val end: Double,
                        val speed: SpeedSettings? = null, val crop: CropSettings? = null, val mirror: MirrorSettings? = null,
                        val texts: List<TextOverlay>? = null, val color: ColorSettings? = null) {
    /// The titles to draw while this clip shows one source time.
    fun visibleTexts(sourceTime: Double) = texts?.filter { it.hasText && it.start <= sourceTime && sourceTime < it.end } ?: emptyList()
    /// Source seconds played per timeline second.
    val rate: Double get() = (speed?.rate ?: 1.0).coerceIn(0.125, 6.0)
    val duration: Double get() = (end - start) / rate
}

data class CanvasSize(val width: Double, val height: Double)

class EditState(val timeline: TrackTimeline = TrackTimeline(), var outputSize: CanvasSize? = null) {
    // Only a fixed project shape sets an output size. In automatic mode an edit may keep
    // an earlier take as its reference even after a different clip moves ahead of it.
    var canvasReference: UUID? = null
        internal set
    constructor(clips: List<TimelineClip>, outputSize: CanvasSize? = null) : this(TrackTimeline.of(clips), outputSize)

    internal val firstVideoSourceID: UUID? get() = timeline.video.minByOrNull { it.start }?.content?.sourceID
    val canvasSourceID: UUID? get() {
        val reference = canvasReference
        return if (reference != null && timeline.video.any { it.content.sourceID == reference }) reference else firstVideoSourceID
    }
    fun retainCanvasReference(previous: EditState) {
        canvasReference = null
        val reference = previous.canvasSourceID
        if (outputSize != null || reference == null || reference == firstVideoSourceID || timeline.video.none { it.content.sourceID == reference }) return
        canvasReference = reference
    }
    val clips: List<TimelineClip> get() = timeline.projectedClips
    val duration: Double get() = timeline.duration.seconds
    /// An independent draft. The receiver, usually a saved snapshot, never changes.
    fun copy() = EditState(timeline.copy(), outputSize).also { it.canvasReference = canvasReference }
    override fun equals(other: Any?) = other is EditState && timeline == other.timeline && outputSize == other.outputSize && canvasReference == other.canvasReference
    override fun hashCode() = timeline.hashCode()
}

/// Immutable edit payload. Capture is journaled independently, so a new take
/// never rewrites a historical snapshot or becomes an accidental Undo step.
class EditSnapshot(state: EditState, val captureCursor: Int) {
    private val saved = state.copy()
    val state: EditState get() = saved.copy()
    private var encoded: ByteArray? = null
    @Synchronized fun data(): ByteArray {
        encoded?.let { return it }
        return JSON.write(jsonObject("state" to ProjectCoding.encode(saved), "captureCursor" to captureCursor)).also { encoded = it }
    }
    companion object {
        fun decode(bytes: ByteArray): EditSnapshot {
            val values = Fields.of(JSON.parse(bytes))
            return EditSnapshot(ProjectCoding.editState(values.fields("state")), values.int("captureCursor")).also { it.encoded = bytes }
        }
    }
}
internal data class CaptureLedgerEntry(val clip: TimelineClip, val insertion: Boolean)
data class DeferredProjectHistory(val manifest: File, val count: Int, val cursor: Int)

class Project(var title: String) {
    companion object {
        const val latestReadableSchema = 16
        fun isSnapshotName(name: String) = name.length == 69 && name.endsWith(".json") && name.take(64).all { it in "0123456789abcdef" }
        fun readManifest(data: ByteArray, summaryFile: File? = null, load: (String) -> ByteArray): Project = ProjectCoding.project(data, summaryFile, load)
    }
    var exportSettings = ExportSettings()
    var schemaVersion = 6
    var id: UUID = UUID.randomUUID()
    var createdAt = Moment.now()
    var updatedAt = Moment.now()
    var sources: List<SourceMedia> = emptyList()
    internal var snapshots = listOf(EditSnapshot(EditState(), 0))
    internal var captureLedger: List<CaptureLedgerEntry> = emptyList()
    var deferredHistory: DeferredProjectHistory? = null
    var historyCursor = 0

    fun copy() = Project(title).also {
        it.exportSettings = exportSettings; it.schemaVersion = schemaVersion; it.id = id; it.createdAt = createdAt; it.updatedAt = updatedAt
        it.sources = sources; it.snapshots = snapshots; it.captureLedger = captureLedger; it.deferredHistory = deferredHistory; it.historyCursor = historyCursor
    }

    private fun materialized(snapshot: EditSnapshot, captureCount: Int = captureLedger.size): EditState {
        val state = snapshot.state
        for (event in captureLedger.take(captureCount).drop(snapshot.captureCursor)) {
            if (event.insertion) continue
            val addition = TrackTimeline.of(listOf(event.clip))
            val position = state.timeline.duration
            val layer = (state.timeline.video.maxOfOrNull { it.layer } ?: -1) + 1
            state.timeline.video += addition.video.map { it.copy(start = it.start + position, layer = layer) }
            state.timeline.audio += addition.audio.map { it.copy(start = it.start + position) }
            state.timeline.links += addition.links
            sources.firstOrNull { it.id == event.clip.sourceID }?.let { state.timeline.includeCaptureBookmarks(it) }
        }
        state.timeline.captureBase = captureCount
        return state
    }
    /// A complete history, for explicit fixtures and checks. Editing uses snapshots.
    var history: List<EditState>
        get() = snapshots.map { materialized(it) }
        set(value) { snapshots = value.map { EditSnapshot(it, 0) }; captureLedger = emptyList() }
    val edit: EditState get() = materialized(snapshots[historyCursor])
    /// LUT files any retained edit refers to: part of the project, like its recordings.
    val effectFiles: List<String> get() = snapshots.flatMap { snapshot -> snapshot.state.clips.mapNotNull { it.color?.imported?.filename } }.distinct().sorted()
    val mediaFiles: List<String> get() = sources.map { it.filename } + effectFiles
    val hasSourceMedia: Boolean get() = sources.isNotEmpty()
    val capturedDuration: Double get() = sources.sumOf { it.duration }
    val canUndo: Boolean get() = (deferredHistory?.cursor ?: historyCursor) > 0
    val canRedo: Boolean get() = (deferredHistory?.cursor ?: historyCursor) + 1 < (deferredHistory?.count ?: snapshots.size)
    /// The saved file name of the current edit: an identity for export receipts.
    val revision: String get() = RecoveryFile.hash(snapshots[historyCursor].data()) + ".json"

    fun commit(requested: EditState) {
        val previous = edit
        val state = requested.copy()
        state.retainCanvasReference(previous)
        if (state == previous) return
        // An older reader would pick the first timeline picture and silently reframe the movie.
        if (state.canvasReference != null) schemaVersion = maxOf(schemaVersion, 10)
        // Older readers must not silently discard independent placement crops.
        if (state.timeline.placements.any { region -> region.videos.values.any { it.edgeCrop != null } }) schemaVersion = maxOf(schemaVersion, 7)
        if (state.timeline.audio.any { it.fadeIn != null || it.fadeOut != null }) schemaVersion = maxOf(schemaVersion, 11)
        if (sources.any { it.audioOnly == true }) schemaVersion = maxOf(schemaVersion, 9)
        val base = state.timeline.captureBase ?: captureLedger.size
        snapshots = snapshots.take(historyCursor + 1) + EditSnapshot(state, minOf(captureLedger.size, maxOf(0, base)))
        historyCursor += 1
        updatedAt = Moment.now()
    }
    /// Recording adds footage to every retained edit state without advancing the
    /// undo cursor or discarding redo. Undo must never remove a captured segment.
    fun addRecording(source: SourceMedia) {
        schemaVersion = maxOf(schemaVersion, 2)
        sources = sources + source
        includeRecording(source)
    }
    /// Imported sound is kept with the originals; placing it is an ordinary edit.
    fun addAudioSource(source: SourceMedia) {
        schemaVersion = maxOf(schemaVersion, 9)
        sources = sources + source; updatedAt = Moment.now()
    }
    private fun includeRecording(source: SourceMedia) {
        captureLedger = captureLedger + CaptureLedgerEntry(TimelineClip(sourceID = source.id, start = 0.0, end = source.duration), false)
        updatedAt = Moment.now()
    }
    fun undo() { if (canUndo) { historyCursor -= 1; updatedAt = Moment.now() } }
    fun redo() { if (canRedo) { historyCursor += 1; updatedAt = Moment.now() } }

    // Explicit recovery only. An unused source might have been deliberately
    // removed, so loading a document must never automatically add it back.
    fun restoreUnusedRecording(id: UUID) {
        val source = sources.firstOrNull { it.id == id }
        if (source == null || source.audioOnly == true || history.any { state -> state.clips.any { it.sourceID == id } }) throw ProjectError.InvalidDocument()
        includeRecording(source)
    }

    fun validate() {
        fun require(ok: Boolean) { if (!ok) throw ProjectError.InvalidDocument() }
        val reserved = setOf("project.json", "integrity.json", "cache", "processing", "previews", "exports")
        require(snapshots.all { it.captureCursor in 0..captureLedger.size })
        require(captureLedger.all { event -> sources.any { it.id == event.clip.sourceID } })
        require(schemaVersion in 1..latestReadableSchema && snapshots.isNotEmpty() && historyCursor in snapshots.indices)
        require(sources.map { it.id }.toSet().size == sources.size && sources.map { it.filename.lowercase() }.toSet().size == sources.size)
        for (source in sources) {
            require(source.duration.isFinite() && source.duration > 0 && source.duration <= 604800 && (source.originalName?.length ?: 0) <= 255)
            require(source.filename == File(source.filename).name && source.filename.isNotEmpty() && !source.filename.startsWith(".")
                && !source.filename.contains("\\") && source.filename.lowercase() !in reserved)
            source.sha256?.let { hash -> require(hash.length == 64 && hash.all { it in "0123456789abcdefABCDEF" }) }
            require((source.events ?: emptyList()).all { it.time.isFinite() && it.time >= 0 && it.time <= source.duration + 1 })
        }
        for (state in history) {
            state.timeline.validate(sources)
            state.outputSize?.let { size ->
                require(size.width.isFinite() && size.height.isFinite() && size.width in 2.0..16384.0 && size.height in 2.0..16384.0
                    && size.width % 2 == 0.0 && size.height % 2 == 0.0)
            }
            require(state.duration.isFinite() && state.duration <= 604800)
            for (clip in state.clips) {
                val source = sources.firstOrNull { it.id == clip.sourceID }
                require(source != null && clip.start.isFinite() && clip.end.isFinite() && clip.start >= 0 && clip.end > clip.start && clip.end <= source.duration + 0.001)
                require(clip.speed?.let { it.rate.isFinite() && it.rate in 0.25..4.0 } ?: true)
                require(clip.crop?.valid ?: true)
                require(clip.color?.valid ?: true)
                require(clip.texts?.let { texts -> texts.map { it.id }.toSet().size == texts.size && texts.all { it.valid(source!!.duration) } } ?: true)
            }
        }
    }

    /// Write every snapshot under its content hash, then describe them in a small header.
    fun manifest(write: (String, ByteArray) -> Unit): ByteArray {
        if (deferredHistory != null) throw ProjectError.InvalidDocument()
        val names = snapshots.map { snapshot ->
            val data = snapshot.data()
            (RecoveryFile.hash(data) + ".json").also { write(it, data) }
        }
        return JSON.write(ProjectCoding.header(this, names))
    }
}

data class ExportSettings(val codec: Codec = Codec.H264, val resolution: Resolution = Resolution.Project, val frameRate: FrameRate = FrameRate.Standard) {
    enum class Codec(val title: String, val mime: String) { H264("H.264", "video/avc"), HEVC("H.265", "video/hevc") }
    enum class Resolution(val title: String, private val long: Double, private val short: Double) {
        Project("Project size", 0.0, 0.0), HD("720p", 1280.0, 720.0), FullHD("1080p", 1920.0, 1080.0), UltraHD("4K", 3840.0, 2160.0);
        fun size(canvas: CanvasSize): CanvasSize {
            if (this == Project) return canvas
            val scale = minOf(long / maxOf(canvas.width, canvas.height), short / minOf(canvas.width, canvas.height))
            fun even(dimension: Double) = maxOf(2.0, Math.rint(dimension * scale / 2) * 2)
            return CanvasSize(even(canvas.width), even(canvas.height))
        }
    }
    enum class FrameRate(val value: Int) { Film(24), Pal(25), Standard(30), PalHigh(50), High(60) }
}

data class ExportReceipt(val id: UUID = UUID.randomUUID(), val createdAt: Moment = Moment.now(), val filename: String, val projectID: UUID,
                         val projectTitle: String, val revision: String, val sourceChecksums: Map<String, String>, val width: Int, val height: Int,
                         val framesPerSecond: Int, val codec: ExportSettings.Codec, val resolution: ExportSettings.Resolution, val audioPeak: Double? = null) {
    // Measured from this export's final mix.
    val audioWarning: String? get() {
        val peak = audioPeak ?: return null
        // A small decode/resample overshoot isn't a useful export warning.
        if (!peak.isFinite() || peak < Math.pow(10.0, 0.5 / 20)) return null
        return String.format("The edited audio peaks %.1f dB above full scale. Lower the volume of the affected clips and export again.", 20 * log10(peak))
    }
}

sealed class ProjectError(message: String) : Exception(message) {
    class InvalidDocument : ProjectError("This project has unsupported or invalid editing data. Its files have been preserved.")
    class UnsupportedTools(tool: String) : ProjectError("This project uses $tool, which YouRec for Android cannot edit yet. Its files have been preserved.")
    class NewerProject : ProjectError("This project was saved by a newer version of YouRec. Its files have been preserved.")
    class MissingMedia : ProjectError("A source file is missing. The project has been preserved.")
    class EmptyTimeline : ProjectError("Add a recording before previewing or exporting.")
    class InvalidMedia : ProjectError("The recording could not be opened as a valid video. Its file has been preserved.")
}

data class ProjectDeletion(val id: UUID, val token: UUID, val date: Moment, val dateIsKnown: Boolean) {
    fun isOlderThan30Days(now: Moment) = date.reference.isFinite() && date.reference < now.reference - 30 * 86400.0
}

class ProjectDisk(val root: File) {
    fun folder(id: UUID) = File(root, id.text)
    /// Complete movies in the project folder which no document mentions: a take
    /// finished just before the app died. Offered again on the next launch.
    fun unindexedRecordings(project: Project): List<File> {
        val known = project.mediaFiles.map { it.lowercase() }.toSet()
        return (folder(project.id).listFiles() ?: emptyArray()).filter { file ->
            val name = file.name.lowercase()
            file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.extension.lowercase() in setOf("mov", "mp4")
                && !name.startsWith(".") && !name.startsWith("recovered-") && name !in known
        }.sortedBy { it.name }
    }
    // Fixture stores must never share the user's Recently Deleted directory.
    val deletedRoot: File get() = File(root.parentFile, if (root.name == "Projects") "RecentlyDeleted" else root.name + "-RecentlyDeleted")
    private fun deletionFile(directory: File) = File(directory, ".yourec-deletion.json")
    fun moveToDeleted(project: Project, now: Moment = Moment.now()) {
        deletedRoot.mkdirs()
        // A failed receipt write leaves the active project in place. The manifest,
        // originals and immutable edit history are not rewritten for deletion.
        RecoveryFile.write(JSON.write(ProjectCoding.encode(ProjectDeletion(project.id, UUID.randomUUID(), now, true))), deletionFile(folder(project.id)))
        RecoveryFile.move(folder(project.id), File(deletedRoot, project.id.text))
    }
    fun restoreDeleted(project: Project) {
        RecoveryFile.move(File(deletedRoot, project.id.text), folder(project.id))
        deletionFile(folder(project.id)).delete()
    }
    fun deletionRecord(id: UUID, firstSeenAt: Moment = Moment.now()): ProjectDeletion {
        val directory = File(deletedRoot, id.text)
        if (!File(directory, "project.json").exists()) throw ProjectError.InvalidDocument()
        val file = deletionFile(directory)
        if (file.exists()) {
            val record = ProjectCoding.deletion(Fields.of(JSON.parse(file.readBytes())))
            if (record.id != id || !record.date.reference.isFinite()) throw ProjectError.InvalidDocument()
            return record
        }
        // Start a conservative 30-day clock now; never substitute last edit or creation time.
        return ProjectDeletion(id, UUID.randomUUID(), firstSeenAt, false).also { RecoveryFile.write(JSON.write(ProjectCoding.encode(it)), file) }
    }
    fun eraseExpiredDeletion(confirmed: ProjectDeletion, now: Moment = Moment.now()): Boolean {
        if (!File(deletedRoot, confirmed.id.text).exists()) return false
        // Revalidate the exact deletion event: restoring and deleting the same
        // project again invalidates an already-visible confirmation.
        val current = deletionRecord(confirmed.id, now)
        if (current != confirmed || !current.isOlderThan30Days(now)) return false
        eraseDeleted(confirmed.id)
        return true
    }
    fun eraseDeleted(id: UUID) {
        val pending = File(deletedRoot, ".purging-" + UUID.randomUUID().text)
        // This rename is the permanent-deletion commit. Only confirmed removals
        // enter this queue; a crash after any child unlink can safely resume.
        RecoveryFile.move(File(deletedRoot, id.text), pending)
        pending.deleteRecursively()
    }
    fun finishPendingDeletions() {
        for (folder in deletedRoot.listFiles() ?: emptyArray()) {
            if (!folder.name.startsWith(".purging-") || !folder.isDirectory || java.nio.file.Files.isSymbolicLink(folder.toPath())) continue
            folder.deleteRecursively()
        }
    }
    fun save(project: Project) {
        if (project.deferredHistory != null) throw ProjectError.InvalidDocument()
        project.validate()
        synchronized(writeLock) {
            val directory = folder(project.id); val history = File(directory, "History")
            history.mkdirs()
            val head = File(directory, "project.json")
            val previous = if (head.exists()) head.readBytes() else null
            previous?.let { RecoveryFile.keepManifest(it, directory) }
            val data = project.manifest { name, bytes -> RecoveryFile.ensureSnapshot(bytes, File(history, name)) }
            if (previous == null || !data.contentEquals(previous)) RecoveryFile.write(data, head)
            // Secondary copy. A failure here cannot revoke the successful primary commit.
            try { RecoveryFile.keepManifest(data, directory) } catch (e: Exception) { }
        }
    }
    /// A summary reads only the current edit: enough for the library card.
    fun read(file: File, summary: Boolean = false): Project {
        val history = File(file.parentFile, "History")
        return Project.readManifest(file.readBytes(), if (summary) file else null) { name -> File(history, name).readBytes() }
    }
    private companion object { val writeLock = Any() }
}

/// Durable small-file writes. A reader sees the old document or the new one.
object RecoveryFile {
    fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { String.format("%02x", it) }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1 shl 16)
        file.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }
    fun write(data: ByteArray, destination: File) {
        val directory = destination.parentFile
        directory.mkdirs()
        val temp = File(directory, ".saving-" + UUID.randomUUID().text)
        try {
            java.io.FileOutputStream(temp).use { out -> out.write(data); out.fd.sync() }
            move(temp, destination, replace = true)
        } finally { temp.delete() }
    }
    fun move(from: File, to: File, replace: Boolean = false) {
        if (!replace && to.exists()) throw java.io.IOException("${to.name} already exists")
        java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(to.parentFile)
    }
    fun syncDirectory(directory: File) {
        // Not every filesystem can flush a directory entry; the rename is still atomic.
        try { java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } } catch (e: java.io.IOException) { }
    }
    /// A snapshot is immutable. If its file no longer matches its name, keep
    /// the damaged bytes as evidence and write the content again.
    fun ensureSnapshot(data: ByteArray, file: File) {
        if (file.exists()) {
            if (file.length() == data.size.toLong() && file.readBytes().contentEquals(data)) return
            val existing = file.readBytes()
            write(existing, File(file.parentFile.parentFile, ".revisions/damaged-history/" + hash(existing) + ".json"))
        }
        write(data, file)
    }
    fun revisions(backups: File): List<File> =
        (backups.listFiles() ?: emptyArray()).filter { it.name.startsWith("revision-") && it.name.endsWith(".json") }.sortedByDescending { it.name }
    /// Called under the project write lock. YouRec keeps five recent project
    /// indexes; only our own rotating backups are ever removed.
    fun keepManifest(data: ByteArray, directory: File) {
        val backups = File(directory, ".revisions"); val digest = hash(data)
        val header = try { JSON.parse(data) as? Map<*, *> } catch (e: JSON.Malformed) { null }
        val version = header?.get("schemaVersion") as? Long
        if (version == null) {
            val damaged = File(backups, "damaged-$digest.json")
            if (!damaged.exists()) write(data, damaged)
            return
        }
        if (version > Project.latestReadableSchema) throw ProjectError.NewerProject()
        if ((header["id"] as? String)?.uppercase() != directory.name.uppercase()) throw ProjectError.InvalidDocument()
        val files = revisions(backups)
        if (files.firstOrNull()?.name?.endsWith("-$digest.json") == true) return
        val last = files.firstOrNull()?.name?.split("-")?.getOrNull(1)?.toLongOrNull() ?: 0
        write(data, File(backups, String.format("revision-%019d-", last + 1) + digest + ".json"))
        for (old in revisions(backups).drop(5)) old.delete()
    }
}

/// Expensive media jobs can report thousands of blocks per second. Keep UI
/// updates bounded while always delivering the start and completion values.
class MediaProgress(private val callback: (Double) -> Unit) {
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastValue = -1.0
    fun report(value: Double) {
        val bounded = value.coerceIn(0.0, 1.0); val now = System.nanoTime() / 1e9
        val publish = synchronized(this) {
            val due = bounded != lastValue && (now - lastTime >= 0.1 || bounded == 0.0 || bounded == 1.0)
            if (due) { lastTime = now; lastValue = bounded }
            due
        }
        if (publish) callback(bounded)
    }
}
