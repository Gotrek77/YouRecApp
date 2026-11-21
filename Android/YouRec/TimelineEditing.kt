package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/// The editor's commands as pure functions from one edit to the next. The
/// interface commits the result; nothing here touches media or storage.
object TimelineEditing {
    const val frame = 1.0 / 30
    fun quantize(time: Double) = floor(time * 30 + 0.5) / 30

    fun split(state: EditState, time: Double, selectedItems: Set<UUID> = emptySet()): EditState {
        val result = state.copy()
        val point = EditTime.seconds(quantize(time))
        // Splitting inside a transition would cut footage both pictures are using.
        if (insideTransition(state, point.seconds)) return state
        if (selectedItems.isNotEmpty()) {
            // An explicit lane selection can split detached audio or picture on
            // its own. A selection elsewhere must not swallow the playhead cut.
            val selected = state.timeline.selection(selectedItems)
            val cutsVideo = state.timeline.video.any { it.id in selected.video && it.start < point && point < it.end }
            val cutsAudio = state.timeline.audio.any { it.id in selected.audio && it.start < point && point < it.end }
            if (cutsVideo || cutsAudio) { result.timeline.split(point, selected); return result }
        }
        result.timeline.split(point, result.timeline.entireSelection())
        return result
    }

    // Use exactly the same clip-relative boundaries for Cut and Delete.
    // Absolute quantization corrupts selections at non-frame-aligned take joins.
    /// Whether a time falls strictly inside the window of a transition between two pictures.
    fun insideTransition(state: EditState, time: Double) = TransitionLayout.joins(state.timeline).any { join ->
        join.transition != null && join.edge == 0 && join.range.start < time - 0.00001 && time < join.range.endInclusive - 0.00001
    }
    /// A marked range as a selection of everything inside it. Ends within a
    /// frame of a cut snap to that cut, so no sliver is left behind.
    fun rangeSelection(start: Double, end: Double, state: EditState): TimelineSelection? {
        if (end - start < frame - 0.00001) return null
        val boundaries = (state.timeline.video.flatMap { listOf(it.start.seconds, it.end.seconds) } + state.timeline.audio.flatMap { listOf(it.start.seconds, it.end.seconds) }).toSet()
        fun aligned(time: Double): Double {
            val nearest = boundaries.minByOrNull { abs(it - time) } ?: return time
            return if (abs(nearest - time) < frame) nearest else time
        }
        val a = aligned(start); val b = aligned(end)
        if (b <= a || insideTransition(state, a) || insideTransition(state, b)) return null
        return state.timeline.entireSelection(EditInterval(a, b))
    }
    fun removing(start: Double, end: Double, state: EditState, preserving: EditTime? = null): Pair<EditState, EditTime?> {
        val range = rangeSelection(start, end, state) ?: return state to preserving
        val (timeline, selection) = state.timeline.scoped(range)
        val position = timeline.remove(selection, preserving)
        return EditState(timeline, state.outputSize).also { it.canvasReference = state.canvasReference } to position
    }

    /// Delete whole selected parts. Linked picture and sound close their gap;
    /// an unlinked component leaves one.
    fun deleting(selection: TimelineSelection, state: EditState, preserving: EditTime? = null): Pair<EditState, EditTime?> {
        val result = state.copy()
        val (timeline, scope) = result.timeline.scoped(result.timeline.expanded(selection))
        val position = timeline.remove(scope, preserving)
        return EditState(timeline, state.outputSize).also { it.canvasReference = state.canvasReference } to position
    }

    /// Insert takes at one time, opening exactly their length for everything after it.
    fun inserting(clips: List<TimelineClip>, state: EditState, time: Double, pictureOnlySources: Set<UUID> = emptySet(), site: VideoInsertionSite? = null): EditState {
        if (clips.isEmpty()) return state
        val result = state.copy()
        // Inserted copies need independent identities: re-editing one copy
        // must not silently edit the original or a later insertion.
        val addition = TrackTimeline.of(clips.map { it.copy(id = UUID.randomUUID()) }, pictureOnlySources)
        val point = EditTime.seconds(time.coerceIn(0.0, state.duration))
        // At the start, the end or a gap, free picture time is used before any time is opened.
        val placement = site?.placement(state.timeline, addition.duration) ?: VideoInsertionSite.Placement(point, point, addition.duration)
        result.timeline.insertTime(placement.ripple, placement.rippleAt, atPictureGap = site != null)
        val layer = (result.timeline.video.maxOfOrNull { it.layer } ?: 0) + 1
        result.timeline.video = (result.timeline.video + addition.video.map { it.copy(start = it.start + placement.start, layer = it.layer + layer) }).sortedBy { it.start }
        result.timeline.audio += addition.audio.map { it.copy(start = it.start + placement.start) }
        result.timeline.links += addition.links
        return result
    }

