package yourec

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.PI

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
    /// Every style, hold and sound timing of the iPhone app is drawn here.
    val unsupported: String? get() = null
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
    val videoRange: ClosedFloatingPointRange<Double> get() = origin..(origin + timing.videoDuration)
    val audioIn: ClosedFloatingPointRange<Double> get() = (origin + timing.audioIn.first)..(origin + timing.audioIn.second)
    val audioOut: ClosedFloatingPointRange<Double> get() = (origin + timing.audioOut.first)..(origin + timing.audioOut.second)
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

/// How each style mixes the outgoing picture A and the incoming picture B, in
/// output pixels with y up (as GL and Core Image count them). The frame shader
/// does the same arithmetic; this reference is what the checks run.
object TransitionMix {
    fun eased(t: Double) = t * t * (3 - 2 * t)

    /// The frames of the next scene a Flash Cut shows, accelerating towards the cut.
    fun flashFrames(settings: ClipTransition, duration: Double, framesPerSecond: Double = 30.0): List<Int> {
        val frames = maxOf(1, Math.round(duration * framesPerSecond).toInt())
        val count = minOf(settings.flashCount ?: 3, maxOf(0, (frames - 1) / 2))
        if (count <= 0) return emptyList()
        val result = ArrayList<Int>()
        for (index in 1..count) {
            val u = index.toDouble() / (count + 1)
            val desired = Math.round((1 - (1 - u).pow(settings.flashAcceleration ?: 2.0)) * (frames - 1)).toInt()
            result.add(minOf(frames - 2 - 2 * (count - index), maxOf((result.lastOrNull() ?: -1) + 2, desired)))
        }
        return result
    }
    fun flashFrame(t: Double, duration: Double, framesPerSecond: Double) = Math.floor(t * duration * framesPerSecond + 0.000001).toInt()

    /// Degrees clockwise on screen.
    fun angle(settings: ClipTransition) = (settings.wipeAngle ?: if (settings.axis == TransitionAxis.Horizontal) 0.0 else 90.0) + if (settings.reverse) 180.0 else 0.0
    class Wipe(val dx: Double, val dy: Double, val p0x: Double, val p0y: Double, val feather: Double)
    fun wipe(settings: ClipTransition, t: Double, width: Double, height: Double): Wipe {
        val radians = angle(settings) * PI / 180; val dx = Math.cos(radians); val dy = -Math.sin(radians)
        val span = abs(dx) * width + abs(dy) * height
        val feather = if (settings.hardEdge == true) 0.5 else maxOf(1.0, span * 0.035)
        val distance = (span + 2 * feather) * eased(t) - span / 2 - feather
        val cx = width / 2 + dx * distance; val cy = height / 2 + dy * distance
        return Wipe(dx, dy, cx - dx * feather, cy - dy * feather, feather)
    }
    /// 1 where the incoming picture shows.
    fun wipeMask(w: Wipe, x: Double, y: Double) = (1 - ((x - w.p0x) * w.dx + (y - w.p0y) * w.dy) / (2 * w.feather)).coerceIn(0.0, 1.0)

    /// Shadows (or highlights) of the outgoing picture give way first.
    class Key(val sign: Double, val feather: Double, val bias: Double)
    fun hitchcock(settings: ClipTransition, t: Double): Key {
        val feather = 0.08; val threshold = eased(t) * (1 + 2 * feather) - feather
        val highlights = settings.highlightsFirst == true
        return Key(if (highlights) 1.0 else -1.0, feather, (if (highlights) threshold - 1 else threshold) / feather + 0.5)
    }
    fun hitchcockMask(k: Key, rgb: FloatArray): Double {
        val luma = 0.2126 * rgb[0].coerceIn(0f, 1f) + 0.7152 * rgb[1].coerceIn(0f, 1f) + 0.0722 * rgb[2].coerceIn(0f, 1f)
        return (k.sign * luma / k.feather + k.bias).coerceIn(0.0, 1.0)
    }

