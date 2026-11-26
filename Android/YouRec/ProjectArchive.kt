package yourec

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/// Duplicate a project, and write or read the project backup archive. The
/// archive is the iPhone app's: flat, uncompressed entries, the manifest and
/// its History snapshots, and integrity.json with each file's SHA-256, so a
/// backup made on either phone opens on the other.
object ProjectArchive {
    private object Limit {
        const val header = 20_000_000
        const val snapshot = 32_000_000
        // Charge every referenced snapshot, including repeated references.
        const val history = 256_000_000
        fun check(bytes: Int, maximum: Int) { if (bytes > maximum) throw ProjectError.InvalidDocument() }
    }
    private val restoreSession = UUID.randomUUID().text
    private fun workspace(disk: ProjectDisk) = File(disk.root, ".yourec-archive-staging")
    /// Work left by an archive operation the app did not finish.
    fun discardInterrupted(disk: ProjectDisk) {
        for (session in workspace(disk).listFiles() ?: return)
            if (session.name != restoreSession && runCatching { UUID.fromString(session.name) }.isSuccess) session.deleteRecursively()
    }
    fun stagingDisk(disk: ProjectDisk): ProjectDisk { discardInterrupted(disk); return ProjectDisk(File(workspace(disk), restoreSession)) }

    // Recovery entries are opaque originals. Keep them outside the restored
    // timeline, including the sidecars that came with an interrupted take.
    fun isRecoveryEntry(path: String): Boolean {
        val parts = path.split("/")
        if (parts.size != 3 || parts[0] != "Recovery") return false
        if (runCatching { UUID.fromString(parts[1]) }.isFailure && !(parts[1].length == 64 && parts[1].all { it in "0123456789abcdefABCDEF" })) return false
        val name = parts[2]
        if (name.isEmpty() || name.toByteArray().size > 255 || name.startsWith(".") || name.contains("\\") || name.contains("\u0000")) return false
        return name.substringAfterLast('.', "").lowercase() in setOf("mov", "mp4", "m4v", "m4a", "caf", "wav", "aac", "flac", "mp3", "aif", "aiff")
            || name.endsWith(".events.json") || name.endsWith(".effects.json")
    }
    private fun plainFile(file: File) = file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())
    private fun recoveryEntries(folder: File): List<String> {
        val root = File(folder, "Recovery")
        if (!root.exists()) return emptyList()
        if (!root.isDirectory || java.nio.file.Files.isSymbolicLink(root.toPath())) throw ProjectError.InvalidDocument()
        val paths = ArrayList<String>()
        for (group in root.listFiles() ?: emptyArray()) {
            if (!group.isDirectory || java.nio.file.Files.isSymbolicLink(group.toPath())) continue
            for (file in group.listFiles() ?: emptyArray()) {
                val path = "Recovery/" + group.name + "/" + file.name
                if (plainFile(file) && isRecoveryEntry(path)) paths.add(path)
            }
        }
        return paths.sorted()
    }
    /// Originals the timeline does not name: recovery entries, and a take a
    /// backup was asked for before startup finished indexing it.
    private fun preservedOriginals(project: Project, folder: File, disk: ProjectDisk): Map<String, File> {
        val preserved = recoveryEntries(folder).associateWith { File(folder, it) }.toMutableMap()
        if (folder.name == project.id.text) for (movie in disk.unindexedRecordings(project)) {
            val prefix = "Recovery/" + RecoveryFile.hash(movie.name.toByteArray()) + "/"
            preserved[prefix + movie.name] = movie
            for (suffix in listOf("events.json", "effects.json")) {
                val sidecar = File(movie.parentFile, movie.nameWithoutExtension + "." + suffix)
                if (sidecar.exists()) preserved[prefix + sidecar.name] = sidecar
            }
        }
        return preserved
    }
    private fun requireSpace(bytes: Long, folder: File) {
        folder.mkdirs()
        if (folder.usableSpace < bytes + 64L * 1024 * 1024) throw ProjectError.InsufficientStorage()
    }

    private fun copy(source: File, target: File, progress: (Double) -> Unit) {
        val total = maxOf(1L, source.length())
        requireSpace(total, target.parentFile!!)
        if (target.exists()) throw ProjectError.InvalidDocument()
        FileInputStream(source).use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(2 shl 20); var count = 0L; var nextCheck = 16L shl 20
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                output.write(buffer, 0, n); count += n
                if (count >= nextCheck) { requireSpace(0, target.parentFile!!); nextCheck = count + (16L shl 20) }
                progress(minOf(1.0, count.toDouble() / total))
            }
            output.fd.sync()
        } }
    }
    private fun publish(project: Project, staged: ProjectDisk, disk: ProjectDisk): Project {
        staged.save(project)
        val destination = disk.folder(project.id)
        disk.root.mkdirs()
        if (!staged.folder(project.id).renameTo(destination)) throw ProjectError.InvalidDocument()
        try { RecoveryFile.syncDirectory(disk.root) } catch (e: Exception) { destination.deleteRecursively(); throw e }
        return project
    }

    /// A second project with its own copy of every file: either can then be
    /// edited, repaired or deleted alone. The copy's history is the original's.
    fun duplicate(original: Project, disk: ProjectDisk, title: String, progress: (Double) -> Unit = {}): Project {
        if (original.deferredHistory != null) throw ProjectError.InvalidDocument()
        original.validate()
        val copy = original.copy()
        copy.id = UUID.randomUUID(); copy.title = title
        copy.createdAt = Moment.now(); copy.updatedAt = copy.createdAt
        val staged = stagingDisk(disk); val work = staged.folder(copy.id)
        val folder = disk.folder(original.id)
        work.mkdirs()
        try {
            val preserved = preservedOriginals(original, folder, disk)
            val names = original.mediaFiles + preserved.keys.sorted()
            for ((index, name) in names.withIndex()) {
                val source = preserved[name] ?: File(folder, name)
                // Keep an already missing asset relinkable in both projects.
                if (!source.exists() && preserved[name] == null) continue
                if (!plainFile(source)) throw ProjectError.InvalidDocument()
                val target = File(work, name); target.parentFile!!.mkdirs()
                copy(source, target) { progress((index + it) / names.size) }
                progress((index + 1.0) / names.size)
            }
            return publish(copy, staged, disk)
        } finally { work.deleteRecursively() }
    }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { String.format("%02x", it) }

    /// Write a backup of the whole project, history included, to `destination`.
    fun export(project: Project, folder: File, destination: File, disk: ProjectDisk, progress: (Double) -> Unit = {}) {
        project.validate()
        if (destination.exists()) throw ProjectError.InvalidDocument()
        destination.parentFile!!.mkdirs()
        val partial = File(destination.parentFile, UUID.randomUUID().text + ".partial")
        try {
            // Keep a backup usable if a LUT has gone missing, even when only an undo
            // state refers to it. Original recordings remain required.
            val preserved = preservedOriginals(project, folder, disk)
            val files = project.sources.map { it.filename } + (project.imageFiles + project.effectFiles + project.fontFiles).distinct().sorted().filter { File(folder, it).exists() } + preserved.keys.sorted()
            var required = 256_000_000L
            for (name in files) {
                val file = preserved[name] ?: File(folder, name)
                if (!plainFile(file)) throw ProjectError.MissingMedia()
                required += file.length()
            }
            requireSpace(required, destination.parentFile!!)
            ZipOutputStream(FileOutputStream(partial).buffered(1 shl 20)).use { zip ->
                zip.setMethod(ZipOutputStream.STORED)
                fun add(data: ByteArray, name: String) {
                    val entry = ZipEntry(name); entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong(); entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().also { it.update(data) }.value
                    zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
                }
                val written = HashSet<String>(); var historyBytes = 0
                val manifest = project.manifest { name, data ->
                    Limit.check(data.size, Limit.snapshot)
                    historyBytes += data.size; Limit.check(historyBytes, Limit.history)
                    if (written.add(name)) add(data, "History/$name")
                }
                Limit.check(manifest.size, Limit.header)
                add(manifest, "project.json")
                val hashes = sortedMapOf<String, String>()
                for ((index, name) in files.withIndex()) {
                    val file = preserved[name] ?: File(folder, name)
                    // A stored entry declares its checksum first: read the file once to
                    // measure it, then again to write it, and refuse if it changed between.
                    val size = file.length(); val crc = CRC32(); val sha = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(1 shl 20)
                    FileInputStream(file).use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n); sha.update(buffer, 0, n) } }
                    val entry = ZipEntry(name); entry.method = ZipEntry.STORED
                    entry.size = size; entry.compressedSize = size; entry.crc = crc.value
                    zip.putNextEntry(entry)
                    var copied = 0L; var nextCheck = 16L shl 20
                    FileInputStream(file).use { input ->
                        while (copied < size) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
                            if (n < 0) throw ProjectError.MissingMedia()
                            zip.write(buffer, 0, n); copied += n
                            if (copied >= nextCheck) { requireSpace(0, destination.parentFile!!); nextCheck = copied + (16L shl 20) }
                            progress((index + copied.toDouble() / maxOf(1L, size)) / files.size)
                        }
                    }
                    zip.closeEntry()   // checks the size and CRC declared above
                    if (file.length() != size) throw ProjectError.SourceChanged()
                    hashes[name] = hex(sha)
                    progress((index + 1.0) / files.size)
                }
                val integrity = JSON.write(jsonObject("version" to (if (preserved.isEmpty()) 1 else 2), "sha256" to hashes))
                Limit.check(integrity.size, Limit.header)
                add(integrity, "integrity.json")
            }
            FileOutputStream(partial, true).use { it.fd.sync() }
            if (!partial.renameTo(destination)) throw ProjectError.InvalidDocument()
        } finally { partial.delete() }
    }

    /// Open a backup as a new project. A recording that fails its checksum is
    /// left out and stays relinkable; damage anywhere else refuses the archive.
    fun restore(archive: File, disk: ProjectDisk, progress: (Double) -> Unit = {}): Project = ZipFile(archive).use { zip ->
        val entries = zip.entries().toList()
        val names = HashSet<String>()
        for (entry in entries) {
            val path = entry.name
            val flat = !path.startsWith(".") && !path.contains("/") && !path.contains("\\") && path.isNotEmpty()
            val snapshot = path.startsWith("History/") && Project.isSnapshotName(path.removePrefix("History/"))
            if (entry.isDirectory || !(flat || snapshot || isRecoveryEntry(path)) || !names.add(path.lowercase())) throw ProjectError.InvalidDocument()
        }
        val byName = entries.associateBy { it.name }
        fun read(name: String, limit: Int = Limit.header): ByteArray {
            val entry = byName[name] ?: throw ProjectError.InvalidDocument()
            if (entry.size < 0 || entry.size > limit) throw ProjectError.InvalidDocument()
            val data = zip.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }
            if (data.size.toLong() != entry.size || CRC32().also { it.update(data) }.value != entry.crc) throw ProjectError.InvalidDocument()
            return data
        }
        val snapshotPaths = HashSet<String>()
        var remaining = Limit.history
        val project = Project.readManifest(read("project.json", Limit.history)) { name ->
            val path = "History/$name"
            val data = read(path, minOf(remaining, Limit.snapshot))
            remaining -= data.size; snapshotPaths.add(path)
            data
        }
        project.validate()
        val integrity = Fields.of(JSON.parse(read("integrity.json")))
        val version = integrity.int("version")
        val sums = integrity.fields("sha256").let { sums -> sums.keys.associateWith { sums.string(it) } }
        val preserved = sums.keys.filter(::isRecoveryEntry).toSet()
        val mediaFiles = project.mediaFiles + preserved.sorted()
        val allowed = snapshotPaths + mediaFiles + listOf("project.json", "integrity.json") + otioName(project)
        if (!((version == 1 && preserved.isEmpty()) || version == 2) || !byName.keys.containsAll(preserved) || !allowed.containsAll(byName.keys)
            || !sums.keys.containsAll(project.sources.map { it.filename }) || !mediaFiles.containsAll(sums.keys)) throw ProjectError.InvalidDocument()
        project.sources = project.sources.map { it.copy(sha256 = sums[it.filename]) }
        project.validate()
        project.id = UUID.randomUUID(); project.updatedAt = Moment.now()
        val staged = stagingDisk(disk); val folder = staged.folder(project.id)
        folder.mkdirs()
        try {
            requireSpace(256_000_000L + entries.sumOf { maxOf(0L, it.size) }, folder)
            for ((index, name) in mediaFiles.withIndex()) {
                val entry = byName[name] ?: continue
                val file = File(folder, name); file.parentFile!!.mkdirs()
                if (file.exists()) throw ProjectError.InvalidDocument()
                val crc = CRC32(); val sha = MessageDigest.getInstance("SHA-256"); var bytes = 0L
                try {
                    zip.getInputStream(entry).use { input -> FileOutputStream(file).use { output ->
                        val buffer = ByteArray(1 shl 20); var nextCheck = 16L shl 20
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            if (bytes + n > entry.size) throw ProjectError.InvalidDocument()
                            output.write(buffer, 0, n); crc.update(buffer, 0, n); sha.update(buffer, 0, n); bytes += n
                            if (bytes >= nextCheck) { requireSpace(0, folder); nextCheck = bytes + (16L shl 20) }
                            progress((index + bytes.toDouble() / maxOf(1L, entry.size)) / mediaFiles.size)
                        }
                        output.fd.sync()
                    } }
                } catch (e: java.util.zip.ZipException) { bytes = -1 }
                if (bytes != entry.size || crc.value != entry.crc || hex(sha) != sums[name]) {
                    if (name in preserved) throw ProjectError.InvalidDocument()
                    // Keep the valid takes and the editing history. The damaged take
                    // stays missing and relinkable by its expected checksum.
                    file.delete()
                }
                progress((index + 1.0) / mediaFiles.size)
            }
            publish(project, staged, disk)
        } finally { folder.deleteRecursively() }
    }
    /// The iPhone app also writes the edit as an OpenTimelineIO file; it is
    /// accepted in an archive and ignored here.
    private fun otioName(project: Project): String {
        val reserved = project.mediaFiles.map { it.lowercase() }.toSet()
        var name = "Timeline.otio"; var index = 1
        while (name.lowercase() in reserved) { index += 1; name = "Timeline-$index.otio" }
        return name
    }
}
