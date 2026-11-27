package yourec

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/// The subtitles' outline over the preview, after the iPhone app's caption handle:
/// it fits the largest caption in the project. Drag inside it to move every
/// subtitle; pinch to resize them about the fingers. A touch elsewhere falls
/// through to the preview. `picture` is the preview's rectangle in this view.
class CaptionGuide(context: Context) : View(context) {
    /// The outline, normalised: left, top, width, height; null hides the guide.
    var envelope: DoubleArray? = null
        set(value) { field = value; visibility = if (value == null) GONE else VISIBLE; invalidate() }
    var picture = android.graphics.RectF()
        set(value) { field = value; invalidate() }
    /// A drag to a new centre, normalised.
    var onMove: ((Double, Double) -> Unit)? = null
    /// A pinch by a factor, from the fingers' old centre to their new one, normalised.
    var onResize: ((Double, Pair<Double, Double>, Pair<Double, Double>) -> Unit)? = null
    /// The end of a drag or a pinch: one change to keep.
    var onEnd: (() -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Design.accent; strokeWidth = Design.dp(context, 1.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(Design.dp(context, 6f).toFloat(), Design.dp(context, 4f).toFloat()), 0f)
    }
    private val slop = Design.dp(context, 16f).toFloat()
    private var dragging = false
    private var pinching = false
    private var grab: Pair<Double, Double>? = null
    private var focus: Pair<Double, Double>? = null

    init {
        visibility = GONE
        contentDescription = "Subtitle position. Drag to move all subtitles, pinch to resize them."
    }

    private fun normalised(x: Float, y: Float) = ((x - picture.left) / picture.width()).toDouble() to ((y - picture.top) / picture.height()).toDouble()
    private fun outline(): android.graphics.RectF? {
        val e = envelope ?: return null
        return android.graphics.RectF((picture.left + e[0] * picture.width()).toFloat(), (picture.top + e[1] * picture.height()).toFloat(),
            (picture.left + (e[0] + e[2]) * picture.width()).toFloat(), (picture.top + (e[1] + e[3]) * picture.height()).toFloat())
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinching = true; dragging = false; focus = normalised(detector.focusX, detector.focusY); return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val to = normalised(detector.focusX, detector.focusY)
            onResize?.invoke(detector.scaleFactor.toDouble(), focus ?: to, to); focus = to
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        outline()?.let { canvas.drawRoundRect(it, Design.dp(context, 6f).toFloat(), Design.dp(context, 6f).toFloat(), line) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = envelope ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val box = outline() ?: return false
            box.inset(-slop, -slop)
            // Outside the outline the preview keeps its own tap.
            if (!box.contains(event.x, event.y)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            val (x, y) = normalised(event.x, event.y)
            grab = (e[0] + e[2] / 2 - x) to (e[1] + e[3] / 2 - y)
            dragging = true; pinching = false
        }
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging && !pinching && event.pointerCount == 1) {
                val (x, y) = normalised(event.x, event.y); val (dx, dy) = grab ?: (0.0 to 0.0)
                onMove?.invoke(x + dx, y + dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; pinching = false; onEnd?.invoke() }
        }
        return true
    }
}