    /// The two views of a split screen, y up: left and right, or top and bottom.
    fun splitRects(settings: ClipTransition, width: Double, height: Double): Pair<Rectangle, Rectangle> {
        if (settings.axis == TransitionAxis.Horizontal) {
            val split = width * settings.divider
            return Rectangle(0.0, 0.0, split, height) to Rectangle(split, 0.0, width - split, height)
        }
        val split = height * settings.divider
        return Rectangle(0.0, height - split, width, split) to Rectangle(0.0, 0.0, width, height - split)
    }
    /// The part of a picture a split view shows, as a rectangle of the picture with y up.
    fun splitCrop(framing: TransitionFraming, view: Rectangle, width: Double, height: Double): Rectangle {
        val top = CropSettings(x = framing.x, y = framing.y, zoom = framing.zoom).rect(CanvasSize(width, height), CanvasSize(view.width, view.height))
        return Rectangle(top.x, height - (top.y + top.height), top.width, top.height)
    }
    fun splitLine(settings: ClipTransition, width: Double, height: Double): Rectangle {
        val (first, _) = splitRects(settings, width, height)
        val line = maxOf(1.0, minOf(width, height) * 0.002)
        return if (settings.axis == TransitionAxis.Horizontal) Rectangle(first.x + first.width - line / 2, 0.0, line, height)
               else Rectangle(0.0, first.y - line / 2, width, line)
    }
    fun whipRadius(t: Double, width: Double, height: Double) = minOf(width, height) * 0.12 * Math.sin(PI * t)
    fun whipDistance(settings: ClipTransition, width: Double, height: Double) =
        (if (settings.axis == TransitionAxis.Vertical) height else width) * 0.65 * (if (settings.reverse) -1.0 else 1.0)
    fun defocusRadius(settings: ClipTransition, t: Double, width: Double, height: Double) = minOf(width, height) * 0.05 * (settings.blurAmount ?: 0.75) * Math.sin(PI * t)
    fun defocusBlend(t: Double) = ((t - 0.35) / 0.3).coerceIn(0.0, 1.0)

    private fun mix(a: FloatArray, b: FloatArray, f: Double) = FloatArray(3) { (a[it] + (b[it] - a[it]) * f).toFloat() }
    private fun inside(r: Rectangle, x: Double, y: Double) = x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height
    /// One output pixel at (x, y), y up. `a` and `b` give the two pictures' colours, without blur.
    fun pixel(settings: ClipTransition, t: Double, x: Double, y: Double, width: Double, height: Double, a: (Double, Double) -> FloatArray,
              b: (Double, Double) -> FloatArray, fill: TransitionColor = settings.resolvedFadeColor, duration: Double = settings.duration, framesPerSecond: Double = 30.0): FloatArray {
        val color = floatArrayOf(fill.red.toFloat(), fill.green.toFloat(), fill.blue.toFloat())
        if (t <= 0) return a(x, y); if (t >= 1) return b(x, y)
        return when (settings.style) {
            TransitionStyle.Dissolve, TransitionStyle.Still -> mix(a(x, y), b(x, y), eased(t))
            TransitionStyle.FadeColor -> if (t < 0.5) mix(a(x, y), color, minOf(1.0, t * 2)) else mix(color, b(x, y), maxOf(0.0, t * 2 - 1))
            TransitionStyle.Wipe -> mix(a(x, y), b(x, y), wipeMask(wipe(settings, t, width, height), x, y))
            TransitionStyle.Hitchcock -> { val first = a(x, y); mix(first, b(x, y), hitchcockMask(hitchcock(settings, t), first)) }
            TransitionStyle.FlashCut -> if (flashFrame(t, duration, framesPerSecond) in flashFrames(settings, duration, framesPerSecond)) b(x, y) else a(x, y)
            TransitionStyle.Whip -> {
                val e = eased(t); val d = whipDistance(settings, width, height); val vertical = settings.axis == TransitionAxis.Vertical
                fun shifted(f: (Double, Double) -> FloatArray, offset: Double) = if (vertical) f(x, (y + offset).coerceIn(0.0, height - 1)) else f((x - offset).coerceIn(0.0, width - 1), y)
                mix(shifted(a, -e * d), shifted(b, (1 - e) * d), e)
            }
            TransitionStyle.Defocus -> mix(a(x, y), b(x, y), defocusBlend(t))
            TransitionStyle.Split -> {
                val (left, right) = splitRects(settings, width, height)
                fun framed(f: (Double, Double) -> FloatArray, framing: TransitionFraming, view: Rectangle): FloatArray {
                    val crop = splitCrop(framing, view, width, height)
                    return f(crop.x + (x - view.x) * crop.width / view.width, crop.y + (y - view.y) * crop.height / view.height)
                }
                var pair = if (inside(left, x, y)) framed(a, settings.first, left) else if (inside(right, x, y)) framed(b, settings.second, right) else floatArrayOf(0f, 0f, 0f)
                if (inside(splitLine(settings, width, height), x, y)) pair = floatArrayOf(0.25f, 0.25f, 0.25f)
                val edge = 0.15
                if (t < edge) mix(a(x, y), pair, t / edge) else if (t > 1 - edge) mix(pair, b(x, y), (t - 1 + edge) / edge) else pair
            }
        }
    }
}
