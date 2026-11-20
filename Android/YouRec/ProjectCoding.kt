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
    private val clipTools = mapOf("stabilization" to "Stabilization",
        "openingTransition" to "Transitions", "transition" to "Transitions",
        "videoEffects" to "Video effects", "frozenFrame" to "Freeze frame", "colorRegions" to "Color",
        "effectWindows" to "effects limited to a range", "framingVariants" to "Crop, Mirror or Stabilization on a range")
    fun clip(value: Fields): TimelineClip {
        for ((key, tool) in clipTools) if (present(value.raw(key))) unsupported(tool)
        // The tools themselves are edited here; their animated and ranged forms are not yet.
        fun plain(tool: Fields, name: String): Fields {
            if (present(tool.raw("motion"))) unsupported("animated $name")
            if (present(tool.raw("activeRange"))) unsupported("$name on a range")
            return tool
        }
        val speed = value.fieldsOrNull("speed")?.let { SpeedSettings(it.uuid("id"), it.double("rate")) }
        val crop = value.fieldsOrNull("crop")?.let { plain(it, "Crop") }?.let { CropSettings(it.uuid("id"), it.double("x"), it.double("y"), it.double("zoom"), it.doubleOrNull("rotation") ?: 0.0) }
        val mirror = value.fieldsOrNull("mirror")?.let { plain(it, "Mirror") }?.let { MirrorSettings(it.uuid("id"), it.bool("horizontal"), it.bool("vertical")) }
        val texts = value.listOrNull("texts")?.let { items -> list(items) { text ->
            // Plain titles are edited here; designed, carded and animated ones are not yet.
            for ((key, tool) in mapOf("appearance" to "title designs", "labelHeadline" to "label and headline cards", "motion" to "animated titles", "playbackClock" to "titles limited to a range"))
                if (present(text.raw(key))) unsupported(tool)
            TextOverlay(text.uuid("id"), text.string("text"), text.double("start"), text.double("end"), text.double("x"), text.double("y"), text.double("size"),
                TextStyle.values().firstOrNull { it.name.lowercase() == text.string("style") } ?: throw ProjectError.InvalidDocument(),
                TextColor.values().firstOrNull { it.name.lowercase() == text.string("color") } ?: throw ProjectError.InvalidDocument())
        } }
        val color = value.fieldsOrNull("color")?.let { grade ->
            if (present(grade.raw("motion"))) unsupported("animated Color")
            if ((grade.doubleOrNull("highlights") ?: 0.0) != 0.0 || (grade.doubleOrNull("localRecovery") ?: 0.0) != 0.0) unsupported("Color highlight recovery")
            val lut = grade.fieldsOrNull("imported")?.let {
                if ((it.stringOrNull("input") ?: "rec709") != "rec709") unsupported("Log LUTs")
                ImportedLUT(it.string("filename"), it.string("name"))
            }
            ColorSettings(grade.uuid("id"), ColorLook.values().firstOrNull { it.raw == grade.string("look") } ?: throw ProjectError.InvalidDocument(), grade.double("amount"), lut,
                grade.doubleOrNull("exposure"), grade.doubleOrNull("warmth"), grade.doubleOrNull("tint"))
        }
        val pauses = value.fieldsOrNull("pauses")?.let { p ->
            fun interval(v: Any?) = Fields.of(v).let { SourceInterval(it.double("start"), it.double("end")) }
            PauseSettings(p.uuid("id"), p.double("thresholdDB"), p.double("minimum"), p.double("before"), p.double("after"), p.list("cuts").map(::interval),
                p.listOrNull("candidates")?.map { c -> Fields.of(c).let { DetectedPause(interval(it.raw("raw")), it.boolOrNull("enabled") ?: true) } },
                p.raw("analyzed")?.let(::interval),
                p.stringOrNull("levelMode")?.let { mode -> PauseLevelMode.values().firstOrNull { it.raw == mode } ?: throw ProjectError.InvalidDocument() })
        }
        val images = value.listOrNull("images")?.let { items -> list(items) { image ->
            if (present(image.raw("motion"))) unsupported("animated Image overlays")
            ImageOverlay(image.uuid("id"), image.string("filename"), image.double("start"), image.double("end"), image.double("x"), image.double("y"),
                image.double("width"), image.double("opacity"))
        } }
        return TimelineClip(value.uuid("id"), value.uuid("sourceID"), value.double("start"), value.double("end"), speed, crop, mirror, texts, color, pauses, images)
    }
    fun encode(clip: TimelineClip) = jsonObject("id" to clip.id.text, "sourceID" to clip.sourceID.text, "start" to clip.start, "end" to clip.end,
        "speed" to clip.speed?.let { jsonObject("id" to it.id.text, "rate" to it.rate) },
        "crop" to clip.crop?.let { jsonObject("id" to it.id.text, "x" to it.x, "y" to it.y, "zoom" to it.zoom, "rotation" to it.rotation) },
        "mirror" to clip.mirror?.let { jsonObject("id" to it.id.text, "horizontal" to it.horizontal, "vertical" to it.vertical) },
        "color" to clip.color?.let { jsonObject("id" to it.id.text, "look" to it.look.raw, "amount" to it.amount,
            "imported" to it.imported?.let { lut -> jsonObject("filename" to lut.filename, "name" to lut.name, "input" to "rec709") },
            "exposure" to it.exposure, "warmth" to it.warmth, "tint" to it.tint) },
        "pauses" to clip.pauses?.let { p ->
            fun encode(i: SourceInterval) = jsonObject("start" to i.start, "end" to i.end)
            jsonObject("id" to p.id.text, "thresholdDB" to p.thresholdDB, "minimum" to p.minimum, "before" to p.before, "after" to p.after,
                "cuts" to p.cuts.map(::encode), "candidates" to p.candidates?.map { jsonObject("raw" to encode(it.raw), "enabled" to it.enabled) },
                "analyzed" to p.analyzed?.let(::encode), "levelMode" to p.levelMode?.raw) },
        "images" to clip.images?.map { jsonObject("id" to it.id.text, "filename" to it.filename, "start" to it.start, "end" to it.end, "x" to it.x, "y" to it.y,
            "width" to it.width, "opacity" to it.opacity) },
        "texts" to clip.texts?.map { jsonObject("id" to it.id.text, "text" to it.text, "start" to it.start, "end" to it.end, "x" to it.x, "y" to it.y,
            "size" to it.size, "style" to it.style.name.lowercase(), "color" to it.color.name.lowercase()) })

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
        if (present(value.raw("subtitles"))) unsupported("Subtitles")
        val video = list(value.list("video")) {
            if (present(it.raw("spanningEffects"))) unsupported("effects spanning several clips")
            VideoTimelineItem(it.uuid("occurrenceID"), time(it.fields("start")), clip(it.fields("content")), it.int("layer"), time(it.fields("localOrigin")), map(it.fields("mapping")))
        }
        val audio = list(value.list("audio")) {
            if (present(it.raw("subtitleWords"))) unsupported("Subtitles")
            if (present(it.raw("spanningEffects"))) unsupported("effects spanning several clips")
            AudioTimelineItem(it.uuid("id"), it.uuid("occurrenceID"), it.uuid("sourceID"), time(it.fields("start")), map(it.fields("mapping")), time(it.fields("localOrigin")),
                it.uuidOrNull("followsVideo"), it.double("volumeDB"), it.bool("muted"), fade(it.fieldsOrNull("fadeIn")), fade(it.fieldsOrNull("fadeOut")),
                it.listOrNull("censorRegions")?.let { regions -> list(regions, ::censor) } ?: emptyList(),
                it.listOrNull("enhancements")?.let { regions -> list(regions, ::enhancement) } ?: emptyList())
        }
        val links = list(value.list("links")) { link -> TimelineLink(link.uuid("id"), link.list("members").map { Fields.uuid(it) }.toSet()) }
        val bookmarks = list(value.list("bookmarks")) { TimelineBookmark(it.uuid("id"), it.string("label"), anchor(it.fields("anchor"))) }
        val placements = list(value.list("placements")) { region ->
            // Swift writes a dictionary keyed by identity as a flat list: key, value, key, value.
            val pairs = region.list("videos")
            if (pairs.size % 2 != 0) throw ProjectError.InvalidDocument()
            VideoPlacementRegion(region.uuid("id"), region.list("participants").map { Fields.uuid(it) }.toSet(),
                (0 until pairs.size / 2).associate { Fields.uuid(pairs[it * 2]) to placement(Fields.of(pairs[it * 2 + 1])) })
        }
        return TrackTimeline(video, audio, links, bookmarks, placements).also { timeline ->
            timeline.transitions = value.listOrNull("transitions")?.let { list(it, ::transition) } ?: emptyList()
            timeline.generated = value.listOrNull("generated")?.let { list(it) { g ->
                TimelineGeneratedRegion(g.uuid("id"), g.uuid("owner"), interval(g.fields("interval")), g.uuidOrNull("freezeVideo"))
            } } ?: emptyList()
        }
    }
    private fun enhancement(value: Fields) = AudioEnhancementRegion(value.uuid("id"), interval(value.fields("interval")), list(value.list("effects"), ::effect))
    /// Kinds and models this build does not know are kept as written.
    private fun effect(value: Fields) = AudioEffect(value.uuid("id"), EffectKind(value.string("kind")), value.bool("enabled"), value.double("amount"),
        value.double("frequency"), value.int("implementationVersion"),
        value.fieldsOrNull("model")?.let { AudioModel(it.string("id"), it.string("weightsSHA256"), it.string("runtime")) })
    fun encode(effect: AudioEffect) = jsonObject("id" to effect.id.text, "kind" to effect.kind.raw, "enabled" to effect.enabled, "amount" to effect.amount,
        "frequency" to effect.frequency, "implementationVersion" to effect.implementationVersion,
        "model" to effect.model?.let { jsonObject("id" to it.id, "weightsSHA256" to it.weightsSHA256, "runtime" to it.runtime) })
    private fun encode(region: AudioEnhancementRegion) = jsonObject("id" to region.id.text, "interval" to encode(region.interval), "effects" to region.effects.map { encode(it) })
    private fun censor(value: Fields): AudioCensorRegion {
        val settings = value.fields("settings")
        fun <T : Enum<T>> choice(values: Array<T>, raw: String) = values.firstOrNull { it.name.lowercase() == raw } ?: throw ProjectError.InvalidDocument()
        return AudioCensorRegion(value.uuid("id"), interval(value.fields("interval")), time(value.fields("phaseOrigin")),
            AudioCensorSettings(settings.double("frequency"), settings.double("levelDB"), choice(CensorWaveform.values(), settings.string("waveform")),
                choice(CensorEnvelope.values(), settings.string("envelope"))), value.fieldsOrNull("envelopeInterval")?.let(::interval))
    }
    private fun encode(interval: EditInterval) = jsonObject("start" to encode(interval.start), "end" to encode(interval.end))
    private fun encode(region: AudioCensorRegion) = jsonObject("id" to region.id.text, "interval" to encode(region.interval), "phaseOrigin" to encode(region.phaseOrigin),
        "settings" to jsonObject("frequency" to region.settings.frequency, "levelDB" to region.settings.levelDB, "waveform" to region.settings.waveform.raw, "envelope" to region.settings.envelope.raw),
        "envelopeInterval" to region.envelopeInterval?.let { encode(it) })
    private fun placement(value: Fields): VideoPlacement {
        if (present(value.raw("motion"))) unsupported("animated Placement")
        val framing = value.fields("framing"); val crop = value.fieldsOrNull("edgeCrop")
        return VideoPlacement(value.double("x"), value.double("y"), value.double("width"), value.double("height"),
            PlacementFraming(framing.double("x"), framing.double("y"), framing.double("zoom")), value.bool("fill"), value.int("order"),
            crop?.let { PlacementEdgeCrop(it.double("left"), it.double("right"), it.double("top"), it.double("bottom")) })
    }
    private fun encode(value: VideoPlacement) = jsonObject("x" to value.x, "y" to value.y, "width" to value.width, "height" to value.height,
        "framing" to jsonObject("x" to value.framing.x, "y" to value.framing.y, "zoom" to value.framing.zoom), "fill" to value.fill, "order" to value.order,
        "edgeCrop" to value.edgeCrop?.let { jsonObject("left" to it.left, "right" to it.right, "top" to it.top, "bottom" to it.bottom) })
    fun encode(timeline: TrackTimeline) = jsonObject(
        "video" to timeline.video.map {
            jsonObject("occurrenceID" to it.occurrenceID.text, "start" to encode(it.start), "content" to encode(it.content), "layer" to it.layer,
                "localOrigin" to encode(it.localOrigin), "mapping" to encode(it.mapping))
        },
        "audio" to timeline.audio.map {
            jsonObject("id" to it.id.text, "occurrenceID" to it.occurrenceID.text, "sourceID" to it.sourceID.text, "start" to encode(it.start), "mapping" to encode(it.mapping),
                "localOrigin" to encode(it.localOrigin), "followsVideo" to it.followsVideo?.text, "volumeDB" to it.volumeDB, "muted" to it.muted,
                "fadeIn" to encode(it.fadeIn), "fadeOut" to encode(it.fadeOut), "enhancements" to it.enhancements.map { region -> encode(region) },
                "censorRegions" to it.censors.takeIf { c -> c.isNotEmpty() }?.map { region -> encode(region) })
        },
        // Sets are sorted so that an unchanged edit keeps its content hash.
        "links" to timeline.links.map { link -> jsonObject("id" to link.id.text, "members" to link.members.map { it.text }.sorted()) },
        "bookmarks" to timeline.bookmarks.map { jsonObject("id" to it.id.text, "label" to it.label, "anchor" to encode(it.anchor)) },
        "placements" to timeline.placements.map { region ->
            jsonObject("id" to region.id.text, "participants" to region.participants.map { it.text }.sorted(),
                "videos" to region.videos.entries.sortedBy { it.key.text }.flatMap { listOf(it.key.text, encode(it.value)) })
        },
        "transitions" to timeline.transitions.map { jsonObject("outgoing" to it.outgoing?.text, "incoming" to it.incoming?.text, "settings" to encode(it.settings),
            "origin" to encode(it.origin), "factor" to it.factor) },
        "generated" to timeline.generated.map { jsonObject("id" to it.id.text, "owner" to it.owner.text, "interval" to encode(it.interval), "freezeVideo" to it.freezeVideo?.text) })

    private fun transition(value: Fields): TimelineTransition {
        val s = value.fields("settings")
        fun framing(f: Fields) = TransitionFraming(f.double("x"), f.double("y"), f.double("zoom"))
        fun <T> choice(values: List<T>, raw: String, name: (T) -> String) = values.firstOrNull { name(it) == raw } ?: throw ProjectError.InvalidDocument()
        val settings = ClipTransition(s.uuid("id"), s.uuidOrNull("nextClipID"), choice(TransitionStyle.values().toList(), s.string("style")) { it.raw }, s.double("duration"),
            choice(TransitionAxis.values().toList(), s.string("axis")) { it.raw }, s.double("divider"), s.bool("reverse"),
            choice(TransitionAudio.values().toList(), s.string("audio")) { it.raw }, framing(s.fields("first")), framing(s.fields("second")),
            s.fieldsOrNull("fadeColor")?.let { TransitionColor(it.double("red"), it.double("green"), it.double("blue")) },
            s.doubleOrNull("hold"), s.doubleOrNull("audioOffset"), s.doubleOrNull("audioBlend"), s.doubleOrNull("wipeAngle"), s.boolOrNull("hardEdge"),
            s.boolOrNull("highlightsFirst"), s.intOrNull("flashCount"), s.doubleOrNull("flashAcceleration"), s.doubleOrNull("stillDissolve"), s.doubleOrNull("blurAmount"))
        settings.unsupported?.let { unsupported(it) }
        return TimelineTransition(value.uuidOrNull("outgoing"), value.uuidOrNull("incoming"), settings, time(value.fields("origin")), value.double("factor"))
    }
    private fun encode(t: ClipTransition): Map<String, Any?> {
        fun framing(f: TransitionFraming) = jsonObject("x" to f.x, "y" to f.y, "zoom" to f.zoom)
        return jsonObject("id" to t.id.text, "nextClipID" to t.nextClipID?.text, "style" to t.style.raw, "duration" to t.duration, "axis" to t.axis.raw,
            "divider" to t.divider, "reverse" to t.reverse, "audio" to t.audio.raw, "first" to framing(t.first), "second" to framing(t.second),
            "fadeColor" to t.fadeColor?.let { jsonObject("red" to it.red, "green" to it.green, "blue" to it.blue) }, "hold" to t.hold, "audioOffset" to t.audioOffset,
            "audioBlend" to t.audioBlend, "wipeAngle" to t.wipeAngle, "hardEdge" to t.hardEdge, "highlightsFirst" to t.highlightsFirst, "flashCount" to t.flashCount,
            "flashAcceleration" to t.flashAcceleration, "stillDissolve" to t.stillDissolve, "blurAmount" to t.blurAmount)
    }

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
        return SourceMedia(value.uuid("id"), value.string("filename"), value.double("duration"), Moment(value.double("recordedAt")),
            value.listOrNull("events")?.let { list(it, ::event) }, value.stringOrNull("sha256"), value.boolOrNull("audioOnly"), value.boolOrNull("isStillPhoto"), value.stringOrNull("originalName"))
    }
    fun encode(source: SourceMedia) = jsonObject("id" to source.id.text, "filename" to source.filename, "duration" to source.duration,
        "recordedAt" to source.recordedAt.reference, "events" to source.events?.map { encode(it) }, "sha256" to source.sha256,
        "audioOnly" to source.audioOnly, "isStillPhoto" to source.isStillPhoto, "originalName" to source.originalName)

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
        "automaticCanvasVersion" to 1, "recordingHistoryVersion" to 1,
        "recordingInsertion" to project.recordingInsertion, "recordingInsertionStep" to project.recordingInsertionStep,
        "recordingSite" to project.recordingSite?.let { jsonObject("site" to encode(it.site), "firstCapture" to it.firstCapture) })

    // Swift's enum coding: {"start": {}}, {"end": {}}, {"gap": {"after": time}}.
    private fun encode(site: VideoInsertionSite) = when (site) {
        VideoInsertionSite.Start -> jsonObject("start" to jsonObject())
        VideoInsertionSite.End -> jsonObject("end" to jsonObject())
        is VideoInsertionSite.Gap -> jsonObject("gap" to jsonObject("after" to encode(site.after)))
    }
    private fun site(value: Fields): VideoInsertionSite = when {
        value.has("start") -> VideoInsertionSite.Start
        value.has("end") -> VideoInsertionSite.End
        value.has("gap") -> VideoInsertionSite.Gap(time(value.fields("gap").fields("after")))
        else -> throw ProjectError.InvalidDocument()
    }

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
        project.recordingInsertion = values.doubleOrNull("recordingInsertion")
        project.recordingSite = values.fieldsOrNull("recordingSite")?.let { RecordingInsertionSite(site(it.fields("site")), it.int("firstCapture")) }
        project.recordingInsertionStep = if (summaryFile == null) values.intOrNull("recordingInsertionStep") else null
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
    fun encode(receipt: ExportReceipt) = jsonObject("schemaVersion" to 2, "id" to receipt.id.text, "createdAt" to receipt.createdAt.reference, "filename" to receipt.filename,
        "projectID" to receipt.projectID.text, "projectTitle" to receipt.projectTitle, "revision" to receipt.revision, "sourceChecksums" to receipt.sourceChecksums,
        "width" to receipt.width, "height" to receipt.height, "framesPerSecond" to receipt.framesPerSecond, "codec" to receipt.codec.title, "resolution" to receipt.resolution.title, "audioPeak" to receipt.audioPeak)
}
