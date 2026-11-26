package yourec

import java.io.File

/// Fonts imported into a project, named and stored as the iPhone app names and stores them.
fun importedFontTests(root: File) {
    root.deleteRecursively(); root.mkdirs()
    val fixtures = File("../Tests/Fixtures/Fonts")
    val digest = "a".repeat(64)

    // The id names content: digest, face index and PostScript name, which may itself hold colons.
    val ref = ImportedFontFace.Reference.of("import:$digest:2:Odd:Name")
    require(ref != null && ref.digest == digest && ref.index == 2 && ref.postScriptName == "Odd:Name" && ref.filename == "font-$digest.sfnt",
        "An imported face's id names its file, its face and its PostScript name")
    for (bad in listOf("import:${"A".repeat(64)}:0:X", "import:${"a".repeat(63)}:0:X", "import:$digest:-1:X", "import:$digest:0:", "import:$digest:0", "system", "import:ABCDEF.ttf#MyFont"))
        require(ImportedFontFace.Reference.of(bad) == null, "$bad is not an imported face")

    // Faces are read as the iPhone's CoreText reads them: the bundled fonts give back the PostScript names the iPhone app uses.
    val bundled = File("../ThirdParty/TitleFonts").walk().filter { it.extension == "ttf" }.flatMap { ImportedFonts.faces(it.readBytes()) }.toList()
    val names = bundled.map { ImportedFontFace.Reference.of(it.id)!!.postScriptName }.toSet()
    for (family in TitleTypeface.families.filter { it.id != "system" })
        require(family.upright in names && (family.slanted == null || family.slanted in names), "${family.name}'s PostScript names are read from its files")
    require(bundled.first { it.id.endsWith(":Caveat-Regular") }.let { it.weights == listOf(400, 500, 600, 700) && it.defaultWeight == 400 },
        "A variable font offers the weights its axis covers")
    require(bundled.first { it.id.endsWith(":ArchivoBlack-Regular") }.let { it.weights.isEmpty() && it.defaultWeight == 400 && it.name == "Archivo Black Regular" },
        "A static font has no weight choice and shows its full name")
    val collection = File(fixtures, "YouRec-QA-Ink-Family.otc").readBytes()
    val inkDigest = ImportedFonts.digest(collection)
    require(ImportedFonts.faces(collection).map { it.id to it.name } == listOf("import:$inkDigest:0:YouRecQAInk-Regular" to "YouRec QA Ink Regular",
        "import:$inkDigest:1:YouRecQAInk-Bold" to "YouRec QA Ink Bold"), "Every face of a collection is named by its index")
    requireFailure<FontError>("A file that is not a font is refused") { ImportedFonts.faces("not a font at all".toByteArray()) }

    // Import into the project folder, under the iPhone's name for it.
    val disk = ProjectDisk(File(root, "Projects"))
    val project = Project("Fonts")
    project.addRecording(SourceMedia(filename = "source.mp4", duration = 4.0))
    disk.save(project)
    val folder = disk.folder(project.id)
    File(folder, "source.mp4").writeBytes(ByteArray(1000) { it.toByte() })
    val faces = ImportedFonts.ingest(collection, "Ink.otc", folder)
    require(File(folder, "font-$inkDigest.sfnt").readBytes().contentEquals(collection) && ImportedFonts.ingest(collection, "Again.OTC", folder) == faces
        && ImportedFonts.faces(folder) == faces.sortedBy { it.name }, "A font is kept once in the project folder as font-<sha256>.sfnt")
    requireFailure<FontError>("Only font files are imported") { ImportedFonts.ingest(collection, "Ink.zip", folder) }
    val bold = faces[1]
    require(ImportedFonts.resolve(bold.id, folder)?.second == bold && ImportedFonts.resolve(bold.id.replace(":1:", ":0:"), folder) == null
        && ImportedFonts.resolve("import:$digest:0:Missing", folder) == null && ImportedFonts.label("import:$digest:0:Missing", folder) == "Missing",
        "A face is found only when the project holds exactly it; a missing one shows its PostScript name")

    // An imported face opens, draws as the system font while missing, and is written back verbatim.
    val edit = project.edit
    val (added, id) = TimelineEditing.addingText(TextOverlay(text = "Fonts", appearance = TitleDesign(face = bold.id, weight = 400)), 0.5, 2.0, edit)
    project.commit(added)
    val missingFace = "import:$digest:0:Missing"
    project.commit(TimelineEditing.updatingText(added.clips.flatMap { it.texts ?: emptyList() }.first { it.id == id }
        .copy(labelHeadline = LabelHeadline(labelStyle = TitleDesign(face = missingFace))), project.edit))
    require(project.fontFiles == listOf("font-$digest.sfnt", "font-$inkDigest.sfnt").sorted() && project.mediaFiles.containsAll(project.fontFiles),
        "Every font the project names, missing or not, belongs to it")
    project.undo()
    require(project.fontFiles.contains("font-$digest.sfnt"), "A font an undone edit uses stays with the project")
    disk.save(project)
    val reopened = disk.read(File(folder, "project.json"))
    require(reopened.edit.clips.flatMap { it.texts ?: emptyList() }.single().appearance?.face == bold.id && reopened.fontFiles == project.fontFiles,
        "An imported face survives a save and a reopen")

    // A copy and a backup carry the font with the project.
    val copy = ProjectArchive.duplicate(reopened, disk, "Fonts copy")
    require(File(disk.folder(copy.id), "font-$inkDigest.sfnt").readBytes().contentEquals(collection), "A duplicated project keeps its fonts")
    val archive = File(root, "fonts.zip")
    ProjectArchive.export(reopened, folder, archive, disk)
    val restoreDisk = ProjectDisk(File(root, "Restored"))
    val restored = ProjectArchive.restore(archive, restoreDisk)
    require(ImportedFonts.resolve(bold.id, restoreDisk.folder(restored.id))?.second == bold, "A backup restores the project's fonts")
}
