package yourec

import java.io.File
import java.util.UUID

/// The saved document, key for key as the iPhone app's Codable types write it.
/// Editing data this build has no tool for is refused, with its name, instead
/// of being dropped on the next save: an edit made elsewhere is never lost here.
internal object ProjectCoding {
    private fun unsupported(tool: String): Nothing = throw ProjectError.UnsupportedTools(tool)
    private fun time(value: Fields) = EditTime.ticks(value.long("ticks"))
    private fun encode(time: EditTime) = jsonObject("ticks" to time.ticks)
    private fun interval(value: Fields) = EditInterval(time(value.fields("start")), time(value.fields("end")))
    private fun <T> list(values: List<Any?>, decode: (Fields) -> T) = values.map { decode(Fields.of(it)) }
    private fun present(value: Any?) = when (value) { null -> false; is List<*> -> value.isNotEmpty(); else -> true }

    // TimelineClip
    private val clipTools = mapOf("stabilization" to "Stabilization", "speed" to "Speed", "color" to "Color", "texts" to "Text", "pauses" to "Pause cuts",
        "crop" to "Crop", "mirror" to "Mirror", "images" to "Image overlays", "openingTransition" to "Transitions", "transition" to "Transitions",
        "videoEffects" to "Video effects", "frozenFrame" to "Freeze frame")
    fun clip(value: Fields): TimelineClip {
        for ((key, tool) in clipTools) if (present(value.raw(key))) unsupported(tool)
        return TimelineClip(value.uuid("id"), value.uuid("sourceID"), value.double("start"), value.double("end"))
    }
    fun encode(clip: TimelineClip) = jsonObject("id" to clip.id.text, "sourceID" to clip.sourceID.text, "start" to clip.start, "end" to clip.end)

    private fun map(value: Fields) = SourcePlaybackMap(list(value.list("runs")) {
        PlaybackRun(time(it.fields("duration")), it.double("sourceStart"), it.double("sourceEnd"), it.bool("silent"))
    })
    private fun encode(map: SourcePlaybackMap) = jsonObject("runs" to map.runs.map {
        jsonObject("duration" to encode(it.duration), "sourceStart" to it.sourceStart, "sourceEnd" to it.sourceEnd, "silent" to it.silent)
    })
    private fun fade(value: Fields?) = value?.let { AudioEdgeFade(it.double("seconds"), it.double("elapsed")) }
    private fun encode(fade: AudioEdgeFade?) = fade?.let { jsonObject("seconds" to it.seconds, "elapsed" to it.elapsed) }

    private fun anchor(value: Fields): BookmarkAnchor {
        value.fieldsOrNull("source")?.let { return BookmarkAnchor.Source(it.uuid("occurrence"), it.uuid("source"), it.double("time")) }
        value.fieldsOrNull("local")?.let { return BookmarkAnchor.Local(it.uuid("occurrence"), time(it.fields("offset"))) }
        value.fieldsOrNull("project")?.let { return BookmarkAnchor.Project(time(it.fields("_0"))) }
        throw ProjectError.InvalidDocument()
    }
    private fun encode(anchor: BookmarkAnchor) = when (anchor) {
        is BookmarkAnchor.Source -> jsonObject("source" to jsonObject("occurrence" to anchor.occurrence.text, "source" to anchor.source.text, "time" to anchor.time))
        is BookmarkAnchor.Local -> jsonObject("local" to jsonObject("occurrence" to anchor.occurrence.text, "offset" to encode(anchor.offset)))
        is BookmarkAnchor.Project -> jsonObject("project" to jsonObject("_0" to encode(anchor.time)))
    }