    /// Apply a clip tool to the selected pictures, or to every picture when none
    /// is selected. Parts split from one take share a tool's identity, as on iPhone.
    fun applying(selection: TimelineSelection, state: EditState, change: (TimelineClip) -> TimelineClip): EditState {
        val result = state.copy()
        val targets = result.timeline.expanded(selection).video.ifEmpty { result.timeline.video.map { it.id }.toSet() }
        result.timeline.replaceContents(result.timeline.video.filter { it.id in targets }.associate { it.id to change(it.content) })
        return result
    }
    fun settingSpeed(rate: Double, selection: TimelineSelection, state: EditState): EditState {
        val bounded = rate.coerceIn(0.25, 4.0)
        return applying(selection, state) { clip -> clip.copy(speed = if (bounded == 1.0) null else SpeedSettings(clip.speed?.id ?: java.util.UUID.randomUUID(), bounded)) }
    }
    fun settingMirror(horizontal: Boolean, vertical: Boolean, selection: TimelineSelection, state: EditState) = applying(selection, state) { clip ->
        clip.copy(mirror = if (horizontal || vertical) MirrorSettings(clip.mirror?.id ?: java.util.UUID.randomUUID(), horizontal, vertical) else null)
    }
    fun settingCrop(crop: CropSettings?, selection: TimelineSelection, state: EditState) = applying(selection, state) { clip ->
        clip.copy(crop = crop?.copy(id = clip.crop?.id ?: java.util.UUID.randomUUID()))
    }
    /// A change to the video effects of the selected clips or the whole project.
    /// `change` also gets the clip's source time at the playhead, or null when
    /// the playhead is not on that clip (keyframes are only set where it is).
    fun settingVideoEffects(selection: TimelineSelection, state: EditState, playhead: Double, change: (TimelineClip, Double?) -> TimelineClip): EditState {
        val result = state.copy()
        val targets = result.timeline.expanded(selection).video.ifEmpty { result.timeline.video.map { it.id }.toSet() }
        val at = EditTime.seconds(playhead)
        result.timeline.replaceContents(result.timeline.video.filter { it.id in targets }.associate { item ->
            item.id to change(item.content, if (at in item.interval) item.mapping.source(at - item.start) else null)
        })
        return result
    }
    /// Add one effect to the selected clips (or all): one id for all of them,
    /// each over its own clip, Negative Strobe phased by where each clip starts.
    fun addingEffect(kind: VideoEffectID, selection: TimelineSelection, state: EditState): EditState {
        val result = state.copy()
        val targets = result.timeline.expanded(selection).video.ifEmpty { result.timeline.video.map { it.id }.toSet() }
        val items = result.timeline.video.filter { it.id in targets }
        val first = items.minOfOrNull { it.start.seconds } ?: return state
        val id = UUID.randomUUID()
        result.timeline.replaceContents(items.associate { it.id to VideoEffectEditing.adding(it.content, kind, id, it.start.seconds - first) })
        return result
    }
    /// Freeze frame, as on iPhone: a hold of the frame under the playhead is
    /// inserted there (three seconds), never replacing the footage after it.
    /// Null inside a transition or where pictures overlap: no single frame to hold.
    fun freezing(state: EditState, time: Double): Pair<EditState, UUID>? {
        val position = if (time >= state.timeline.duration.seconds - frame / 2) state.timeline.duration.seconds - frame else maxOf(0.0, quantize(time))
        val at = EditTime.seconds(position)
        val active = state.timeline.video.filter { at in it.interval }
        if (active.size != 1 || insideTransition(state, position)) return null
        val item = active.single()
        val clip = item.content
        if (clip.frozenFrame != null) return null
        val sourceTime = minOf(clip.end - frame, maxOf(clip.start, item.mapping.source(at - item.start) ?: return null))
        val result = split(state, position)
        val point = EditTime.seconds(quantize(position))
        val effect = VideoEffectSettings(kind = VideoEffectID.freezeFrame, start = sourceTime, end = minOf(clip.end, sourceTime + frame))
        val hold = clip.copy(id = UUID.randomUUID(), start = sourceTime, end = minOf(clip.end, sourceTime + frame), pauses = null, speed = null,
            frozenFrame = FrozenFrameSegment(sourceTime, effect.number("hold")),
            videoEffects = (clip.videoEffects ?: emptyList()).filter { it.kind.descriptor.family != EffectFamily.Time } + effect, effectWindows = null)
        val length = EditTime.seconds(hold.duration)
        result.timeline.insertTime(length, point)
        val occurrence = UUID.randomUUID()
        result.timeline.video = result.timeline.video + VideoTimelineItem(occurrence, point, hold, item.layer)
        // As on iPhone, the hold has its (silent) sound, linked: it moves with the
        // takes around it, and removing it closes up like removing a take.
        val sound = AudioTimelineItem(occurrenceID = occurrence, sourceID = hold.sourceID, start = point, mapping = SourcePlaybackMap(hold), followsVideo = hold.id)
        result.timeline.audio = result.timeline.audio + sound
        result.timeline.links = result.timeline.links + TimelineLink(members = setOf(hold.id, sound.id))
        return result to hold.id
    }
    /// A hold made longer or shorter: later footage moves with its end.
    fun settingHold(state: EditState, id: UUID, seconds: Double): EditState {
        val item = state.timeline.video.firstOrNull { it.id == id } ?: return state
        val frozen = item.content.frozenFrame ?: return state
        val duration = seconds.coerceIn(0.5, 10.0)
        val delta = EditTime.seconds(duration) - item.mapping.duration
        if (delta == EditTime.zero) return state
        val content = item.content.copy(frozenFrame = frozen.copy(duration = duration), videoEffects = item.content.videoEffects?.map {
            if (it.kind == VideoEffectID.freezeFrame) it.setting("hold", EffectValue.Number(duration)) else it })
        // A linked hold retimes like a take: the parts after it move with its end.
        if (state.timeline.links.any { id in it.members }) return state.copy().also { it.timeline.replaceContents(mapOf(id to content)) }
        if (delta > EditTime.zero) {
            val result = state.copy()
            result.timeline.insertTime(delta, item.end)
            result.timeline.replaceContents(mapOf(id to content))
            return result
        }
        // Shorter: the hold ends earlier and the footage after it closes up.
        val result = state.copy(); result.timeline.replaceContents(mapOf(id to content))
        val end = item.start + EditTime.seconds(duration)
        if (result.timeline.gap(end) != null) result.timeline.closeGap(end)
        return result
    }
    /// The picture a title made at this time would sit on: the front one.
    fun pictureAt(time: Double, state: EditState): VideoTimelineItem? {
        val at = EditTime.seconds(time)
        return state.timeline.video.filter { at in it.interval }.sortedWith(TrackTimeline.backToFront).lastOrNull()
    }
    fun textsAt(time: Double, state: EditState): List<TextOverlay> {
        val item = pictureAt(time, state) ?: return emptyList()
        val source = item.mapping.source(EditTime.seconds(time) - item.start) ?: return emptyList()
        return item.content.texts?.filter { it.start <= source && source < it.end } ?: emptyList()
    }
    /// Put a title on the picture under `time`, from there for `seconds` of
    /// playback or to the end of that clip, whichever comes first.
    fun addingText(text: TextOverlay, time: Double, seconds: Double, state: EditState): Pair<EditState, UUID?> {
        val item = pictureAt(time, state) ?: return state to null
        val start = item.mapping.source(EditTime.seconds(time) - item.start) ?: return state to null
        val end = minOf(item.content.end, start + seconds * item.content.rate)
        if (end - start < frame * item.content.rate) return state to null
        val placed = text.copy(start = start, end = end)
        val result = state.copy()
        result.timeline.replaceContents(mapOf(item.id to item.content.copy(texts = (item.content.texts ?: emptyList()) + placed)))
        return result to placed.id
    }
    /// Parts split from one take share a title's identity: all of them change together.
    fun updatingText(text: TextOverlay, state: EditState): EditState {
        val result = state.copy()
        result.timeline.replaceContents(result.timeline.video.filter { item -> item.content.texts?.any { it.id == text.id } == true }
            .associate { it.id to it.content.copy(texts = it.content.texts!!.map { old -> if (old.id == text.id) text else old }) })
        return result
    }
    fun imagesAt(time: Double, state: EditState): List<ImageOverlay> {
        val item = pictureAt(time, state) ?: return emptyList()
        val source = item.mapping.source(EditTime.seconds(time) - item.start) ?: return emptyList()
        return item.content.visibleImages(source)
    }
    /// Put an image on the picture under `time`, as a title is placed.
    fun addingImage(image: ImageOverlay, time: Double, seconds: Double, state: EditState): Pair<EditState, UUID?> {
        val item = pictureAt(time, state) ?: return state to null
        val start = item.mapping.source(EditTime.seconds(time) - item.start) ?: return state to null
        val end = minOf(item.content.end, start + seconds * item.content.rate)
        if (end - start < frame * item.content.rate) return state to null
        val placed = image.copy(start = start, end = end)
        val result = state.copy()
        result.timeline.replaceContents(mapOf(item.id to item.content.copy(images = (item.content.images ?: emptyList()) + placed)))
        return result to placed.id
    }
    fun updatingImage(image: ImageOverlay, state: EditState): EditState {
        val result = state.copy()
        result.timeline.replaceContents(result.timeline.video.filter { item -> item.content.images?.any { it.id == image.id } == true }
            .associate { it.id to it.content.copy(images = it.content.images!!.map { old -> if (old.id == image.id) image else old }) })
        return result
    }
    fun removingImage(id: UUID, state: EditState): EditState {
        val result = state.copy()
        result.timeline.replaceContents(result.timeline.video.filter { item -> item.content.images?.any { it.id == id } == true }
            .associate { it.id to it.content.copy(images = it.content.images!!.filter { old -> old.id != id }.ifEmpty { null }) })
        return result
    }
    fun removingText(id: UUID, state: EditState): EditState {
        val result = state.copy()
        result.timeline.replaceContents(result.timeline.video.filter { item -> item.content.texts?.any { it.id == id } == true }
            .associate { it.id to it.content.copy(texts = it.content.texts!!.filter { old -> old.id != id }.ifEmpty { null }) })
        return result
    }

