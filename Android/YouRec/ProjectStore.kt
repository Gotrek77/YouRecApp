package yourec

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/// The project library. Every change is saved before it becomes visible, and a
/// failed save is kept as a draft to retry, never dropped. Main thread only;
/// copying and hashing run on a worker and report back here.
class ProjectStore(private val context: Context) {
    val disk = ProjectDisk(File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects"))
    var projects: List<Project> = emptyList(); private set
    var deletedProjects: List<Project> = emptyList(); private set
    var deletionRecords: Map<UUID, ProjectDeletion> = emptyMap(); private set
    var unavailableProjects: Map<UUID, String> = emptyMap(); private set
    var unsavedDrafts: Map<UUID, Project> = emptyMap(); private set
    var selectedID: UUID? = null; private set
    var error: String? = null
        set(value) { field = value; changed() }
    /// Copy and Cut, per project, for this run of the app only.
    val clipboards = HashMap<UUID, TimelineClipboard>()
    var importing = false; private set
    var operationLabel = ""; private set
    var operationProgress = 0.0; private set
    val selected: Project? get() = projects.firstOrNull { it.id == selectedID }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = context.getSharedPreferences("yourec", Context.MODE_PRIVATE)
    private val observers = LinkedHashMap<Any, () -> Unit>()
    fun observe(owner: Any, callback: () -> Unit) { observers[owner] = callback }
    fun stopObserving(owner: Any) { observers.remove(owner) }
    private fun changed() { for (callback in observers.values.toList()) callback() }

    init {
        disk.root.mkdirs()
        disk.finishPendingDeletions()
        val found = ArrayList<Project>(); val failed = HashMap<UUID, String>()
        for (folder in disk.root.listFiles() ?: emptyArray()) {
            val id = try { UUID.fromString(folder.name) } catch (e: IllegalArgumentException) { continue }
            val head = File(folder, "project.json")
            if (!head.exists()) continue
            try { disk.read(head, summary = true).let { if (it.id == id) found.add(it) } }
            catch (e: Exception) { failed[id] = e.message ?: "This project could not be read. Its files have been preserved." }
        }
        projects = found.sortedByDescending { it.updatedAt }
        unavailableProjects = failed
        refreshDeleted()
        val saved = preferences.getString("selectedProject", null)?.let { try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null } }
        (saved?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id)?.let { select(it) }
    }

    fun folder(project: Project) = disk.folder(project.id)
    fun mediaFile(project: Project, source: SourceMedia) = File(folder(project), source.filename)
    fun cacheFolder(project: Project) = File(folder(project), "Cache").apply { mkdirs() }
    fun exportsFolder(project: Project) = File(folder(project), "Exports").apply { mkdirs() }
    fun missingSources(project: Project) = project.sources.filter { !mediaFile(project, it).exists() }
    private fun replace(project: Project) { projects = projects.map { if (it.id == project.id) project else it }.sortedByDescending { it.updatedAt } }

