package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

// A join belongs to these exact neighbours. Moving or deleting a clip must never
// silently apply its transition to unrelated footage. Source trims stay intact.
enum class TransitionStyle(val raw: String, val title: String) {
    Dissolve("dissolve", "Dissolve"), FadeColor("fadeBlack", "Fade through color"), Wipe("wipe", "Wipe"), Split("split", "Split screen"),
    Hitchcock("hitchcock", "Hitchcock"), Whip("whip", "Whip pan"), FlashCut("flashCut", "Flash Cut"), Still("still", "Still"), Defocus("defocus", "Defocus");
    val supportsHold: Boolean get() = this != FlashCut && this != Still
}
enum class TransitionAxis(val raw: String) { Horizontal("horizontal"), Vertical("vertical") }
enum class TransitionAudio(val raw: String) { Crossfade("crossfade"), First("first"), Second("second") }
data class TransitionFraming(val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0)
data class TransitionColor(val red: Double, val green: Double, val blue: Double) {
    companion object { val black = TransitionColor(0.0, 0.0, 0.0) }
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }
}

/// The iPhone app's transition settings, kept whole so a document round-trips.
data class ClipTransition(val id: UUID = UUID.randomUUID(), val nextClipID: UUID? = null, val style: TransitionStyle = TransitionStyle.Dissolve,
                          val duration: Double = 0.5, val axis: TransitionAxis = TransitionAxis.Horizontal, val divider: Double = 0.5, val reverse: Boolean = false,
                          val audio: TransitionAudio = TransitionAudio.Crossfade, val first: TransitionFraming = TransitionFraming(), val second: TransitionFraming = TransitionFraming(),
                          val fadeColor: TransitionColor? = null, val hold: Double? = null, val audioOffset: Double? = null, val audioBlend: Double? = null,
                          val wipeAngle: Double? = null, val hardEdge: Boolean? = null, val highlightsFirst: Boolean? = null, val flashCount: Int? = null,
                          val flashAcceleration: Double? = null, val stillDissolve: Double? = null, val blurAmount: Double? = null) {
    companion object { const val maximumDuration = 604800.0 }
    val resolvedFadeColor: TransitionColor get() = fadeColor ?: TransitionColor.black
    val holdDuration: Double get() = if (style.supportsHold) hold ?: 0.0 else 0.0
    val customAudio: Boolean get() = audioOffset != null || audioBlend != null
    val defaultAudioBlend: Double get() = if (style == TransitionStyle.Still) 0.0 else duration + (if (style == TransitionStyle.FadeColor) 0.0 else holdDuration)
    val valid: Boolean get() = duration.isFinite() && duration > 0 && duration <= maximumDuration && divider.isFinite() && divider in 0.15..0.85 && resolvedFadeColor.valid
        && listOf(first, second).all { it.x.isFinite() && it.y.isFinite() && it.zoom.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 && it.zoom in 1.0..4.0 }
        && listOfNotNull(hold, audioBlend, stillDissolve).all { it.isFinite() && it in 0.0..maximumDuration }
        && (audioOffset?.let { it.isFinite() && abs(it) <= maximumDuration } ?: true)
        && (wipeAngle?.let { it.isFinite() && it in -360.0..360.0 } ?: true)
        && (flashCount?.let { it in 1..20 } ?: true)
        && (flashAcceleration?.let { it.isFinite() && it in 0.25..4.0 } ?: true)
        && (blurAmount?.let { it.isFinite() && it in 0.0..1.0 } ?: true)
    /// What this Android version draws: dissolves and fades through a colour, without holds or custom sound timing.
    val unsupported: String? get() = when {
        style != TransitionStyle.Dissolve && style != TransitionStyle.FadeColor -> style.title + " transitions"
        hold != null && hold != 0.0 -> "transition holds"
        customAudio -> "transition sound timing"
        else -> null
    }
}

/// A transition between two pictures (or a fade at an edge, with one side
/// absent), with its resolved place on the edit clock.
data class TimelineTransition(val outgoing: UUID?, val incoming: UUID?, val settings: ClipTransition, val origin: EditTime, val factor: Double = 1.0) {
    val id: UUID get() = settings.id
    val edge: Int get() = if (outgoing == null) -1 else if (incoming == null) 1 else 0
    val timing: TransitionTiming get() = TransitionTiming(settings, factor, edge)
}
/// Time the edit holds for a transition that is not footage: a fade's colour.
data class TimelineGeneratedRegion(val id: UUID, val owner: UUID, val interval: EditInterval, val freezeVideo: UUID? = null)