    /// A grade on the selected clips or the whole project. An empty grade is removed.
    fun settingColor(color: ColorSettings?, selection: TimelineSelection, state: EditState) = applying(selection, state) { clip ->
        clip.copy(color = color?.takeIf { !it.isIdentity }?.copy(id = clip.color?.id ?: UUID.randomUUID()))
    }
    /// A project-wide picture shape. Null returns to the shape of the reference take.
    fun settingShape(size: CanvasSize?, state: EditState): EditState = state.copy().also { it.outputSize = size }

    /// One trim-handle gesture. Dragging outward reveals original material;
    /// dragging inward removes it from this occurrence, never from another lane.
    fun trimming(id: UUID, left: Boolean, delta: Double, state: EditState, sources: List<SourceMedia>, playhead: EditTime? = null): Pair<EditState, EditTime?> {
        val chosen = state.timeline.selection(id)
        val interval = state.timeline.interval(chosen) ?: return state to playhead
        if (left && delta < 0 || !left && delta > 0) {
            val result = state.copy()
            result.timeline.extend(chosen, left, EditTime.seconds(abs(delta)), sources)
            return result to playhead
        }
        val a = interval.start.seconds; val b = interval.end.seconds
        val point = if (left) minOf(b - frame, maxOf(a, a + delta)) else maxOf(a + frame, minOf(b, b + delta))
        val removed = if (left) EditInterval(a, point) else EditInterval(point, b)
        if (removed.duration.seconds <= 0.00001) return state to playhead
        val (timeline, scope) = state.timeline.scoped(chosen.copy(range = removed))
        val position = timeline.remove(scope, playhead)
        return EditState(timeline, state.outputSize).also { it.canvasReference = state.canvasReference } to position
    }
}