    fun timeline(value: Fields): TrackTimeline {
        if (present(value.raw("placements"))) unsupported("Placement")
        if (present(value.raw("transitions")) || present(value.raw("generated"))) unsupported("Transitions")
        if (present(value.raw("subtitles"))) unsupported("Subtitles")
        val video = list(value.list("video")) {
            VideoTimelineItem(it.uuid("occurrenceID"), time(it.fields("start")), clip(it.fields("content")), it.int("layer"), time(it.fields("localOrigin")), map(it.fields("mapping")))
        }
        val audio = list(value.list("audio")) {
            if (present(it.raw("enhancements"))) unsupported("Enhance audio")
            if (present(it.raw("censorRegions"))) unsupported("Censor audio")
            if (present(it.raw("subtitleWords"))) unsupported("Subtitles")
            AudioTimelineItem(it.uuid("id"), it.uuid("occurrenceID"), it.uuid("sourceID"), time(it.fields("start")), map(it.fields("mapping")), time(it.fields("localOrigin")),
                it.uuidOrNull("followsVideo"), it.double("volumeDB"), it.bool("muted"), fade(it.fieldsOrNull("fadeIn")), fade(it.fieldsOrNull("fadeOut")))
        }
        val links = list(value.list("links")) { link -> TimelineLink(link.uuid("id"), link.list("members").map { Fields.uuid(it) }.toSet()) }
        val bookmarks = list(value.list("bookmarks")) { TimelineBookmark(it.uuid("id"), it.string("label"), anchor(it.fields("anchor"))) }
        return TrackTimeline(video, audio, links, bookmarks)
    }
    fun encode(timeline: TrackTimeline) = jsonObject(
        "video" to timeline.video.map {
            jsonObject("occurrenceID" to it.occurrenceID.text, "start" to encode(it.start), "content" to encode(it.content), "layer" to it.layer,
                "localOrigin" to encode(it.localOrigin), "mapping" to encode(it.mapping))
        },
        "audio" to timeline.audio.map {
            jsonObject("id" to it.id.text, "occurrenceID" to it.occurrenceID.text, "sourceID" to it.sourceID.text, "start" to encode(it.start), "mapping" to encode(it.mapping),
                "localOrigin" to encode(it.localOrigin), "followsVideo" to it.followsVideo?.text, "volumeDB" to it.volumeDB, "muted" to it.muted,
                "fadeIn" to encode(it.fadeIn), "fadeOut" to encode(it.fadeOut), "enhancements" to emptyList<Any?>())
        },
        // Sets are sorted so that an unchanged edit keeps its content hash.
        "links" to timeline.links.map { link -> jsonObject("id" to link.id.text, "members" to link.members.map { it.text }.sorted()) },
        "bookmarks" to timeline.bookmarks.map { jsonObject("id" to it.id.text, "label" to it.label, "anchor" to encode(it.anchor)) },
        "placements" to emptyList<Any?>(), "transitions" to emptyList<Any?>(), "generated" to emptyList<Any?>())

    fun editState(value: Fields): EditState {
        // The sequential "clips" form predates the first public release.
        if (!value.has("timeline")) unsupported("an early development format")
        val size = value.listOrNull("outputSize")?.let { if (it.size != 2) throw ProjectError.InvalidDocument(); CanvasSize(Fields.double(it[0]), Fields.double(it[1])) }
        return EditState(timeline(value.fields("timeline")), size).also { it.canvasReference = value.uuidOrNull("canvasReference") }
    }
    fun encode(state: EditState) = jsonObject("timeline" to encode(state.timeline),
        "outputSize" to state.outputSize?.let { listOf(it.width, it.height) }, "canvasReference" to state.canvasReference?.text)

    private fun event(value: Fields) = CaptureEvent(value.uuid("id"), value.double("time"), value.string("kind"), value.string("detail"))
    private fun encode(event: CaptureEvent) = jsonObject("id" to event.id.text, "time" to event.time, "kind" to event.kind, "detail" to event.detail)
    fun source(value: Fields): SourceMedia {
        if (present(value.raw("videoEffects"))) unsupported("Video effects")
        if (value.boolOrNull("isStillPhoto") == true) unsupported("Photos")
        return SourceMedia(value.uuid("id"), value.string("filename"), value.double("duration"), Moment(value.double("recordedAt")),
            value.listOrNull("events")?.let { list(it, ::event) }, value.stringOrNull("sha256"), value.boolOrNull("audioOnly"), null, value.stringOrNull("originalName"))
    }
    fun encode(source: SourceMedia) = jsonObject("id" to source.id.text, "filename" to source.filename, "duration" to source.duration,
        "recordedAt" to source.recordedAt.reference, "events" to source.events?.map { encode(it) }, "sha256" to source.sha256,
        "audioOnly" to source.audioOnly, "originalName" to source.originalName)

    private fun settings(value: Fields?): ExportSettings {
        if (value == null) return ExportSettings()
        return ExportSettings(ExportSettings.Codec.values().firstOrNull { it.title == value.string("codec") } ?: throw ProjectError.InvalidDocument(),
            ExportSettings.Resolution.values().firstOrNull { it.title == value.string("resolution") } ?: throw ProjectError.InvalidDocument(),
            ExportSettings.FrameRate.values().firstOrNull { it.value == value.int("frameRate") } ?: throw ProjectError.InvalidDocument())
    }
    private fun encode(settings: ExportSettings) = jsonObject("codec" to settings.codec.title, "resolution" to settings.resolution.title, "frameRate" to settings.frameRate.value)