    fun create(): Project? {
        val project = Project(SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date()))
        return try { disk.save(project); projects = listOf(project) + projects; select(project.id); project }
        catch (e: Exception) { error = e.message; null }
    }
    private fun hydrated(project: Project): Project = project.deferredHistory?.let { disk.read(it.manifest) } ?: project
    fun openProject(id: UUID): Boolean {
        val current = projects.firstOrNull { it.id == id } ?: run { error = "This project is no longer available."; return false }
        return try { replace(hydrated(unsavedDrafts[id] ?: current)); true }
        catch (e: Exception) { error = "Could not open “${current.title}”. ${e.message}"; false }
    }
    fun select(id: UUID): Boolean {
        if (!openProject(id)) return false
        selectedID = id
        preferences.edit().putString("selectedProject", id.text).apply()
        changed()
        return true
    }
    /// Apply one change to a copy, save it, then publish it. On failure the
    /// library keeps showing the last saved state and the draft waits for Retry.
    fun update(id: UUID, change: (Project) -> Unit): Boolean {
        val current = projects.firstOrNull { it.id == id } ?: return false
        val candidate: Project
        try { candidate = hydrated(unsavedDrafts[id] ?: current).copy(); change(candidate); candidate.validate() }
        catch (e: Exception) { error = e.message; return false }
        return try { disk.save(candidate); unsavedDrafts = unsavedDrafts - id; replace(candidate); changed(); true }
        catch (e: Exception) {
            unsavedDrafts = unsavedDrafts + (id to candidate)
            error = "Changes not saved. ${e.message ?: ""} Free some storage, then choose Retry."
            false
        }
    }
    fun retrySaves() {
        for ((id, draft) in unsavedDrafts) {
            try { disk.save(draft); replace(draft); unsavedDrafts = unsavedDrafts - id }
            catch (e: Exception) { error = draft.title + ": " + e.message }
        }
        changed()
    }
    fun commit(id: UUID, edit: EditState) = update(id) { it.commit(edit) }
    fun rename(project: Project, title: String) {
        val clean = title.trim().take(120)
        if (clean.isNotEmpty() && clean != project.title) update(project.id) { it.title = clean; it.updatedAt = Moment.now() }
    }

    private fun refreshDeleted() {
        val found = ArrayList<Project>(); val records = HashMap<UUID, ProjectDeletion>()
        for (folder in disk.deletedRoot.listFiles() ?: emptyArray()) {
            try {
                val project = disk.read(File(folder, "project.json"), summary = true)
                records[project.id] = disk.deletionRecord(project.id); found.add(project)
            } catch (e: Exception) { }
        }
        deletedProjects = found.sortedByDescending { records[it.id]?.date ?: Moment(0.0) }
        deletionRecords = records
    }
    fun moveToDeleted(project: Project) {
        try {
            disk.moveToDeleted(project)
            projects = projects.filter { it.id != project.id }; unsavedDrafts = unsavedDrafts - project.id
            if (selectedID == project.id) selectedID = projects.firstOrNull()?.id
            refreshDeleted(); changed()
        } catch (e: Exception) { error = e.message }
    }
    fun restoreDeleted(project: Project) {
        try {
            disk.restoreDeleted(project)
            projects = (projects + disk.read(File(disk.folder(project.id), "project.json"), summary = true)).sortedByDescending { it.updatedAt }
            refreshDeleted(); changed()
        } catch (e: Exception) { error = e.message }
    }
    fun eraseDeleted(project: Project) {
        try { disk.eraseDeleted(project.id); refreshDeleted(); changed() } catch (e: Exception) { error = e.message }
    }
    /// Deletions older than thirty days. Erasing them still needs the user's confirmation.
    fun expiredDeletions(now: Moment = Moment.now()) = deletionRecords.values.filter { it.isOlderThan30Days(now) }
    fun eraseExpiredDeletions(confirmed: List<ProjectDeletion>) {
        for (record in confirmed) try { disk.eraseExpiredDeletion(record) } catch (e: Exception) { error = e.message }
        refreshDeleted(); changed()
    }

    /// Index a finished movie already inside the project folder. Marks written
    /// beside it during capture (name.events.json) become its events.
    fun addRecording(file: File, id: UUID, audioOnly: Boolean = false, originalName: String? = null, completion: (Boolean) -> Unit = {}) {
        worker.execute {
            var failure: String? = null; var source: SourceMedia? = null
            try {
                val duration = MediaFiles.duration(file, needsVideo = !audioOnly)
                val marks = File(file.parentFile, file.nameWithoutExtension + ".events.json")
                val events = if (!marks.exists()) null else try {
                    @Suppress("UNCHECKED_CAST")
                    CaptureEvent.recovered((JSON.parse(marks.readBytes()) as List<Any?>).map { Fields.of(it) }
                        .map { CaptureEvent(it.uuid("id"), it.double("time"), it.string("kind"), it.string("detail")) }, duration).ifEmpty { null }
                } catch (e: Exception) { null }
                source = SourceMedia(filename = file.name, duration = duration, events = events, sha256 = RecoveryFile.hash(file),
                                     audioOnly = if (audioOnly) true else null, originalName = originalName)
            } catch (e: Exception) { failure = e.message ?: "The recording could not be read." }
            main.post {
                val take = source
                val ok = take != null && (projects.firstOrNull { it.id == id }?.let { hydrated(unsavedDrafts[id] ?: it) }?.sources?.any { it.filename == file.name } == true
                    || update(id) { if (audioOnly) it.addAudioSource(take) else it.addRecording(take) })
                if (failure != null) error = failure
                completion(ok)
            }
        }
    }
    /// Copy a picked movie into managed storage as a new project, so deleting
    /// the original elsewhere cannot break it.
    fun importVideo(uri: Uri, completion: (Project?) -> Unit) = importMedia(uri, null, false) { project, _ -> completion(project) }
    /// Copy a picked movie or sound into an existing project's originals.
    fun importMedia(uri: Uri, into: UUID?, audioOnly: Boolean, completion: (Project?, SourceMedia?) -> Unit) {
        val project = (if (into != null) projects.firstOrNull { it.id == into } else create()) ?: return completion(null, null)
        val name = displayName(uri)
        val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: if (audioOnly) "m4a" else "mp4"
        val target = File(folder(project), (if (audioOnly) "audio-" else "import-") + UUID.randomUUID().text + "." + extension)
        importing = true; operationLabel = if (audioOnly) "Adding audio" else "Importing video"; operationProgress = 0.0; changed()
        worker.execute {
            var failure: String? = null
            // The working copy is ours alone until it is complete and renamed.
            val partial = File(target.parentFile, "." + target.name + ".importing")
            try {
                val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                val progress = MediaProgress { value -> main.post { operationProgress = value; changed() } }
                (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("The file could not be opened.")).use { input ->
                    java.io.FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1 shl 16); var copied = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); copied += n; if (size > 0) progress.report(copied.toDouble() / size) }
                        output.fd.sync()
                    }
                }
                MediaFiles.duration(partial, needsVideo = !audioOnly)
                RecoveryFile.move(partial, target)
            } catch (e: Exception) { partial.delete(); failure = e.message ?: "The file could not be imported." }
            main.post {
                if (failure != null) {
                    importing = false; error = failure
                    if (into == null) { projects = projects.filter { it.id != project.id }; disk.folder(project.id).deleteRecursively() }
                    changed(); completion(null, null)
                } else addRecording(target, project.id, audioOnly, name) { ok ->
                    importing = false; changed()
                    val current = projects.firstOrNull { it.id == project.id }
                    completion(if (ok) current else null, current?.sources?.firstOrNull { it.filename == target.name })
                }
            }
        }
    }
    /// Run a long project operation off the main thread, with progress on the list.
    private fun operation(label: String, work: ((Double) -> Unit) -> Project?, completed: (Project?) -> Unit = {}) {
        if (importing) return
        importing = true; operationLabel = label; operationProgress = 0.0; changed()
        worker.execute {
            var result: Project? = null; var failure: String? = null
            try { result = work { value -> main.post { operationProgress = value; changed() } } }
            catch (e: Exception) { failure = e.message ?: "The operation failed." }
            main.post {
                importing = false
                result?.let { project -> projects = (listOf(project) + projects).sortedByDescending { it.updatedAt }; select(project.id) }
                if (failure != null) error = failure
                changed(); completed(result)
            }
        }
    }
    private fun copyTitle(title: String): String {
        val base = title.replace(Regex(" copy(?: [0-9]+)?$", RegexOption.IGNORE_CASE), "")
        val existing = projects.map { it.title.lowercase() }.toSet()
        var number = 1
        while (true) {
            val suffix = if (number == 1) " copy" else " copy $number"
            val candidate = base.take(120 - suffix.length) + suffix
            if (candidate.lowercase() !in existing) return candidate
            number += 1
        }
    }
    /// A second, independent project with the same recordings and history.
    fun duplicate(project: Project, completed: (Project?) -> Unit = {}) {
        val original = try { hydrated(unsavedDrafts[project.id] ?: projects.firstOrNull { it.id == project.id } ?: return) } catch (e: Exception) { error = e.message; return }
        val title = copyTitle(original.title)
        operation("Duplicating project", { progress -> ProjectArchive.duplicate(original, disk, title, progress) }, completed)
    }
    /// Write a backup of the project, history included, into its Exports folder.
    /// The completion gets the file to share, or null.
    fun backUp(project: Project, completed: (File?) -> Unit) {
        val original = try { hydrated(unsavedDrafts[project.id] ?: projects.firstOrNull { it.id == project.id } ?: return) } catch (e: Exception) { error = e.message; return }
        if (importing) return
        val exports = exportsFolder(original)
        val name = original.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '-' }.joinToString("").trim().take(80).ifEmpty { "YouRec project" }
        val target = File(exports, "$name.zip")
        importing = true; operationLabel = "Backing up project"; operationProgress = 0.0; changed()
        worker.execute {
            var failure: String? = null
            try {
                // Only an earlier backup is replaced; movies in Exports are left alone.
                for (old in exports.listFiles() ?: emptyArray()) if (old.name.endsWith(".zip") || old.name.endsWith(".partial")) old.delete()
                ProjectArchive.export(original, folder(original), target, disk) { value -> main.post { operationProgress = value; changed() } }
            } catch (e: Exception) { target.delete(); failure = "Couldn’t back up the project. " + (e.message ?: "") }
            main.post {
                importing = false
                if (failure != null) error = failure
                changed(); completed(if (failure == null) target else null)
            }
        }
    }
    /// Open a project backup, from this phone or from the iPhone app, as a new project.
    fun restoreBackup(uri: Uri, completed: (Project?) -> Unit) {
        val staging = File(context.cacheDir, "Backups").apply { mkdirs() }
        operation("Opening backup", { progress ->
            val copy = File(staging, UUID.randomUUID().text + ".zip")
            try {
                (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("The file could not be opened.")).use { input ->
                    java.io.FileOutputStream(copy).use { input.copyTo(it, 1 shl 20) }
                }
                try { ProjectArchive.restore(copy, disk) { progress(it) } }
                catch (e: java.util.zip.ZipException) { throw ProjectError.InvalidDocument() }
            } finally { copy.delete() }
        }, completed)
    }
    /// Read a picked .cube file, check it and keep it in the project folder.
    /// The completion gets the LUT, or the reason it was refused.
    fun importLUT(uri: Uri, into: UUID, completion: (ImportedLUT?, String?) -> Unit) {
        val project = projects.firstOrNull { it.id == into } ?: return completion(null, null)
        val name = displayName(uri) ?: "Imported LUT"
        worker.execute {
            var lut: ImportedLUT? = null; var failure: String? = null
            try {
                val data = (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("The file could not be opened.")).use { input ->
                    val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n); if (bytes.size() > ColorCube.maximumBytes) throw LUTError("Choose a UTF-8 .cube file smaller than 32 MB.") }
                    bytes.toByteArray()
                }
                lut = ColorCube.ingest(data, name, folder(project))
            } catch (e: Exception) { failure = e.message ?: "The LUT could not be imported." }
            main.post { completion(lut, failure) }
        }
    }
    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    /// A take can be complete on disk and absent from its document if the app
    /// died between the two, or still open if it died while recording. At
    /// launch, rebuild the interrupted ones from their journals, then index
    /// every finished movie no document mentions. Nothing is ever deleted here.
    fun recoverUnindexedRecordings() {
        val launched = System.currentTimeMillis()
        val folders = projects.map { it to folder(it) }
        worker.execute {
            val notices = folders.flatMap { (_, folder) -> try { RecordingSalvage.recover(folder, launched) } catch (e: Exception) { emptyList() } }
            main.post {
                if (notices.isNotEmpty()) error = notices.distinct().joinToString(" ")
                for ((summary, _) in folders) {
                    val files = try { disk.unindexedRecordings(summary) } catch (e: Exception) { continue }
                    for (file in files) addRecording(file, summary.id) { ok ->
                        if (ok) return@addRecording
                        // Asked once: a file that is not a movie moves aside instead of failing at every launch.
                        val kept = File(folder(summary), "Recovery").apply { mkdirs() }
                        file.renameTo(File(kept, file.name))
                        error = "“${file.name}” in “${summary.title}” could not be opened as a video. It was kept in the project's Recovery folder."
                    }
                }
            }
        }
    }
}