/// Stops for the previous/next cut buttons: every edge once, in time order.
object TimelineNavigation {
    fun stops(timeline: TrackTimeline): List<Double> {
        val edges = timeline.video.flatMap { listOf(it.start, it.end) } + timeline.audio.flatMap { listOf(it.start, it.end) } + EditTime.zero + timeline.duration
        return edges.toSortedSet().map { it.seconds }
    }
    fun jump(timeline: TrackTimeline, from: Double, forward: Boolean): Double? {
        val stops = stops(timeline)
        return if (forward) stops.firstOrNull { it > from + 0.02 } else stops.lastOrNull { it < from - 0.02 }
    }
}

/// Editing one kind of video effect on a clip, as the iPhone app's effects
/// editor does: one effect of a kind per clip here; a changed control with
/// animation edits the key at the playhead, never silently erases it; with
/// Animate on, a change adds a keyframe (and holds the old value at the start).
object VideoEffectEditing {
    fun effect(clip: TimelineClip, kind: VideoEffectID) = clip.videoEffects?.firstOrNull { it.kind == kind }
    private fun updating(clip: TimelineClip, kind: VideoEffectID, change: (VideoEffectSettings) -> VideoEffectSettings?): TimelineClip {
        val effects = (clip.videoEffects ?: emptyList()).mapNotNull { if (it.kind == kind) change(it) else it }
        // A removed effect takes its range windows with it; titles' and images' windows stay.
        val removed = (clip.videoEffects ?: emptyList()).map { it.id }.toSet() - effects.map { it.id }.toSet()
        val windows = clip.effectWindows?.filterKeys { it !in removed }
        return clip.copy(videoEffects = effects.ifEmpty { null }, effectWindows = windows?.ifEmpty { null })
    }
    /// A new effect on one clip, as the iPhone app adds it: over the clip's own
    /// source range, with the id shared by every clip it is added to at once.
    /// Negative Strobe keeps one rhythm across them (`phaseOffset`, seconds since
    /// the first clip) and through speed changes (its strobe time map).
    fun adding(clip: TimelineClip, kind: VideoEffectID, id: UUID = UUID.randomUUID(), phaseOffset: Double = 0.0): TimelineClip {
        if (effect(clip, kind) != null) return clip
        var effect = VideoEffectSettings(id = id, kind = kind, start = clip.start, end = clip.end)
        if (kind == VideoEffectID.negativeStrobe) {
            effect = effect.copy(phaseOffset = maxOf(0.0, phaseOffset))
            val spans = clip.playbackSpans
            if (clip.frozenFrame == null && (spans.size > 1 || spans.firstOrNull()?.rate != 1.0) && spans.isNotEmpty()) {
                var elapsed = 0.0; val points = mutableListOf(StrobeTimePoint(spans.first().start, 0.0))
                for (span in spans) {
                    if (span.start > points.last().source) points.add(StrobeTimePoint(span.start, elapsed))
                    elapsed += span.duration
                    points.add(StrobeTimePoint(span.end, elapsed))
                }
                effect = effect.copy(strobeTimeMap = points)
            }
        }
        return clip.copy(videoEffects = (clip.videoEffects ?: emptyList()) + effect)
    }
    fun removing(clip: TimelineClip, kind: VideoEffectID) = updating(clip, kind) { null }
    fun enabling(clip: TimelineClip, kind: VideoEffectID, enabled: Boolean) = updating(clip, kind) { it.copy(enabled = enabled) }
    fun resetting(clip: TimelineClip, kind: VideoEffectID) = updating(clip, kind) { it.copy(parameters = VideoEffectSettings.defaults(kind)) }
    fun setting(clip: TimelineClip, kind: VideoEffectID, parameter: String, value: EffectValue, source: Double?, animate: Boolean) = updating(clip, kind) { effect ->
        effect.copy(parameters = effect.parameters.map { p ->
            if (p.id != parameter || !p.kind.accepts(value)) p
            else if (animate && p.animatable && source != null) {
                val held = if (p.keyframes.isEmpty() && source > clip.start + 0.001) p.settingKeyframe(clip.start, p.value) else p
                held.settingKeyframe(source, value)
            } else {
                val base = p.copy(value = value)
                if (p.keyframes.isNotEmpty() && source != null) base.settingKeyframe(source, value) else base
            }
        })
    }
    /// Add a keyframe at the playhead with the value shown there, or remove the one already there.
    fun togglingKeyframe(clip: TimelineClip, kind: VideoEffectID, parameter: String, source: Double) = updating(clip, kind) { effect ->
        effect.copy(parameters = effect.parameters.map { p ->
            if (p.id != parameter || !p.animatable) p
            else if (p.keyframes.any { kotlin.math.abs(it.time - source) < 1.0 / 60 }) p.copy(keyframes = p.keyframes.filter { kotlin.math.abs(it.time - source) >= 1.0 / 60 })
            else p.settingKeyframe(source, p.value(source))
        })
    }
}