    fun header(project: Project, snapshotFiles: List<String>) = jsonObject(
        "exportSettings" to encode(project.exportSettings), "schemaVersion" to project.schemaVersion, "id" to project.id.text, "title" to project.title,
        "createdAt" to project.createdAt.reference, "updatedAt" to project.updatedAt.reference, "sources" to project.sources.map { encode(it) },
        "historyCursor" to project.historyCursor, "snapshotFiles" to snapshotFiles,
        "captureLedger" to project.captureLedger.map { jsonObject("clip" to encode(it.clip), "insertion" to it.insertion) },
        "automaticCanvasVersion" to 1, "recordingHistoryVersion" to 1)

    fun project(data: ByteArray, summaryFile: File?, load: (String) -> ByteArray): Project {
        val values = try { Fields.of(JSON.parse(data)) } catch (e: JSON.Malformed) { throw ProjectError.InvalidDocument() }
        val schema = values.int("schemaVersion")
        if (schema > Project.latestReadableSchema) throw ProjectError.NewerProject()
        // Documents from before the capture ledger need the iPhone app's one-time migration.
        if (!values.has("recordingHistoryVersion") || !values.has("automaticCanvasVersion")) unsupported("an early development format")
        if (values.int("recordingHistoryVersion") != 1) throw ProjectError.InvalidDocument()
        val project = Project(values.string("title"))
        project.exportSettings = settings(values.fieldsOrNull("exportSettings"))
        project.schemaVersion = schema
        project.id = values.uuid("id")
        project.createdAt = Moment(values.double("createdAt")); project.updatedAt = Moment(values.double("updatedAt"))
        project.sources = list(values.list("sources"), ::source)
        project.historyCursor = values.int("historyCursor")
        project.captureLedger = values.listOrNull("captureLedger")?.let { entries -> list(entries) { CaptureLedgerEntry(clip(it.fields("clip")), it.bool("insertion")) } } ?: emptyList()
        val names = values.listOrNull("snapshotFiles")?.map { it as? String ?: throw ProjectError.InvalidDocument() }
        if (names != null) {
            if (project.historyCursor !in names.indices || !names.all(Project::isSnapshotName)) throw ProjectError.InvalidDocument()
            var requested = names
            if (summaryFile != null) {
                project.deferredHistory = DeferredProjectHistory(summaryFile, names.size, project.historyCursor)
                requested = listOf(names[project.historyCursor]); project.historyCursor = 0
            }
            project.snapshots = requested.map { name ->
                val payload = try { load(name) } catch (e: java.io.IOException) { throw ProjectError.InvalidDocument() }
                if (RecoveryFile.hash(payload) + ".json" != name) throw ProjectError.InvalidDocument()
                try { EditSnapshot.decode(payload) } catch (e: JSON.Malformed) { throw ProjectError.InvalidDocument() }
            }
        } else if (values.has("snapshots")) {
            project.snapshots = list(values.list("snapshots")) { EditSnapshot(editState(it.fields("state")), it.int("captureCursor")) }
        } else unsupported("an early development format")
        project.validate()
        return project
    }

    fun deletion(value: Fields) = ProjectDeletion(value.uuid("id"), value.uuid("token"), Moment(value.double("date")), value.bool("dateIsKnown"))
    fun encode(record: ProjectDeletion) = jsonObject("id" to record.id.text, "token" to record.token.text, "date" to record.date.reference, "dateIsKnown" to record.dateIsKnown)

    fun receipt(value: Fields) = ExportReceipt(value.uuid("id"), Moment(value.double("createdAt")), value.string("filename"), value.uuid("projectID"),
        value.string("projectTitle"), value.string("revision"),
        value.fields("sourceChecksums").let { sums -> sums.keys.associateWith { sums.string(it) } }, value.int("width"), value.int("height"), value.int("framesPerSecond"),
        ExportSettings.Codec.values().firstOrNull { it.title == value.string("codec") } ?: throw ProjectError.InvalidDocument(),
        ExportSettings.Resolution.values().firstOrNull { it.title == value.string("resolution") } ?: throw ProjectError.InvalidDocument(), value.doubleOrNull("audioPeak"))
    fun encode(receipt: ExportReceipt) = jsonObject("schemaVersion" to 1, "id" to receipt.id.text, "createdAt" to receipt.createdAt.reference, "filename" to receipt.filename,
        "projectID" to receipt.projectID.text, "projectTitle" to receipt.projectTitle, "revision" to receipt.revision, "sourceChecksums" to receipt.sourceChecksums,
        "width" to receipt.width, "height" to receipt.height, "framesPerSecond" to receipt.framesPerSecond, "codec" to receipt.codec.title, "resolution" to receipt.resolution.title, "audioPeak" to receipt.audioPeak)
}
