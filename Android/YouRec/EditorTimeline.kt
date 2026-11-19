package yourec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.util.UUID
import kotlin.math.abs

/// Thumbnails and waveforms on one shared time scale, scroll position, selection
/// and playhead. Zoomed in, the playhead stays at the centre and footage moves
/// beneath it; when the whole project fits, both ends are visible and the
/// playhead moves instead. Drawing and hit testing use the same lanes.
class EditorTimeline(context: Context) : View(context) {
    /// What the editor shows. Assign, then call invalidate().
    var timeline = TrackTimeline()
    var sources: List<SourceMedia> = emptyList()
    var folder: File? = null
    var cache: File? = null
    var playhead = 0.0
    var selected: Set<UUID> = emptySet()
    var rangeStart: Double? = null
    var rangeEnd: Double? = null
    /// Items lifted by Move, drawn on their own rows and offset by the draft delta.
    var moving: TimelineSelection? = null
    var moveDelta = EditTime.zero
    var requestedScale = 60.0

    var onScrub: ((Double, Boolean) -> Unit)? = null       // time, gesture still active
    var onTap: ((UUID?) -> Unit)? = null                   // a part, or empty space
    var onLongPress: ((UUID) -> Unit)? = null
    var onTrim: ((UUID, Boolean, Double) -> Unit)? = null  // part, left edge, seconds
    var onMove: ((Double, Boolean) -> Unit)? = null        // seconds from the gesture start, ended

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val ruler = dp(22f); private val videoRow = dp(56f); private val audioRow = dp(40f); private val gap = dp(4f); private val handle = dp(18f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.secondary; textSize = dp(10f) }
    private val clip = Path(); private val card = RectF(); private val cell = Rect()
    private var lanes: List<TimelineLane> = emptyList()
    private var verticalOffset = 0f
    private val redraw = { invalidate() }

    val viewport: TimelineViewport get() = TimelineViewport(width / density.toDouble(), timeline.duration.seconds, requestedScale)
    private val offset: Double get() = viewport.let { if (it.fits) 0.0 else playhead * it.scale }
    private fun x(time: Double) = (viewport.x(time, offset) * density).toFloat()
    private fun time(x: Float) = viewport.time(x / density.toDouble(), offset)
    private fun videoRows() = (lanes.filter { !it.audio }.maxOfOrNull { it.row } ?: -1) + 1
    private fun top(lane: TimelineLane): Float {
        val pictures = maxOf(1, videoRows())
        return ruler + gap - verticalOffset + if (lane.audio) pictures * (videoRow + gap) + (lane.row - pictures) * (audioRow + gap) else lane.row * (videoRow + gap)
    }
    private fun rect(lane: TimelineLane): RectF {
        val shift = if (moving?.ids?.contains(lane.id) == true) moveDelta.seconds else 0.0
        val y = top(lane)
        return RectF(x(lane.interval.start.seconds + shift), y, x(lane.interval.end.seconds + shift), y + if (lane.audio) audioRow else videoRow)
    }
    private fun contentHeight(): Float {
        val pictures = maxOf(1, videoRows()); val sounds = maxOf(1, (lanes.maxOfOrNull { it.row } ?: 0) + 1 - pictures)
        return ruler + gap + pictures * (videoRow + gap) + sounds * (audioRow + gap)
    }

    override fun onDraw(canvas: Canvas) {
        lanes = timelineLanes(timeline, moving)
        verticalOffset = verticalOffset.coerceIn(0f, maxOf(0f, contentHeight() - height))
        canvas.drawColor(Design.background)
        drawRuler(canvas)
        links = TimelineLinkLayout(lanes, timeline.links)
        for (lane in lanes) drawLane(canvas, lane)
        drawBridges(canvas)
        val a = rangeStart; val b = rangeEnd
        if (a != null) {
            // A lone Start mark is a line; a complete range is the shaded span that Delete removes.
            paint.style = Paint.Style.FILL; paint.color = if (b != null) 0x55FF453A else Design.recording
            if (b != null) canvas.drawRect(x(minOf(a, b)), ruler, x(maxOf(a, b)), height.toFloat(), paint)
            else canvas.drawRect(x(a) - dp(1f), ruler, x(a) + dp(1f), height.toFloat(), paint)
        }
        paint.style = Paint.Style.FILL; paint.color = Design.accent
        val px = x(playhead)
        canvas.drawRect(px - dp(1f), 0f, px + dp(1f), height.toFloat(), paint)
        clip.rewind(); clip.moveTo(px - dp(5f), 0f); clip.lineTo(px + dp(5f), 0f); clip.lineTo(px, dp(7f)); clip.close()
        canvas.drawPath(clip, paint)
    }