/// Facts about a movie file, read without decoding it.
object MediaFiles {
    class Video(val width: Int, val height: Int, val rotation: Int, val frameRate: Int) {
        /// Dimensions as displayed, after the recorded rotation.
        val displayWidth: Int get() = if (rotation % 180 == 0) width else height
        val displayHeight: Int get() = if (rotation % 180 == 0) height else width
    }
    fun track(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true } ?: -1
    /// The longest track, in seconds. Throws for a file that is not usable media.
    fun duration(file: File, needsVideo: Boolean): Double {
        val extractor = MediaExtractor()
        try {
            try { extractor.setDataSource(file.path) } catch (e: Exception) { throw ProjectError.InvalidMedia() }
            if (track(extractor, if (needsVideo) "video/" else "audio/") < 0) throw ProjectError.InvalidMedia()
            var longest = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) longest = maxOf(longest, format.getLong(MediaFormat.KEY_DURATION))
            }
            if (longest <= 0) throw ProjectError.InvalidMedia()
            return longest / 1e6
        } finally { extractor.release() }
    }
    fun video(file: File): Video? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = track(extractor, "video/")
            if (index < 0) return null
            val format = extractor.getTrackFormat(index)
            fun int(key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
            return Video(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), int(MediaFormat.KEY_ROTATION, 0), int(MediaFormat.KEY_FRAME_RATE, 30))
        } catch (e: Exception) { return null } finally { extractor.release() }
    }
}