// Relative times use the beginning of the picture effect as zero. Both tracks
// keep the same media clock; the union of picture and sound requirements decides
// how far the next clip overlaps (or follows) the previous one.
class TransitionTiming(settings: ClipTransition?, factor: Double = 1.0, edge: Int = 0) {
    var style: TransitionStyle? = null; private set
    var motion = 0.0; private set
    var hold = 0.0; private set
    var videoDuration = 0.0; private set
    var audioIn = 0.0 to 0.0; private set
    var audioOut = 0.0 to 0.0; private set
    var leftEnd = 0.0; private set
    var rightStart = 0.0; private set
    var lower = 0.0; private set
    var upper = 0.0; private set
    var cut = 0.0; private set
    var leftCost = 0.0; private set
    var rightCost = 0.0; private set
    val overlap: Double get() = leftEnd - rightStart

    init {
        if (settings != null) {
            style = settings.style
            val still = settings.style == TransitionStyle.Still
            motion = (if (still) settings.stillDissolve ?: 0.0 else settings.duration) * factor
            // Solid gaps and silent freezes need no source handles. Custom audio
            // can make the neighbouring source run underneath them, so those holds
            // must then participate in fitting, just like moving-picture holds.
            hold = if (still) settings.duration * (if (settings.customAudio && edge != 1) factor else 1.0)
                   else settings.holdDuration * (if (settings.style == TransitionStyle.FadeColor && edge == 0 && !settings.customAudio) 1.0 else factor)
            if (!still && motion + hold < TimelineEditing.frame - 0.000001) { motion = 0.0; hold = 0.0 }
            videoDuration = motion + hold
            var aNeed = videoDuration; var bNeed = 0.0
            if (settings.style == TransitionStyle.FadeColor && edge == 0) { aNeed = motion / 2; bNeed = motion / 2 + hold }
            if (still) { aNeed = 0.0; bNeed = hold }
            var offset = (settings.audioOffset ?: 0.0) * factor
            var blend = (settings.audioBlend ?: (if (edge != 0 && settings.style == TransitionStyle.FadeColor) settings.duration + settings.holdDuration else settings.defaultAudioBlend)) * factor
            if (settings.style == TransitionStyle.Split && settings.audioOffset == null && settings.audioBlend == null) {
                if (settings.audio == TransitionAudio.First) { offset = maxOf(0.0, videoDuration - 0.008); blend = minOf(0.008, videoDuration) }
                if (settings.audio == TransitionAudio.Second) blend = minOf(0.008, videoDuration)
            }
            if (edge == -1) {
                val begin = offset + (if (still && !settings.customAudio) hold else 0.0)
                audioIn = begin to begin + blend
                rightStart = minOf(bNeed, audioIn.first)
                lower = minOf(0.0, rightStart); upper = maxOf(videoDuration, audioIn.second)
                rightCost = upper - rightStart
            } else if (edge == 1) {
                audioOut = offset to offset + blend
                leftEnd = maxOf(aNeed, audioOut.second)
                lower = minOf(0.0, audioOut.first); upper = maxOf(videoDuration, leftEnd)
                leftCost = leftEnd - lower
            } else {
                if ((settings.style == TransitionStyle.FadeColor || still) && !settings.customAudio) {
                    audioOut = offset to offset + blend / 2
                    audioIn = offset + blend / 2 + hold to offset + blend + hold
                } else { audioIn = offset to offset + blend; audioOut = audioIn }
                leftEnd = maxOf(aNeed, audioOut.second)
                rightStart = minOf(bNeed, audioIn.first)
                lower = minOf(0.0, rightStart, audioOut.first)
                upper = maxOf(videoDuration, leftEnd, audioIn.second)
                leftCost = leftEnd - lower; rightCost = upper - rightStart
            }
            cut = if (still) hold else videoDuration / 2
        }
    }
    fun progress(elapsed: Double): Double {
        if (style == TransitionStyle.Still) return if (motion > 0) ((elapsed - hold) / motion).coerceIn(0.0, 1.0) else if (elapsed < hold) 0.0 else 1.0
        if (motion <= 0) return if (elapsed < videoDuration / 2) 0.0 else 1.0
        if (elapsed < motion / 2) return (elapsed / motion).coerceIn(0.0, 1.0)
        if (elapsed <= motion / 2 + hold) return 0.5
        return ((elapsed - hold) / motion).coerceIn(0.0, 1.0)
    }
}

