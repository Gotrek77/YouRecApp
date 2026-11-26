package yourec

import java.io.File
import java.util.UUID

/// The saved document, key for key as the iPhone app's Codable types write it.
/// Editing data this build has no tool for is refused, with its name, instead
/// of being dropped on the next save: an edit made elsewhere is never lost here.
internal object ProjectCoding {
    private fun unsupported(tool: String): Nothing = throw ProjectError.UnsupportedTools(tool)
    private fun time(value: Fields) = EditTime.ticks(value.long("ticks"))
    fun encode(time: EditTime) = jsonObject("ticks" to time.ticks)
    private fun interval(value: Fields) = EditInterval(time(value.fields("start")), time(value.fields("end")))
    private fun <T> list(values: List<Any?>, decode: (Fields) -> T) = values.map { decode(Fields.of(it)) }
    private fun present(value: Any?) = when (value) { null -> false; is List<*> -> value.isNotEmpty(); else -> true }

    // TimelineClip
    private val clipTools = mapOf("stabilization" to "Stabilization",
        "openingTransition" to "Transitions", "transition" to "Transitions",
        "colorRegions" to "Color",
        "framingVariants" to "Crop, Mirror or Stabilization on a range")
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
            TextOverlay(text.uuid("id"), text.string("text"), text.double("start"), text.double("end"), text.double("x"), text.double("y"), text.double("size"),
                TextStyle.values().firstOrNull { it.name.lowercase() == text.string("style") } ?: throw ProjectError.InvalidDocument(),
                TextColor.values().firstOrNull { it.name.lowercase() == text.string("color") } ?: throw ProjectError.InvalidDocument(),
                text.fieldsOrNull("appearance")?.let { titleDesign(it) }, text.fieldsOrNull("labelHeadline")?.let { labelHeadline(it) },
                text.fieldsOrNull("motion")?.let { titleMotion(it) }, text.fieldsOrNull("playbackClock")?.let { titleClock(it) })
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
        // Swift writes a dictionary keyed by identity as a flat list: key, value, key, value.
        val windows = value.listOrNull("effectWindows")?.let { pairs ->
            if (pairs.size % 2 != 0) throw ProjectError.InvalidDocument()
            (0 until pairs.size / 2).associate { Fields.uuid(pairs[it * 2]) to (pairs[it * 2 + 1] as? List<*> ?: throw ProjectError.InvalidDocument()).map { i -> Fields.of(i).let { w -> SourceInterval(w.double("start"), w.double("end")) } } }
        }
        return TimelineClip(value.uuid("id"), value.uuid("sourceID"), value.double("start"), value.double("end"), speed, crop, mirror, texts, color, pauses, images,
            value.listOrNull("videoEffects")?.let(::videoEffects), windows,
            value.fieldsOrNull("frozenFrame")?.let { FrozenFrameSegment(it.double("sourceTime"), it.double("duration"), it.doubleOrNull("offset") ?: 0.0).also { f -> if (!f.valid) throw ProjectError.InvalidDocument() } })
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
            "size" to it.size, "style" to it.style.name.lowercase(), "color" to it.color.name.lowercase(), "appearance" to it.appearance?.let { design -> encode(design) },
            "labelHeadline" to it.labelHeadline?.let { group -> encode(group) }, "motion" to it.motion?.let { m -> encode(m) },
            "playbackClock" to it.playbackClock?.let { c -> jsonObject("duration" to c.duration, "points" to c.points.map { p -> jsonObject("source" to p.source, "elapsed" to p.elapsed) }) }) },
        "videoEffects" to clip.videoEffects?.map { encode(it) },
        "frozenFrame" to clip.frozenFrame?.let { jsonObject("sourceTime" to it.sourceTime, "duration" to it.duration, "offset" to it.offset) },
        "effectWindows" to clip.effectWindows?.entries?.sortedBy { it.key.text }?.flatMap { listOf(it.key.text, it.value.map { i -> jsonObject("start" to i.start, "end" to i.end) }) })

    private fun map(value: Fields) = SourcePlaybackMap(list(value.list("runs")) {
        PlaybackRun(time(it.fields("duration")), it.double("sourceStart"), it.double("sourceEnd"), it.bool("silent"))
    })
    fun encode(map: SourcePlaybackMap) = jsonObject("runs" to map.runs.map {
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
        val video = list(value.list("video")) {
            if (present(it.raw("spanningEffects"))) unsupported("effects spanning several clips")
            VideoTimelineItem(it.uuid("occurrenceID"), time(it.fields("start")), clip(it.fields("content")), it.int("layer"), time(it.fields("localOrigin")), map(it.fields("mapping")))
        }
        val audio = list(value.list("audio")) {
            if (present(it.raw("spanningEffects"))) unsupported("effects spanning several clips")
            AudioTimelineItem(it.uuid("id"), it.uuid("occurrenceID"), it.uuid("sourceID"), time(it.fields("start")), map(it.fields("mapping")), time(it.fields("localOrigin")),
                it.uuidOrNull("followsVideo"), it.double("volumeDB"), it.bool("muted"), fade(it.fieldsOrNull("fadeIn")), fade(it.fieldsOrNull("fadeOut")),
                it.listOrNull("censorRegions")?.let { regions -> list(regions, ::censor) } ?: emptyList(),
                it.listOrNull("enhancements")?.let { regions -> list(regions, ::enhancement) } ?: emptyList(),
                it.listOrNull("subtitleWords")?.let { words -> list(words, ::subtitleWord) } ?: emptyList())
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
            timeline.subtitles = value.fieldsOrNull("subtitles")?.let(::subtitleSettings)
            timeline.generated = value.listOrNull("generated")?.let { list(it) { g ->
                TimelineGeneratedRegion(g.uuid("id"), g.uuid("owner"), interval(g.fields("interval")), g.uuidOrNull("freezeVideo"))
            } } ?: emptyList()
        }
    }
    private fun subtitleWord(value: Fields) = SubtitleWord(value.uuid("id"), value.uuid("phraseID"), value.string("text"), value.double("start"), value.double("end"))
    private fun encode(word: SubtitleWord) = jsonObject("id" to word.id.text, "phraseID" to word.phraseID.text, "text" to word.text, "start" to word.start, "end" to word.end)
    private fun subtitleSettings(value: Fields): SubtitleSettings {
        if (present(value.raw("appearance")) || present(value.raw("motion"))) unsupported("Title designs in subtitles")
        return SubtitleSettings(value.uuid("id"), value.int("maxWords"),
            TextStyle.values().firstOrNull { it.name.lowercase() == value.string("style") } ?: throw ProjectError.InvalidDocument(),
            TextColor.values().firstOrNull { it.name.lowercase() == value.string("color") } ?: throw ProjectError.InvalidDocument(),
            value.double("size"), value.double("y"), value.string("language"), value.string("timingFingerprint"), value.doubleOrNull("x"),
            value.stringOrNull("font")?.let { raw -> SubtitleFont.values().firstOrNull { it.raw == raw } ?: throw ProjectError.InvalidDocument() })
    }
    private fun encode(settings: SubtitleSettings) = jsonObject("id" to settings.id.text, "maxWords" to settings.maxWords, "style" to settings.style.name.lowercase(),
        "color" to settings.color.name.lowercase(), "size" to settings.size, "y" to settings.y, "language" to settings.language,
        "timingFingerprint" to settings.timingFingerprint, "x" to settings.x, "font" to settings.font?.raw)
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
                "subtitleWords" to it.subtitleWords.takeIf { w -> w.isNotEmpty() }?.map { word -> encode(word) },
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
        "generated" to timeline.generated.map { jsonObject("id" to it.id.text, "owner" to it.owner.text, "interval" to encode(it.interval), "freezeVideo" to it.freezeVideo?.text) },
        "subtitles" to timeline.subtitles?.let { encode(it) })

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

    // TitleDesign: the iPhone's synthesized Codable writes every field, and the gradient only when set.
    private fun ink(value: Fields) = TitleInk(value.long("rgba").also { if (it !in 0..0xFFFFFFFFL) throw ProjectError.InvalidDocument() })
    private fun encode(ink: TitleInk): Map<String, Any?> = jsonObject("rgba" to ink.rgba)
    fun titleDesign(value: Fields): TitleDesign {
        val face = value.string("face")
        // A font imported into the project is drawn from its file, or as the system font while it is missing, as on iPhone.
        // The iPhone's other system fonts are not drawn here yet.
        if (!face.startsWith("import:") && TitleTypeface.of(face) == null) unsupported("the font $face")
        fun <T> choice(values: Array<T>, raw: String, name: (T) -> String) = values.firstOrNull { name(it) == raw } ?: throw ProjectError.InvalidDocument()
        val design = TitleDesign(face, value.int("weight"), value.bool("italic"), choice(TitleAlignment.values(), value.string("alignment")) { it.raw },
            value.double("tracking"), value.double("lineSpacing"), ink(value.fields("fill")),
            value.fieldsOrNull("gradient")?.let { TitleGradient(ink(it.fields("first")), ink(it.fields("last")), it.double("angle")) },
            value.bool("outlined"), ink(value.fields("border")), value.double("borderWidth"),
            value.bool("shadow"), ink(value.fields("shade")), value.double("shadowBlur"), value.double("shadowOffset"),
            choice(TitlePlate.values(), value.string("plate")) { it.raw }, ink(value.fields("backing")), value.double("padding"), value.double("radius"))
        if (!design.valid) throw ProjectError.InvalidDocument()
        return design
    }
    fun encode(d: TitleDesign): Map<String, Any?> = jsonObject("face" to d.face, "weight" to d.weight, "italic" to d.italic, "alignment" to d.alignment.raw,
        "tracking" to d.tracking, "lineSpacing" to d.lineSpacing, "fill" to encode(d.fill),
        "gradient" to d.gradient?.let { jsonObject("first" to encode(it.first), "last" to encode(it.last), "angle" to it.angle) },
        "outlined" to d.outlined, "border" to encode(d.border), "borderWidth" to d.borderWidth,
        "shadow" to d.shadow, "shade" to encode(d.shade), "shadowBlur" to d.shadowBlur, "shadowOffset" to d.shadowOffset,
        "plate" to d.plate.raw, "backing" to encode(d.backing), "padding" to d.padding, "radius" to d.radius)

    // LabelHeadline: every key is required, as the iPhone's synthesized decoder requires them; only the offsets are optional.
    fun labelHeadline(value: Fields): LabelHeadline {
        fun offset(it: Fields) = TitleCard.Offset(it.double("x"), it.double("y"))
        val card = value.fields("card").let { c -> TitleCard(ink(c.fields("fill")), ink(c.fields("border")), c.double("borderWidth"), c.double("padding"),
            c.double("radius"), c.double("spacing"), c.fieldsOrNull("labelOffset")?.let(::offset), c.fieldsOrNull("headlineOffset")?.let(::offset)) }
        val group = LabelHeadline(value.string("label"), titleDesign(value.fields("labelStyle")), titleDesign(value.fields("headlineStyle")),
            value.double("labelScale"), value.double("headlineScale"), card)
        if (!group.valid) throw ProjectError.InvalidDocument()
        return group
    }
    fun encode(g: LabelHeadline): Map<String, Any?> {
        fun offset(o: TitleCard.Offset?) = o?.let { jsonObject("x" to it.x, "y" to it.y) }
        val c = g.card
        return jsonObject("label" to g.label, "labelStyle" to encode(g.labelStyle), "headlineStyle" to encode(g.headlineStyle),
            "labelScale" to g.labelScale, "headlineScale" to g.headlineScale,
            "card" to jsonObject("fill" to encode(c.fill), "border" to encode(c.border), "borderWidth" to c.borderWidth, "padding" to c.padding,
                "radius" to c.radius, "spacing" to c.spacing, "labelOffset" to offset(c.labelOffset), "headlineOffset" to offset(c.headlineOffset)))
    }

    // TitleMotion: both moves and durations always, a direction only when chosen.
    fun titleMotion(value: Fields): TitleMotion {
        fun move(raw: String) = TitleMove.values().firstOrNull { it.raw == raw } ?: throw ProjectError.InvalidDocument()
        fun direction(raw: String?) = raw?.let { r -> TitleDirection.values().firstOrNull { it.raw == r } ?: throw ProjectError.InvalidDocument() }
        val motion = TitleMotion(move(value.string("entrance")), move(value.string("exit")), value.double("inSeconds"), value.double("outSeconds"),
            direction(value.stringOrNull("inDirection")), direction(value.stringOrNull("outDirection")))
        if (!motion.valid) throw ProjectError.InvalidDocument()
        return motion
    }
    fun encode(m: TitleMotion): Map<String, Any?> = jsonObject("entrance" to m.entrance.raw, "exit" to m.exit.raw, "inSeconds" to m.inSeconds, "outSeconds" to m.outSeconds,
        "inDirection" to m.inDirection?.raw, "outDirection" to m.outDirection?.raw)
    private fun titleClock(value: Fields): TitlePlaybackClock {
        val clock = TitlePlaybackClock(value.double("duration"), value.list("points").map { Fields.of(it).let { p -> StrobeTimePoint(p.double("source"), p.double("elapsed")) } })
        if (!clock.valid) throw ProjectError.InvalidDocument()
        return clock
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
        return SourceMedia(value.uuid("id"), value.string("filename"), value.double("duration"), Moment(value.double("recordedAt")),
            value.listOrNull("events")?.let { list(it, ::event) }, value.stringOrNull("sha256"), value.boolOrNull("audioOnly"), value.boolOrNull("isStillPhoto"), value.stringOrNull("originalName"),
            value.listOrNull("videoEffects")?.let(::videoEffects))
    }
    fun encode(source: SourceMedia) = jsonObject("id" to source.id.text, "filename" to source.filename, "duration" to source.duration,
        "recordedAt" to source.recordedAt.reference, "events" to source.events?.map { encode(it) }, "sha256" to source.sha256,
        "audioOnly" to source.audioOnly, "isStillPhoto" to source.isStillPhoto, "originalName" to source.originalName,
        "videoEffects" to source.videoEffects?.map { encode(it) })

    // Video effects, in the iPhone app's form. Unknown kinds are skipped, as
    // the iPhone app skips them; a known effect this build cannot draw yet is
    // refused by name rather than opened and silently not drawn.
    fun videoEffects(values: List<Any?>): List<VideoEffectSettings> = values.mapNotNull { raw ->
        val fields = Fields.of(raw)
        val kind = VideoEffectID.of(fields.string("kind")) ?: return@mapNotNull null
        if (kind !in VideoEffectRendering.drawn) unsupported("Video effects: " + kind.descriptor.displayName)
        if (present(fields.raw("motion"))) unsupported("animated Color Correction")
        val reference = VideoEffectSettings.defaults(kind)
        val stored = list(fields.list("parameters"), ::effectParameter)
        // Bounds, labels and choices belong to the catalogue; stored values and animation are kept.
        val parameters = stored.map { old -> reference.firstOrNull { it.id == old.id }?.copy(value = old.value, keyframes = old.keyframes) ?: old } +
            reference.filter { p -> stored.none { it.id == p.id } }
        VideoEffectSettings(fields.uuid("id"), kind, fields.bool("enabled"), fields.int("version"), fields.double("start"), fields.double("end"), parameters,
            fields.stringOrNull("analysisFile"), fields.stringOrNull("analysisDataFile"), fields.uuidOrNull("capturedEffectID"),
            fields.listOrNull("palette")?.map { rgb(Fields.of(it)) }, fields.raw("lockedPalette"), fields.intOrNull("algorithm"),
            fields.listOrNull("strobeTimeMap")?.map { Fields.of(it).let { p -> StrobeTimePoint(p.double("source"), p.double("elapsed")) } },
            fields.doubleOrNull("phaseOffset"), fields.doubleOrNull("releaseTime")).also { effect -> VideoEffectRendering.refusal(effect)?.let { unsupported(it) } }
    }
    private fun rgb(value: Fields) = EffectRGB(value.double("red"), value.double("green"), value.double("blue"))
    private fun encode(rgb: EffectRGB) = jsonObject("red" to rgb.red, "green" to rgb.green, "blue" to rgb.blue)
    // Swift's form for enums with associated values: {"case": {"_0": value}} or {"case": {"label": value}}.
    private fun effectValue(value: Fields): EffectValue {
        fun only(name: String) = value.fields(name)
        return when {
            value.has("number") -> EffectValue.Number(only("number").double("_0"))
            value.has("toggle") -> EffectValue.Toggle(only("toggle").bool("_0"))
            value.has("choice") -> EffectValue.Choice(only("choice").string("_0"))
            value.has("color") -> EffectValue.Color(rgb(only("color").fields("_0")))
            value.has("media") -> EffectValue.Media(only("media").stringOrNull("_0"))
            else -> throw ProjectError.InvalidDocument()
        }
    }
    private fun encode(value: EffectValue): Map<String, Any?> = when (value) {
        is EffectValue.Number -> jsonObject("number" to jsonObject("_0" to value.value))
        is EffectValue.Toggle -> jsonObject("toggle" to jsonObject("_0" to value.value))
        is EffectValue.Choice -> jsonObject("choice" to jsonObject("_0" to value.value))
        is EffectValue.Color -> jsonObject("color" to jsonObject("_0" to encode(value.value)))
        is EffectValue.Media -> jsonObject("media" to jsonObject("_0" to value.file))
    }
    private fun parameterKind(value: Fields): EffectParameterKind = when {
        value.has("slider") -> value.fields("slider").let { EffectParameterKind.Slider(it.double("min"), it.double("max"), it.double("defaultValue"), it.double("step")) }
        value.has("toggle") -> EffectParameterKind.Toggle(value.fields("toggle").bool("defaultValue"))
        value.has("stepper") -> value.fields("stepper").let { s -> EffectParameterKind.Stepper(s.list("values").map { Fields.double(it) }, s.double("defaultValue")) }
        value.has("color") -> EffectParameterKind.Color(rgb(value.fields("color").fields("defaultValue")))
        value.has("picker") -> value.fields("picker").let { p -> EffectParameterKind.Picker(p.list("options").map { it as? String ?: throw ProjectError.InvalidDocument() }, p.string("defaultValue")) }
        value.has("media") -> value.fields("media").let { EffectParameterKind.Media(it.bool("image"), it.bool("video")) }
        else -> throw ProjectError.InvalidDocument()
    }
    private fun encode(kind: EffectParameterKind): Map<String, Any?> = when (kind) {
        is EffectParameterKind.Slider -> jsonObject("slider" to jsonObject("min" to kind.min, "max" to kind.max, "defaultValue" to kind.defaultValue, "step" to kind.step))
        is EffectParameterKind.Toggle -> jsonObject("toggle" to jsonObject("defaultValue" to kind.defaultValue))
        is EffectParameterKind.Stepper -> jsonObject("stepper" to jsonObject("values" to kind.values, "defaultValue" to kind.defaultValue))
        is EffectParameterKind.Color -> jsonObject("color" to jsonObject("defaultValue" to encode(kind.defaultValue)))
        is EffectParameterKind.Picker -> jsonObject("picker" to jsonObject("options" to kind.options, "defaultValue" to kind.defaultValue))
        is EffectParameterKind.Media -> jsonObject("media" to jsonObject("image" to kind.image, "video" to kind.video))
    }
    private fun effectParameter(value: Fields) = EffectParameter(value.string("id"), value.string("title"), parameterKind(value.fields("kind")),
        effectValue(value.fields("value")), value.listOrNull("keyframes")?.let { keys -> list(keys) { EffectKeyframe(it.uuid("id"), it.double("time"), effectValue(it.fields("value"))) } } ?: emptyList(),
        value.boolOrNull("animatable") ?: true)
    private fun encode(p: EffectParameter) = jsonObject("id" to p.id, "title" to p.title, "kind" to encode(p.kind), "value" to encode(p.value),
        "keyframes" to p.keyframes.map { jsonObject("id" to it.id.text, "time" to it.time, "value" to encode(it.value)) }, "animatable" to p.animatable)
    fun encode(effect: VideoEffectSettings) = jsonObject("id" to effect.id.text, "kind" to effect.kind.name, "enabled" to effect.enabled, "version" to effect.version,
        "start" to effect.start, "end" to effect.end, "parameters" to effect.parameters.map { encode(it) }, "analysisFile" to effect.analysisFile,
        "analysisDataFile" to effect.analysisDataFile, "capturedEffectID" to effect.capturedEffectID?.text, "palette" to effect.palette?.map { encode(it) },
        "lockedPalette" to effect.lockedPalette, "algorithm" to effect.algorithm, "strobeTimeMap" to effect.strobeTimeMap?.map { jsonObject("source" to it.source, "elapsed" to it.elapsed) },
        "phaseOffset" to effect.phaseOffset, "releaseTime" to effect.releaseTime)

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
