package yourec

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/// Ported from the iPhone app's archive and duplication checks.
fun archiveTests(root: File) {
    root.deleteRecursively()
    val disk = ProjectDisk(File(root, "Projects"))
    val movie = SourceMedia(filename = "source.mp4", duration = 4.0)
    val voice = SourceMedia(filename = "voice.m4a", duration = 2.0, audioOnly = true)
    val original = Project("Interview")
    original.addRecording(movie); original.sources = original.sources + voice
    val edit = original.edit; edit.timeline.insertAudio(voice, EditTime.seconds(0.5)); original.commit(edit)
    original.commit(TimelineEditing.split(original.edit, 1.0)); original.undo()
    disk.save(original)
    val source = disk.folder(original.id)
    val movieBytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
    val media = listOf("source.mp4", "voice.m4a", "unfinished.mp4", "unfinished.events.json", "Recovery/" + UUID.randomUUID().text.uppercase() + "/salvaged.mov")
    for (name in media + listOf("Cache/preview.mov", "Exports/master.mp4")) {
        val file = File(source, name); file.parentFile.mkdirs()
        file.writeBytes(if (name == movie.filename) movieBytes else name.toByteArray())
    }

    val copy = ProjectArchive.duplicate(original, disk, "Interview copy")
    val folder = disk.folder(copy.id)
    val reopened = disk.read(File(folder, "project.json"))
    require(copy.id != original.id && reopened.title == "Interview copy" && reopened.history == original.history
        && reopened.historyCursor == original.historyCursor && reopened.canUndo && reopened.canRedo, "Independent project with the full saved history and cursor")
    require(reopened.exportSettings == original.exportSettings, "Export settings are kept")
    reopened.redo(); disk.save(reopened)
    val unchanged = disk.read(File(source, "project.json"))
    require(unchanged.edit == original.edit && unchanged.canRedo, "Redo in the copy cannot change the original")
    val lostTake = "Recovery/" + RecoveryFile.hash("unfinished.mp4".toByteArray()) + "/"
    for (name in media) {
        val path = if (name.startsWith("unfinished.")) lostTake + name else name
        require(File(folder, path).readBytes().contentEquals(if (name == movie.filename) movieBytes else name.toByteArray()), "Copied original or recovery asset: $name")
    }
    require(!File(folder, "Cache").exists() && !File(folder, "Exports").exists(), "Generated artifacts stay with their original project")
    RandomAccessFile(File(source, movie.filename), "rw").use { it.write(byteArrayOf((movieBytes[0].toInt() xor 0xff).toByte())) }
    require(File(folder, movie.filename).readBytes().contentEquals(movieBytes), "Changing the original's media leaves the copy's alone")
    File(folder, voice.filename).delete()
    val repairable = ProjectArchive.duplicate(reopened, disk, "Repairable")
    require(repairable.sources.any { it.id == voice.id } && !File(disk.folder(repairable.id), voice.filename).exists(), "An existing offline reference stays relinkable")
    val empty = Project("Empty"); disk.save(empty)
    val emptyCopy = ProjectArchive.duplicate(empty, disk, "Empty copy")
    require(emptyCopy.edit.duration == 0.0 && emptyCopy.id != empty.id, "Empty projects duplicate")
    val before = disk.root.list()!!.toSet()
    java.nio.file.Files.createSymbolicLink(File(folder, voice.filename).toPath(), File(folder, movie.filename).toPath())
    requireFailure<ProjectError.InvalidDocument>("A link in place of a recording is refused") { ProjectArchive.duplicate(reopened, disk, "Incomplete") }
    require(disk.root.list()!!.toSet() == before, "A failed copy leaves no published project")
    File(folder, voice.filename).delete(); File(folder, voice.filename).writeBytes("voice.m4a".toByteArray())

    // Backup and restore.
    val archive = File(root, "portable-project.zip")
    ProjectArchive.export(reopened, folder, archive, disk)
    ZipFile(archive).use { zip ->
        val names = zip.entries().toList().map { it.name }
        require(names.contains("project.json") && names.contains("integrity.json") && names.count { it.startsWith("History/") } == reopened.history.distinct().size
            && zip.entries().toList().all { it.method == ZipEntry.STORED }, "Flat, uncompressed entries with the manifest, its snapshots and integrity.json")
    }
    val restoreDisk = ProjectDisk(File(root, "Restored"))
    val restored = ProjectArchive.restore(archive, restoreDisk)
    require(restored.id != reopened.id && restored.history == reopened.history && restored.historyCursor == reopened.historyCursor && restored.canUndo,
        "A backup restores the full undo and redo history into a distinct project")
    require(File(restoreDisk.folder(restored.id), movie.filename).readBytes().contentEquals(movieBytes) && restored.sources.all { it.sha256 != null },
        "A backup restores exact original bytes and records their checksums")
    require(File(restoreDisk.folder(restored.id), "Recovery").walk().any { it.name == "salvaged.mov" }, "Recovery entries travel in the backup")
    val reread = restoreDisk.read(File(restoreDisk.folder(restored.id), "project.json"))
    require(reread.history == reopened.history, "The restored project is saved")

    val malicious = File(root, "malicious.zip")
    ZipOutputStream(malicious.outputStream()).use { it.putNextEntry(ZipEntry("../escape.mov")); it.write(1); it.closeEntry() }
    requireFailure<ProjectError.InvalidDocument>("A path outside the project is refused") { ProjectArchive.restore(malicious, restoreDisk) }
    require(!File(root, "escape.mov").exists() && !File(File(root, "Restored"), "escape.mov").exists(), "Nothing is extracted from a refused archive")

    // A damaged recording is left missing; the history survives.
    val damaged = File(root, "damaged.zip"); archive.copyTo(damaged)
    val offset = ZipFile(damaged).use { zip -> zip.entries().toList().indexOfFirst { it.name == movie.filename } }
    require(offset >= 0, "The archive holds the recording")
    val bytes = damaged.readBytes()
    val at = indexOf(bytes, movieBytes.copyOfRange(1000, 1032))
    require(at > 0, "The recording is stored uncompressed")
    bytes[at] = (bytes[at].toInt() xor 0x55).toByte(); damaged.writeBytes(bytes)
    val partial = ProjectArchive.restore(damaged, restoreDisk)
    require(partial.history == reopened.history && !File(restoreDisk.folder(partial.id), movie.filename).exists()
        && partial.sources.first { it.id == movie.id }.sha256 != null, "A damaged recording is left missing, relinkable by its checksum")
    require(File(restoreDisk.root, ".yourec-archive-staging").walk().none { it.isFile }, "No staged files are left behind")
}

private fun indexOf(data: ByteArray, part: ByteArray): Int {
    outer@ for (i in 0..data.size - part.size) { for (j in part.indices) if (data[i + j] != part[j]) continue@outer; return i }
    return -1
}