/// One place where a transition can go: the start, a cut between two
/// pictures, or the end, with where its window lies.
class TransitionJoin(val index: Int, val time: Double, val range: ClosedFloatingPointRange<Double>, val maximum: Double, val transition: ClipTransition?,
                     val origin: Double = 0.0, val timing: TransitionTiming = TransitionTiming(null), val factor: Double = 1.0, val selectable: Boolean = true,
                     val edge: Int = 0, val outgoingID: UUID? = null, val incomingID: UUID? = null) {
    val title: String get() = if (index == 0) "Start" else "Join $index"
}

object TransitionLayout {
    fun joins(timeline: TrackTimeline): List<TransitionJoin> {
        val pictures = timeline.chronologicalVideo
        if (pictures.isEmpty()) return emptyList()
        val built = ArrayList<TransitionJoin>()
        val last = pictures.maxByOrNull { it.end }!!
        val endings = pictures.groupBy { it.end }
        val byID = pictures.associateBy { it.id }
        for (index in 0..pictures.size) {
            val right = pictures.getOrNull(index)
            val relation = timeline.transitions.firstOrNull { it.incoming == right?.id && (right != null || it.outgoing == last.id) }
            val left: VideoTimelineItem? = when {
                relation != null -> relation.outgoing?.let { byID[it] }
                right != null && index > 0 -> {
                    val ending = endings[right.start] ?: emptyList()
                    ending.filter { it.layer <= right.layer }.maxWithOrNull(TrackTimeline.backToFront) ?: ending.minWithOrNull(TrackTimeline.backToFront) ?: pictures[index - 1]
                }
                else -> if (right == null) last else null
            }
            val edge = if (left == null) -1 else if (right == null) 1 else 0
            val timing = TransitionTiming(relation?.settings, relation?.factor ?: 1.0, edge)
            val origin = relation?.origin?.seconds ?: left?.end?.seconds ?: right?.start?.seconds ?: 0.0
            val point = if (relation == null) origin else if (edge == -1) right!!.start.seconds else if (edge == 1) left!!.end.seconds else origin + timing.cut
            built.add(TransitionJoin(index, point, maxOf(0.0, origin + timing.lower)..maxOf(0.0, origin + timing.upper),
                minOf(left?.mapping?.duration?.seconds ?: 604800.0, right?.mapping?.duration?.seconds ?: 604800.0), relation?.settings, origin, timing, relation?.factor ?: 1.0,
                relation != null || (left == null && right?.start == EditTime.zero) || right == null || left?.end == right.start, edge, left?.id, right?.id))
        }
        // Moving a clip can leave its transition away from an ordinary
        // sequence boundary. Keep every explicit relation editable by identity.
        for (relation in timeline.transitions) {
            if (built.any { it.transition?.id == relation.id }) continue
            val left = pictures.firstOrNull { it.id == relation.outgoing }; val right = pictures.firstOrNull { it.id == relation.incoming }
            val edge = if (left == null) -1 else if (right == null) 1 else 0
            val timing = TransitionTiming(relation.settings, relation.factor, edge)
            val origin = relation.origin.seconds; val end = maxOf(0.0, origin + timing.upper)
            built.add(TransitionJoin(built.size, if (edge == -1) maxOf(0.0, origin) else if (edge == 1) end else origin + timing.cut,
                maxOf(0.0, origin + timing.lower)..end, minOf(left?.mapping?.duration?.seconds ?: 604800.0, right?.mapping?.duration?.seconds ?: 604800.0),
                relation.settings, origin, timing, relation.factor, true, edge, left?.id, right?.id))
        }
        return built
    }

