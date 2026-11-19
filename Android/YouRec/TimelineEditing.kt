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
    fun removing(start: Double, end: Double, state: EditState, preserving: EditTime? = null): Pair<EditState, EditTime?> {
        if (end - start < frame - 0.00001) return state to preserving
        val boundaries = (state.timeline.video.flatMap { listOf(it.start.seconds, it.end.seconds) } + state.timeline.audio.flatMap { listOf(it.start.seconds, it.end.seconds) }).toSet()
        fun aligned(time: Double): Double {
            val nearest = boundaries.minByOrNull { abs(it - time) } ?: return time
            return if (abs(nearest - time) < frame) nearest else time
        }
        val a = aligned(start); val b = aligned(end)
        if (b <= a) return state to preserving
        val (timeline, selection) = state.timeline.scoped(state.timeline.entireSelection(EditInterval(a, b)))
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
    fun inserting(clips: List<TimelineClip>, state: EditState, time: Double, pictureOnlySources: Set<UUID> = emptySet()): EditState {
        if (clips.isEmpty()) return state
        val result = state.copy()
        // Inserted copies need independent identities: re-editing one copy
        // must not silently edit the original or a later insertion.
        val addition = TrackTimeline.of(clips.map { it.copy(id = UUID.randomUUID()) }, pictureOnlySources)
        val point = EditTime.seconds(time.coerceIn(0.0, state.duration))
        result.timeline.insertTime(addition.duration, point)
        val layer = (result.timeline.video.maxOfOrNull { it.layer } ?: 0) + 1
        result.timeline.video = (result.timeline.video + addition.video.map { it.copy(start = it.start + point, layer = it.layer + layer) }).sortedBy { it.start }
        result.timeline.audio += addition.audio.map { it.copy(start = it.start + point) }
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
    fun removingText(id: UUID, state: EditState): EditState {
        val result = state.copy()
        result.timeline.replaceContents(result.timeline.video.filter { item -> item.content.texts?.any { it.id == id } == true }
            .associate { it.id to it.content.copy(texts = it.content.texts!!.filter { old -> old.id != id }.ifEmpty { null }) })
        return result
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