    private fun drawRuler(canvas: Canvas) {
        val scale = viewport.scale
        // Choose the smallest step whose labels stay about 70 dp apart.
        val step = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0).firstOrNull { it * scale >= 70 } ?: 3600.0
        var t = Math.floor(time(0f) / step) * step
        val end = time(width.toFloat())
        paint.style = Paint.Style.FILL; paint.color = Design.separator
        while (t <= end + step) {
            if (t >= 0 && t <= timeline.duration.seconds + 0.0001) {
                canvas.drawRect(x(t), ruler - dp(6f), x(t) + dp(1f), ruler, paint)
                canvas.drawText(Design.clock(t), x(t) + dp(3f), ruler - dp(8f), textPaint)
            }
            t += step
        }
        paint.color = Design.positive
        for (mark in timeline.resolvedBookmarks) {
            val mx = x(mark.time.seconds)
            clip.rewind(); clip.moveTo(mx - dp(4f), ruler - dp(9f)); clip.lineTo(mx + dp(4f), ruler - dp(9f)); clip.lineTo(mx, ruler - dp(1f)); clip.close()
            canvas.drawPath(clip, paint)
        }
    }

    private var links = TimelineLinkLayout(emptyList(), emptyList())
    private val radii = FloatArray(8)
    private val outline = RectF()

    /// A linked group reads as one object: its cards lose the corners and
    /// borders where they meet, a seam marks each cut inside it, and picture
    /// and sound that cover the same time share a border.
    private fun drawLane(canvas: Canvas, lane: TimelineLane) {
        val r = rect(lane)
        if (r.right < 0 || r.left > width || r.bottom < ruler || r.top > height) return
        val edges = links.edges[lane.id] ?: TimelineLinkLayout.Edges()
        val isSelected = lane.id in selected
        // Corner radii in Path order: top-left, top-right, bottom-right, bottom-left.
        val round = dp(6f)
        val corners = booleanArrayOf(edges.previous == null && !edges.joinsAbove, edges.next == null && !edges.joinsAbove,
                                     edges.next == null && !edges.joinsBelow, edges.previous == null && !edges.joinsBelow)
        for (i in 0 until 4) { radii[i * 2] = if (corners[i]) round else 0f; radii[i * 2 + 1] = radii[i * 2] }
        canvas.save()
        clip.rewind(); clip.addRoundRect(r, radii, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL; paint.color = if (lane.audio) soundColor else Design.surface
        canvas.drawRect(r, paint)
        val file = sources.firstOrNull { it.id == lane.source }?.let { source -> folder?.let { File(it, source.filename) } }
        val shift = if (moving?.ids?.contains(lane.id) == true) moveDelta.seconds else 0.0
        if (file != null && file.exists()) {
            if (lane.audio) drawWaveform(canvas, lane, r, file, shift) else drawThumbnails(canvas, lane, r, file, shift)
        } else {
            canvas.drawText("Missing file", r.left + dp(6f), r.centerY(), textPaint)
        }
        canvas.restore()

        // An open side runs past the card and is clipped away, so no line is drawn across a join.
        fun stroke(leftOpen: Boolean, rightOpen: Boolean, vertical: Boolean, color: Int, width: Float) {
            outline.set(r); outline.inset(width / 2, width / 2)
            val past = dp(3f)
            if (leftOpen) outline.left -= past
            if (rightOpen) outline.right += past
            if (vertical && edges.joinsAbove) outline.top -= past
            if (vertical && edges.joinsBelow) outline.bottom += past
            canvas.save(); canvas.clipRect(r)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = color
            clip.rewind(); clip.addRoundRect(outline, radii, Path.Direction.CW)
            canvas.drawPath(clip, paint)
            canvas.restore()
        }
        // The quiet outline marks glue even without a selection; a lone card keeps its plain border.
        if (edges.outlinedGroup) stroke(edges.previous != null, edges.next != null, true, 0x85E6E6E6.toInt(), dp(1f))
        else if (!isSelected) stroke(false, false, false, Design.separator, dp(1f))
        if (isSelected) {
            // Picture and sound are outlined as one only when the whole group is selected.
            val group = timeline.links.firstOrNull { lane.id in it.members }?.members
            stroke(edges.previous?.let { it in selected } ?: false, edges.next?.let { it in selected } ?: false,
                   group != null && selected.containsAll(group), Design.accent, dp(2.5f))
        }
        if (edges.previous != null && r.left >= 0 && r.left <= width) {
            // The cut inside a group, kept readable over bright footage or a dense waveform.
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = 0xE6FFFFFF.toInt()
            clip.rewind(); clip.moveTo(r.left, r.top + dp(1f))
            if (minOf(lane.interval.duration.seconds, edges.previousDuration.seconds) * viewport.scale >= 14) {
                val middle = r.centerY()
                for ((dx, dy) in listOf(0f to -7f, -3f to -3f, 3f to 2f, 0f to 6f)) clip.lineTo(r.left + dp(dx), middle + dp(dy))
            }
            clip.lineTo(r.left, r.bottom - dp(1f))
            canvas.drawPath(clip, paint)
        }
        if (isSelected && moving == null && selected.size <= 2) {
            // Trim grips at both ends of the selected part.
            paint.style = Paint.Style.FILL; paint.color = Design.accent
            for (left in listOf(true, false)) {
                val gx = if (left) r.left else r.right - dp(5f)
                canvas.drawRoundRect(gx, r.top + dp(8f), gx + dp(5f), r.bottom - dp(8f), dp(2f), dp(2f), paint)
            }
        }
    }

    /// Where linked picture and sound cover the same time, close the gap between their rows.
    private fun drawBridges(canvas: Canvas) {
        for (bridge in links.bridges) {
            val groupLeft = x(bridge.interval.start.seconds) + dp(2f)
            val left = maxOf(0f, groupLeft); val right = minOf(width.toFloat(), x(bridge.interval.end.seconds) - dp(2f))
            if (right - left < dp(2f)) continue
            val y = ruler + gap - verticalOffset + bridge.upperRow * (videoRow + gap) + videoRow
            if (y + gap < ruler || y > height) continue
            paint.style = Paint.Style.FILL; paint.color = soundColor
            canvas.drawRect(left, y, right, y + gap, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = 0x85E6E6E6.toInt()
            val middle = y + gap / 2
            clip.rewind(); clip.moveTo(left, middle)
            if (groupLeft >= -dp(7f) && right - groupLeft >= dp(28f)) {
                // A little stitch connects picture and sound without covering the waveform.
                for ((dx, dy) in listOf(7f to 0f, 10f to -2f, 14f to 2f, 17f to 0f)) clip.lineTo(groupLeft + dp(dx), middle + dp(dy))
            }
            clip.lineTo(right, middle)
            canvas.drawPath(clip, paint)
        }
    }
    private val soundColor = 0xFF14323A.toInt()

    private fun drawThumbnails(canvas: Canvas, lane: TimelineLane, r: RectF, file: File, shift: Double) {
        val h = (r.height()).toInt()
        val w = h * 16f / 9
        // Cells are anchored to the part's own start, so they do not swim while scrolling.
        var cx = r.left + Math.floor(((maxOf(r.left, 0f) - r.left) / w).toDouble()).toFloat() * w
        while (cx < minOf(r.right, width.toFloat())) {
            val local = EditTime.seconds((time(cx + w / 2) - lane.interval.start.seconds - shift).coerceIn(0.0, lane.mapping.duration.seconds))
            val sourceTime = lane.mapping.source(local) ?: 0.0
            val image = SourceMediaCache.thumbnail(file, sourceTime, h, redraw)
            if (image != null) {
                // Centre-crop every frame into its cell, whatever the footage shape.
                val side = minOf(image.width.toFloat(), image.height * w / h).toInt()
                cell.set((image.width - side) / 2, 0, (image.width + side) / 2, image.height)
                card.set(cx, r.top, cx + w, r.bottom)
                canvas.drawBitmap(image, cell, card, null)
            }
            cx += w
        }
    }

    private fun drawWaveform(canvas: Canvas, lane: TimelineLane, r: RectF, file: File, shift: Double) {
        val peaks = SourceMediaCache.waveform(file, cache ?: file.parentFile, redraw)
        if (peaks == null) { canvas.drawText("Loading waveform…", maxOf(r.left, 0f) + dp(6f), r.centerY() + dp(4f), textPaint); return }
        val sound = timeline.audio.firstOrNull { it.id == lane.id }
        paint.style = Paint.Style.FILL; paint.color = if (sound?.muted == true) Design.secondary else 0xFF64D2FF.toInt()
        val step = dp(2f); val middle = r.centerY(); val half = r.height() / 2 - dp(3f)
        var px = maxOf(r.left, 0f)
        val startTime = lane.interval.start.seconds + shift
        while (px < minOf(r.right, width.toFloat())) {
            val a = lane.mapping.source(EditTime.seconds((time(px) - startTime).coerceAtLeast(0.0)))
            val b = lane.mapping.source(EditTime.seconds((time(px + step) - startTime).coerceAtLeast(0.0)))
            if (a != null && b != null) {
                // Show the level the listener will hear: volume and fades included, to a visible minimum.
                val gain = sound?.gain((time(px) - startTime).coerceAtLeast(0.0))?.toFloat() ?: 1f
                val level = maxOf(dp(0.5f), minOf(1f, peaks.level(minOf(a, b), maxOf(a, b)) * gain) * half)
                canvas.drawRect(px, middle - level, px + step - dp(0.5f), middle + level, paint)
            }
            px += step
        }
    }

    // Gestures. One finger scrubs, trims a selected edge, or moves a lifted group; two fingers zoom.
    private enum class Drag { None, Scrub, Trim, Move, Vertical }
    private var drag = Drag.None
    private var downX = 0f; private var downY = 0f; private var downPlayhead = 0.0; private var downScale = 60.0; private var downOffset = 0f
    private var trimTarget: Pair<UUID, Boolean>? = null
    private var multi = false
    private val slop = dp(8f)

    private fun hit(x: Float, y: Float): TimelineLane? = lanes.lastOrNull { rect(it).contains(x, y) }
    private fun trimHit(x: Float, y: Float): Pair<UUID, Boolean>? {
        if (moving != null || selected.size > 2) return null
        for (lane in lanes) {
            if (lane.id !in selected) continue
            val r = rect(lane)
            if (y < r.top || y > r.bottom) continue
            // A short part still offers both edges: split the width between them.
            val reach = minOf(handle, r.width() / 2)
            if (x >= r.left - handle / 2 && x <= r.left + reach) return lane.id to true
            if (x <= r.right + handle / 2 && x >= r.right - reach) return lane.id to false
        }
        return null
    }

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (moving != null) return true
            val lane = hit(e.x, e.y)
            if (lane == null && e.y > ruler) onTap?.invoke(null)
            else if (lane != null) onTap?.invoke(lane.id)
            else onScrub?.invoke(time(e.x), false)   // a tap on the ruler moves the playhead there
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (drag != Drag.None || moving != null) return
            hit(e.x, e.y)?.let { Design.tick(this@EditorTimeline); onLongPress?.invoke(it.id) }
        }
    })
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { downScale = viewport.scale; drag = Drag.None; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Limits come from the viewport: never below whole-project fit.
            requestedScale = TimelineViewport(width / density.toDouble(), timeline.duration.seconds, downScale * detector.scaleFactor).scale
            downScale = requestedScale
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        taps.onTouchEvent(event)
        // Once a second finger has touched, the rest of this gesture is a zoom:
        // the finger that stays down must not scrub by the distance it pinched.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multi = false
        if (pinch.isInProgress || event.pointerCount > 1) multi = true
        if (multi) { if (drag == Drag.Scrub) onScrub?.invoke(playhead, false); drag = Drag.None; trimPreview = null; return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downPlayhead = playhead; downOffset = verticalOffset
                trimTarget = trimHit(event.x, event.y); drag = Drag.None
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (drag == Drag.None && (abs(dx) > slop || abs(dy) > slop)) {
                    drag = if (moving != null) Drag.Move else if (trimTarget != null) Drag.Trim
                           else if (abs(dy) > abs(dx) && contentHeight() > height) Drag.Vertical else Drag.Scrub
                }
                val seconds = dx / density / viewport.scale
                when (drag) {
                    Drag.Scrub -> onScrub?.invoke(if (viewport.fits) time(event.x) else (downPlayhead - seconds).coerceIn(0.0, timeline.duration.seconds), true)
                    Drag.Move -> onMove?.invoke(seconds, false)
                    Drag.Vertical -> { verticalOffset = downOffset - dy; invalidate() }
                    Drag.Trim -> { trimPreview = event.x; invalidate() }
                    Drag.None -> { }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val seconds = (event.x - downX) / density / viewport.scale
                val ended = event.actionMasked == MotionEvent.ACTION_UP
                when (drag) {
                    Drag.Scrub -> onScrub?.invoke(playhead, false)
                    // Fast short drags can go from down straight to up: always apply the final distance.
                    Drag.Trim -> { trimPreview = null; val target = trimTarget; if (ended && target != null) onTrim?.invoke(target.first, target.second, seconds) }
                    Drag.Move -> onMove?.invoke(if (ended) seconds else 0.0, true)
                    else -> { }
                }
                drag = Drag.None; trimTarget = null; invalidate()
            }
        }
        return true
    }
    private var trimPreview: Float? = null
    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        val px = trimPreview ?: return
        // The edge being dragged, before the trim is committed on release.
        paint.style = Paint.Style.FILL; paint.color = Design.text
        canvas.drawRect(px - dp(1f), ruler, px + dp(1f), height.toFloat(), paint)
    }
}