    /// Put a transition at a join, or remove it with null. The next picture
    /// moves to overlap the previous one by what the transition needs; a
    /// transition that needs more footage than there is is shortened to fit.
    fun setting(state: EditState, value: ClipTransition?, index: Int): EditState {
        val boundary = joins(state.timeline).firstOrNull { it.index == index }
        if (boundary == null || !boundary.selectable) return state
        val result = state.copy(); val timeline = result.timeline
        val left = timeline.video.firstOrNull { it.id == boundary.outgoingID }
        val right = timeline.video.firstOrNull { it.id == boundary.incomingID }
        timeline.transitions = timeline.transitions.filter { !(it.outgoing == left?.id && it.incoming == right?.id) }
        timeline.pruneRelations()
        if (value == null) return result
        // Each picture has one incoming and one outgoing transition slot.
        // A new cut transition replaces edge fades on those same sides.
        timeline.transitions = timeline.transitions.filter { relation -> !((left != null && relation.outgoing == left.id) || (right != null && relation.incoming == right.id)) }
        val settings = value.copy(nextClipID = right?.id)
        val incoming = left?.let { item -> timeline.transitions.firstOrNull { it.incoming == item.id } }
        val outgoing = right?.let { item -> timeline.transitions.firstOrNull { it.outgoing == item.id } }
        val leftFree = maxOf(0.0, (left?.mapping?.duration?.seconds ?: 604800.0) - (incoming?.timing?.rightCost ?: 0.0))
        val rightFree = maxOf(0.0, (right?.mapping?.duration?.seconds ?: 604800.0) - (outgoing?.timing?.leftCost ?: 0.0))
        val edge = if (left == null) -1 else if (right == null) 1 else 0
        fun fits(factor: Double): Boolean { val t = TransitionTiming(settings, factor, edge); return t.leftCost <= leftFree + 0.00000001 && t.rightCost <= rightFree + 0.00000001 }
        var factor = 1.0
        if (!fits(1.0)) {
            var lo = 0.0; var hi = 1.0
            repeat(48) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
            factor = lo
        }
        val t = TransitionTiming(settings, factor, edge)
        if (t.videoDuration < TimelineEditing.frame - 0.000001) return result
        // Opening J-cuts may begin sound before the picture fade. Reserve
        // that lead-in at time zero instead of shifting the first take negative.
        val origin = left?.let { it.end - EditTime.seconds(t.leftEnd) } ?: maxOf(EditTime.zero, (right?.start ?: EditTime.zero) - EditTime.seconds(t.rightStart))
        if (right != null) {
            val delta = origin + EditTime.seconds(t.rightStart) - right.start
            shiftTail(timeline, shiftTargets(timeline, right, left), delta)
        }
        timeline.transitions = timeline.transitions + TimelineTransition(left?.id, right?.id, settings, origin, factor)
        timeline.placements = timeline.placements.filter { it.participants != setOfNotNull(left?.id, right?.id) }
        timeline.pruneRelations()
        timeline.synchronizeFollowers()
        return result
    }
    /// Remove a transition and close the overlap it made, as a straight cut.
    fun restoringStraightCut(state: EditState, index: Int): EditState {
        val join = joins(state.timeline).firstOrNull { it.index == index }
        val left = join?.let { j -> state.timeline.video.firstOrNull { it.id == j.outgoingID } }
        val right = join?.let { j -> state.timeline.video.firstOrNull { it.id == j.incomingID } }
        val result = setting(state, null, index)
        if (left == null || right == null) return result
        shiftTail(result.timeline, shiftTargets(result.timeline, right, left), left.end - right.start)
        result.timeline.pruneRelations()
        return result
    }
    private fun shiftTargets(timeline: TrackTimeline, right: VideoTimelineItem, left: VideoTimelineItem?): Set<UUID> {
        val linked = timeline.links.flatMap { it.members }.toSet()
        val pictures = timeline.video.filter { it.id != left?.id && (it.id == right.id || (it.id in linked && it.start >= right.start)) }.map { it.id }.toSet()
        val sounds = timeline.audio.filter { sound ->
            if (left != null && sound.followsVideo == left.id) return@filter false
            val follower = sound.followsVideo
            if (follower != null) follower in pictures else sound.id in linked && sound.start >= right.start
        }.map { it.id }
        return pictures + sounds
    }
    private fun shiftTail(timeline: TrackTimeline, moving: Set<UUID>, delta: EditTime) {
        timeline.video = timeline.video.map { if (it.id in moving) it.copy(start = it.start + delta) else it }
        timeline.audio = timeline.audio.map { if (it.id in moving) it.copy(start = it.start + delta) else it }
        timeline.transitions = timeline.transitions.map { relation ->
            val members = setOfNotNull(relation.outgoing, relation.incoming)
            if (moving.containsAll(members)) relation.copy(origin = relation.origin + delta) else relation
        }
    }
}
