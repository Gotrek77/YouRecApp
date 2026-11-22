package yourec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.UUID
import kotlin.math.abs

/// The recorder: the camera picture over the whole display and a few controls
/// on it. Nothing frames the footage; secondary choices open in a panel from
/// the bottom edge and close again. The view owns no capture logic: it shows
/// CaptureController's state and forwards gestures to it.
class RecorderView(context: Context, private val store: ProjectStore, private val navigation: Navigation) : FrameLayout(context), Screen {
    /// The project new takes are appended to. Without one, the first Record creates it.
    var project: UUID? = null
        set(value) { field = value; refresh() }

    private val capture = CaptureController(context)
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var asked = false
    private var takeProject: UUID? = null
    private var takeBase = 0.0          // the project's captured duration when the take began
    private var indexing = 0            // finished movies not yet in their project
    private var shownCamera: String? = null
    private var shownTurn = 0
    private var ticks = 0

    private fun dp(value: Float) = Design.dp(context, value)
    private fun plate() = Design.rounded(Design.scrim, dp(24f).toFloat())

    private val preview = SurfaceView(context)
    private val touch = View(context)
    private val overlay = FrameLayout(context)
    private val focusBox = View(context).apply { background = Design.rounded(0, dp(4f).toFloat(), dp(2f), Design.accent); visibility = GONE }

    private val projects = Design.symbol(context, "☰", "Projects") { navigation.showProjects() }
    private val edit = Design.button(context, "Edit", fill = Design.scrim) { project?.let { navigation.showEditor(it) } }.apply { contentDescription = "Edit this project" }
    private val settings = Design.symbol(context, "⚙", "Camera settings") { openSettings() }
    private val status = Design.label(context, "0:00", 15f, bold = true, mono = true).apply {
        gravity = Gravity.CENTER; background = plate(); minHeight = dp(40f); setPadding(dp(14f), 0, dp(14f), 0)
    }

    private val meter = LevelMeter(context)
    private val microphoneName = Design.label(context, "", 12f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(150f) }
    private val clip = Design.label(context, "CLIP", 10f, Design.recording, bold = true).apply { visibility = INVISIBLE }
    private val microphone = Design.row(context, Design.label(context, "MIC", 10f, Design.secondary, bold = true), microphoneName, meter, clip, spacing = 6f).apply {
        background = plate(); minimumHeight = dp(Design.touch.toFloat()); setPadding(dp(12f), 0, dp(12f), 0)
        isClickable = true; isFocusable = true
        setOnClickListener { Design.tap(it); openMicrophones() }
    }
    private val banners = Design.column(context, spacing = 6f)

    private val hint = Design.label(context, "", 13f, bold = true).apply { background = plate(); setPadding(dp(12f), dp(7f), dp(12f), dp(7f)); visibility = INVISIBLE }
    private val duration = Design.label(context, "", 13f, bold = true, mono = true).apply {
        gravity = Gravity.CENTER; minWidth = dp(56f); minHeight = dp(Design.touch.toFloat()); isFocusable = true
    }
    private val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    // More presets than fit a narrow phone scroll sideways instead of shrinking below a fingertip.
    private val presetScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(presets) }
    private val presetBar = Design.row(context, duration, divider(), presetScroll, spacing = 4f).apply { background = plate(); setPadding(dp(6f), 0, dp(6f), 0) }

    private val flip = Design.symbol(context, "⇄", "Switch camera", fill = 0) { capture.flipCamera() }
    private val cameraName = Design.button(context, "Camera", fill = 0, size = 14f) { openCameras() }
    private val zoomReadout = Design.label(context, "1×", 14f, bold = true, mono = true).apply { gravity = Gravity.CENTER; minWidth = dp(52f) }
    private val focus = Design.button(context, "AF", fill = 0, size = 14f) { focusTapped() }.apply {
        // As on iPhone: hold and release to toggle focus peaking.
        setOnLongClickListener { capture.focusPeaking = !capture.focusPeaking; say(if (capture.focusPeaking) "Focus peaking on" else "Focus peaking off"); true }
    }
    private val cameraBar = Design.row(context, flip, cameraName, divider(), zoomReadout, divider(), focus, spacing = 2f).apply { background = plate(); setPadding(dp(6f), 0, dp(6f), 0) }

    private val record = RecordButton(context).apply { setOnClickListener { Design.tap(it); recordTapped() } }
    private val pause = Design.symbol(context, "❚❚", "Pause recording", 56f) {
        if (capture.state == CaptureController.State.Recording) capture.pause() else capture.finish()
    }
    private val bookmark = Design.symbol(context, "◆", "Add recording bookmark", 56f) { capture.bookmark(); say("Bookmark added") }
    private val denied = Design.column(context,
        Design.label(context, "Allow Camera and Microphone access to record.", 16f, bold = true).apply { gravity = Gravity.CENTER },
        Design.button(context, "Allow access", Design.background, Design.accent) { request() }, spacing = 14f, gravity = Gravity.CENTER_HORIZONTAL).apply { visibility = GONE }

    private val frontLight = RecorderFrontLight(context)
    private val bulb = Design.symbol(context, "💡", "Adjust front light", 44f) { openFrontLight() }.apply { visibility = GONE }
    private val panelHost = FrameLayout(context).apply { visibility = GONE; setBackgroundColor(0x66000000); setOnClickListener { closePanel() }; contentDescription = "Close panel" }

    init {
        addView(preview, Design.frame())
        addView(touch, Design.frame())
        addView(focusBox, Design.frame(dp(72f), dp(72f)))
        addView(frontLight, Design.frame())
        addView(overlay, Design.frame())
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(projects); top.addView(edit, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(8f) })
        top.addView(Design.spacer(context), Design.weighted()); top.addView(settings)
        overlay.addView(top, Design.frame(height = wrap, gravity = Gravity.TOP).apply { setMargins(dp(12f), dp(8f), dp(12f), 0) })
        // Centred on the display whatever stands at its sides.
        overlay.addView(status, Design.frame(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12f) })
        overlay.addView(microphone, Design.frame(wrap, wrap, Gravity.TOP or Gravity.START).apply { setMargins(dp(12f), dp(64f), 0, 0) })
        overlay.addView(banners, Design.frame(height = wrap, gravity = Gravity.TOP).apply { setMargins(dp(12f), dp(120f), dp(12f), 0) })
        overlay.addView(denied, Design.frame(wrap, wrap, Gravity.CENTER).apply { setMargins(dp(32f), 0, dp(32f), 0) })

        val shutter = FrameLayout(context)
        shutter.addView(record, Design.frame(dp(84f), dp(84f), Gravity.CENTER))
        shutter.addView(pause, Design.frame(dp(56f), dp(56f), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(40f) })
        shutter.addView(bookmark, Design.frame(dp(56f), dp(56f), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(40f) })
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        bottom.addView(hint, LinearLayout.LayoutParams(wrap, wrap))
        bottom.addView(presetBar, LinearLayout.LayoutParams(wrap, wrap).apply { setMargins(dp(12f), dp(8f), dp(12f), 0) })
        bottom.addView(cameraBar, LinearLayout.LayoutParams(wrap, wrap).apply { setMargins(dp(12f), dp(8f), dp(12f), 0) })
        bottom.addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96f)).apply { topMargin = dp(10f) })
        overlay.addView(bottom, Design.frame(height = wrap, gravity = Gravity.BOTTOM).apply { bottomMargin = dp(12f) })
        overlay.addView(bulb, Design.frame(dp(44f), dp(44f), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14f), dp(14f)) })
        overlay.addView(panelHost, Design.frame())

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                capture.displayTurn = (display?.rotation ?: 0) * 90
                capture.setPreview(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = capture.clearPreview()
        })
        setUpGestures()
        capture.onChange = { refresh() }
        capture.onSegment = { file ->
            val id = takeProject
            if (id != null) {
                indexing++
                store.addRecording(file, id) { ok ->
                    indexing--
                    if (!ok) capture.warning = "The take was recorded but could not be added to the project. Its file has been preserved."
                    refresh()
                }
            }
        }
        capture.audio.onLevel = { level, hold, clipped ->
            meter.show(level, hold, clipped)
            clip.visibility = if (clipped) VISIBLE else INVISIBLE
        }
        capture.audio.onRoute = { refresh() }
        capture.audio.onWarning = { message, event ->
            // A lost microphone stays on screen until acknowledged, and in the take as a mark.
            if (event) capture.mark("microphoneFallback", message)
            capture.warning = message
        }
        refresh()
    }

    /// The activity pads screens clear of the system bars. Here only the
    /// controls move: the picture keeps the whole display.
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_CAST") (overlay as FrameLayout?)?.setPadding(left, top, right, bottom)
    }

    // ================================================================ screen

    override fun appear() {
        visible = true
        frontLight.visible(true)
        store.observe(this) { refresh() }
        main.removeCallbacks(tick); main.post(tick)
        if (granted()) begin()
        else if (!asked) { asked = true; request() }
        else denied.visibility = VISIBLE
    }
    /// A take in progress is finalized before the camera is released. Coming
    /// back shows the recorder idle: recording again takes a new tap.
    override fun disappear() {
        visible = false
        frontLight.visible(false)
        main.removeCallbacks(tick)
        closePanel()
        capture.stop()
        store.stopObserving(this)
        keepScreenOn = false
    }
    override fun back(): Boolean {
        if (panelHost.visibility != VISIBLE) return false
        closePanel(); return true
    }
    private fun granted() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    // The system's question pauses the activity, so the answer may arrive while
    // this screen is not in front; appear() looks at the permission again.
    private fun request() { navigation.requestCapturePermissions { ok -> if (ok && visible) begin() else if (!ok) denied.visibility = VISIBLE } }
    private fun begin() { denied.visibility = GONE; capture.start(); refresh() }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            showTime()
            if (++ticks % 4 == 0) { capture.checkStorage(); showMicrophone() }
            main.postDelayed(this, 250)
        }
    }
    /// Silence is a level; a missing or stalled input is said in words.
    private fun showMicrophone() {
        val audio = capture.audio
        val name = if (!audio.running) "No microphone" else if (audio.stalled) "No signal" else if (audio.silenced) "In use by another app" else audio.activeName
        microphoneName.show(name)
        microphoneName.setTextColor(if (name == audio.activeName && audio.running) Design.text else Design.warning)
        microphone.contentDescription = "Microphone: $name"
        if (!audio.running || audio.stalled) { meter.show(CaptureAudio.floor, CaptureAudio.floor, false); clip.visibility = INVISIBLE }
    }

    // ================================================================ state

    private fun current(): Project? = project?.let { id -> store.projects.firstOrNull { it.id == id } }

    private fun showTime() {
        val recording = capture.state == CaptureController.State.Recording
        val total = if (capture.hasTake) takeBase + capture.elapsed else current()?.capturedDuration ?: 0.0
        // Words and a shape, not only red: the state must read without colour.
        status.show(when (capture.state) {
            CaptureController.State.Recording -> "● REC  " + Design.clock(total)
            CaptureController.State.Paused -> "❚❚ PAUSED  " + Design.clock(total)
            CaptureController.State.Saving -> "Saving…"
            else -> Design.clock(total)
        })
        status.setTextColor(if (recording) Design.recording else if (capture.state == CaptureController.State.Paused) Design.accent else Design.text)
        status.contentDescription = when (capture.state) {
            CaptureController.State.Recording -> "Recording, " + Design.clock(total)
            CaptureController.State.Paused -> "Paused, " + Design.clock(total)
            CaptureController.State.Saving -> "Saving"
            else -> if (total > 0) "Ready to continue recording, " + Design.clock(total) + " captured" else "Ready to record"
        }
    }

    // The clock runs four times a second: an unchanged text must not relayout the
    // screen or tell accessibility services (TalkBack, UI tests) that it changed.
    private fun TextView.show(value: String) { if (text.toString() != value) text = value }

    private fun enable(view: View, on: Boolean) { view.isEnabled = on; view.alpha = if (on) 1f else 0.4f }

    /// Bring every control in line with the controller. Called often, so it only sets values.
    private fun refresh() {
        val state = capture.state
        val camera = capture.camera
        val usable = capture.ready && !capture.switching
        keepScreenOn = capture.hasTake
        showTime()

        enable(projects, !capture.hasTake)
        edit.visibility = if (state == CaptureController.State.Idle && indexing == 0 && current()?.sources?.isNotEmpty() == true) VISIBLE else GONE
        enable(settings, true)

        record.state = state
        enable(record, state == CaptureController.State.Recording || (usable && state != CaptureController.State.Saving))
        record.contentDescription = when (state) {
            CaptureController.State.Recording -> "Stop recording"
            CaptureController.State.Paused -> "Resume recording"
            CaptureController.State.Saving -> "Saving recording"
            else -> if (current() != null) "Record into this project" else "Record a new project"
        }
        pause.visibility = if (state == CaptureController.State.Recording || state == CaptureController.State.Paused) VISIBLE else INVISIBLE
        pause.show(if (state == CaptureController.State.Paused) "■" else "❚❚")
        pause.contentDescription = if (state == CaptureController.State.Paused) "Finish paused recording" else "Pause recording"
        bookmark.visibility = if (state == CaptureController.State.Recording) VISIBLE else INVISIBLE

        val opposite = capture.oppositeCamera
        // Choosing a camera stays possible with none running: it is the way back after one is lost.
        enable(flip, !capture.switching && opposite != null && state != CaptureController.State.Saving)
        flip.contentDescription = if (opposite == null) "Switch camera" else "Switch to " + (if (opposite.front) "front" else "back") + " camera, " + opposite.name
        enable(cameraName, !capture.switching && state != CaptureController.State.Saving)
        cameraName.show(if (capture.switching) "Switching…" else (camera?.name ?: "Camera") + " ▾")
        cameraName.contentDescription = "Camera, " + (camera?.name ?: "unavailable")

        if (camera?.id != shownCamera) { shownCamera = camera?.id; buildPresets(camera) }
        enable(presets, usable)
        val shown = capture.displayZoom
        for (i in 0 until presets.childCount) {
            val button = presets.getChildAt(i) as TextView
            val selected = abs((button.tag as Double) - shown) < 0.04
            button.setTextColor(if (selected) Design.accent else Design.text); button.isSelected = selected
        }
        zoomReadout.show(CaptureController.magnification(shown))
        zoomReadout.contentDescription = "Zoom, " + CaptureController.magnification(shown).replace("×", " times")
        showDuration()

        enable(focus, usable && camera?.fixedFocus == false)
        focus.show(if (camera?.fixedFocus != false) "FIXED" else if (capture.focusLocked) "AF LOCK" else "AF")
        focus.setTextColor(if (capture.focusLocked) Design.accent else Design.text)
        focus.contentDescription = if (camera?.fixedFocus != false) "Fixed focus" else if (capture.focusLocked) (if (camera.manualFocus) "Manual focus" else "Focus locked") else "Autofocus"

        showMicrophone()
        showBanners()
        // Glyphs turn with the device; the layout stays where the thumbs are.
        if (capture.turn != shownTurn) {
            shownTurn = capture.turn
            val angle = if (shownTurn == 270) 90f else -shownTurn.toFloat()
            for (view in listOf(projects, settings, flip, pause, bookmark, zoomReadout)) view.animate().rotation(angle).setDuration(200).start()
        }
    }

    private var shownError: String? = null
    private var shownWarning: String? = null
    private fun showBanners() {
        if (capture.error == shownError && capture.warning == shownWarning) return
        shownError = capture.error; shownWarning = capture.warning
        banners.removeAllViews()
        fun banner(message: String, color: Int, dismiss: () -> Unit) {
            val text = Design.label(context, "⚠  $message", 13f, color, bold = true)
            val close = Design.symbol(context, "✕", "Dismiss warning", fill = 0) { dismiss() }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Design.rounded(0xCC000000.toInt(), dp(14f).toFloat()); setPadding(dp(12f), dp(4f), 0, dp(4f)) }
            row.addView(text, Design.weighted()); row.addView(close)
            banners.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (banners.childCount > 0) topMargin = dp(6f) })
            row.announceForAccessibility(message)
        }
        shownError?.let { banner(it, Design.text) { capture.error = null } }
        shownWarning?.let { banner(it, Design.accent) { capture.warning = null } }
    }

    private val hideHint = Runnable { hint.visibility = INVISIBLE }
    private fun say(message: String) {
        hint.text = message; hint.visibility = VISIBLE
        hint.announceForAccessibility(message)
        main.removeCallbacks(hideHint); main.postDelayed(hideHint, 2000)
    }
    private fun divider() = Fixed(context, dp(1f), dp(18f)).apply { setBackgroundColor(0x33FFFFFF) }

    // ================================================================ recording

    private fun recordTapped() {
        when (capture.state) {
            CaptureController.State.Recording -> capture.finish()
            CaptureController.State.Paused -> capture.resume()
            CaptureController.State.Saving -> { }
            CaptureController.State.Idle -> {
                if (!capture.ready || capture.switching) return
                val target = current() ?: store.create() ?: run { capture.error = store.error ?: "A new project could not be created."; return }
                project = target.id; takeProject = target.id; takeBase = target.capturedDuration
                capture.record(store.folder(target))
            }
        }
    }

    // ================================================================ zoom

    private fun buildPresets(camera: CaptureController.Camera?) {
        presets.removeAllViews()
        for (value in camera?.presets ?: emptyList()) {
            val button = Design.label(context, CaptureController.magnification(value), 14f, bold = true).apply {
                gravity = Gravity.CENTER; tag = value; isFocusable = true
                contentDescription = CaptureController.magnification(value).replace("×", " times zoom")
            }
            // Wait for the double-tap window before acting: with an instant
            // transition the first tap of a double tap would otherwise jump.
            val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean { Design.tick(button); button.alpha = 0.6f; return true }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { capture.setZoom(value, capture.zoomDuration); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { capture.setZoom(value, 1.0); return true }
            })
            button.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) button.alpha = 1f
                taps.onTouchEvent(event)
            }
            // TalkBack activates through a click; the quick ramp is a named action.
            button.setOnClickListener { capture.setZoom(value, capture.zoomDuration) }
            button.accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(quickAction, "One-second transition"))
                }
                override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                    if (action != quickAction) return super.performAccessibilityAction(host, action, arguments)
                    capture.setZoom(value, 1.0); return true
                }
            }
            presets.addView(button, LinearLayout.LayoutParams(dp(Design.touch.toFloat()), dp(Design.touch.toFloat())))
        }
    }

    private fun showDuration() {
        val seconds = capture.zoomDuration
        duration.text = "↕ " + ZoomTransition.number(seconds) + "s"
        duration.contentDescription = "Zoom transition duration, " + ZoomTransition.durationLabel(seconds)
    }
    private fun adjustDuration(seconds: Double) {
        val bounded = ZoomTransition.boundedDuration(seconds)
        if (bounded == capture.zoomDuration) return
        capture.zoomDuration = bounded
        Design.tick(duration); showDuration()
        say("Zoom time: " + ZoomTransition.durationLabel(bounded))
    }

    private fun setUpGestures() {
        // Swipe the duration up or down in half-second steps; a tap opens the presets.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startY = 0f; var base = 0.0; var dragging = false
        duration.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; base = capture.zoomDuration; dragging = false; view.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val travelled = startY - event.y
                    if (abs(travelled) > slop) { dragging = true; duration.setTextColor(Design.accent) }
                    if (dragging) adjustDuration(base + (travelled / dp(22f)).toInt() * 0.5)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    duration.setTextColor(Design.text)
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) { Design.tap(view); openDurations() }
                }
            }
            true
        }
        duration.setOnClickListener { openDurations() }
        duration.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { adjustDuration(capture.zoomDuration + 0.5); true }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { adjustDuration(capture.zoomDuration - 0.5); true }
                else -> super.performAccessibilityAction(host, action, arguments)
            }
        }

        // On the picture: a tap asks for focus there, a pinch zooms.
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { capture.scaleZoom(detector.scaleFactor.toDouble()); return true }
        })
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (capture.focusLocked) { say("Focus is locked"); return true }
                if (!capture.focusAt(e.x, e.y)) return true
                focusBox.translationX = e.x - focusBox.width / 2f; focusBox.translationY = e.y - focusBox.height / 2f
                focusBox.visibility = VISIBLE; focusBox.alpha = 1f
                focusBox.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
                return true
            }
        })
        touch.setOnTouchListener { _, event -> pinch.onTouchEvent(event); if (!pinch.isInProgress) taps.onTouchEvent(event); true }
        touch.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ================================================================ focus

    private fun focusTapped() {
        val camera = capture.camera ?: return
        if (!capture.focusLocked) {
            // Start a manual lock from where autofocus left the lens.
            capture.setFocusLocked(true)
            say("Focus locked")
            if (!camera.manualFocus) return
        }
        openFocus(camera)
    }
    private fun openFocus(camera: CaptureController.Camera) {
        val rows = ArrayList<View>()
        if (camera.manualFocus) {
            val value = Design.label(context, "", 15f, mono = true)
            fun describe(position: Double) { value.text = "Focus " + Math.round(position * 100) + "%" }
            val start = capture.focusPosition ?: capture.reportedFocus ?: 0.5
            describe(start)
            // A lens position, not a distance: the same number means different metres on different phones.
            val slider = SeekBar(context).apply { max = 1000; progress = (start * 1000).toInt(); contentDescription = "Manual lens position" }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) { if (user) { capture.setFocusPosition(progress / 1000.0); describe(progress / 1000.0) } }
                override fun onStartTrackingTouch(bar: SeekBar) { }
                override fun onStopTrackingTouch(bar: SeekBar) { }
            })
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(Design.label(context, "Near", 13f, Design.secondary))
            line.addView(slider, Design.weighted(height = dp(Design.touch.toFloat())))
            line.addView(Design.label(context, "Far", 13f, Design.secondary))
            rows.add(value); rows.add(line)
        } else rows.add(note("This lens holds its position while locked. It cannot be set by hand."))
        rows.add(Design.button(context, "Return to autofocus") { capture.setFocusLocked(false); closePanel(); say("Autofocus") })
        showPanel("Focus", rows)
    }

    // ================================================================ panels

    private fun note(text: String) = Design.label(context, text, 13f, Design.secondary)
    private fun choice(title: String, selected: Boolean, enabled: Boolean = true, action: () -> Unit) =
        Design.button(context, (if (selected) "✓  " else "") + title, if (selected) Design.accent else Design.text) { action() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            contentDescription = title + if (selected) ", selected" else ""
            isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
        }

    private fun showPanel(title: String, rows: List<View>) {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(14f), dp(16f), dp(16f)) }
        list.addView(Design.label(context, title, 17f, bold = true))
        for (row in rows) list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        val sheet = ScrollView(context).apply {
            background = Design.rounded(Design.surface, dp(16f).toFloat()); clipToOutline = true
            isClickable = true   // taps on the sheet itself must not fall through and close it
            addView(list)
        }
        panelHost.removeAllViews()
        panelHost.addView(sheet, Design.frame(height = ViewGroup.LayoutParams.WRAP_CONTENT, gravity = Gravity.BOTTOM).apply { setMargins(dp(8f), dp(96f), dp(8f), dp(8f)) })
        panelHost.visibility = VISIBLE
    }
    private fun closePanel() { panelHost.removeAllViews(); panelHost.visibility = GONE }

    private fun openCameras() {
        showPanel("Camera", capture.cameras.map { camera ->
            choice(camera.name, camera.id == capture.camera?.id) { closePanel(); capture.switchCamera(camera.id) }
        } + note("Each camera remembers its own zoom and focus."))
    }

    private fun openMicrophones() {
        val audio = capture.audio
        val rows = ArrayList<View>()
        // The tick follows the input that is really recording, not the last request.
        for (input in audio.inputs) rows.add(choice(input.name, input.id == audio.active?.id) {
            closePanel(); audio.select(input.id)
        })
        if (audio.inputs.isEmpty()) rows.add(note("No microphone is available."))
        rows.add(Design.button(context, "Let Android choose") { closePanel(); audio.select(null) })
        rows.add(note("A selection lasts until Stop. During a take the microphone does not change by itself; if it disconnects, another is used and the moment is marked."))
        showPanel("Microphone", rows)
    }

    private fun openDurations() {
        showPanel("Zoom transition", ZoomTransition.durationPresets.map { seconds ->
            choice(ZoomTransition.durationLabel(seconds), seconds == capture.zoomDuration) { capture.zoomDuration = seconds; showDuration(); closePanel() }
        } + note("Swipe the time beside the zoom presets up or down to adjust by half a second. Double-tap a zoom preset to reach it in one second."))
    }

    private fun openSettings() {
        val rows = ArrayList<View>()
        fun toggle(title: String, value: Boolean, change: (Boolean) -> Unit) = Design.button(context, title + (if (value) "  ·  On" else "  ·  Off"), if (value) Design.accent else Design.text) {
            change(!value); openSettings()
        }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; contentDescription = title + if (value) ", on" else ", off" }
        rows.add(Design.label(context, "Quality", 13f, Design.secondary, bold = true))
        for (quality in CaptureController.Quality.values()) {
            val supported = quality == CaptureController.Quality.HD || capture.camera?.supports4K != false
            rows.add(choice(quality.title + if (supported) "" else "  ·  not on this camera", quality == capture.quality, !capture.hasTake && supported && !capture.switching) { capture.quality = quality; openSettings() })
        }
        rows.add(note("4K uses more storage and power. Quality stays fixed until Stop."))
        rows.add(toggle("Mirror front preview", capture.previewMirrored) { capture.previewMirrored = it })
        rows.add(toggle("Save mirrored selfies", capture.saveMirrored) { capture.saveMirrored = it })
        rows.add(note("The front-camera preview behaves like a mirror by default. Saved video keeps writing readable unless Save mirrored selfies is on."))
        rows.add(toggle("Fill the screen", capture.fill) { capture.fill = it })
        rows.add(note("The full-screen view fills the display. Off shows the complete recorded frame."))
        rows.add(Design.button(context, "Zoom transition  ·  " + ZoomTransition.durationLabel(capture.zoomDuration)) { openDurations() }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })
        rows.add(toggle("Focus peaking", capture.focusPeaking) { capture.focusPeaking = it })
        rows.add(note("Sharp edges show red in the preview, never in the recording. Holding AF turns it on or off too."))
        rows.add(toggle("Front light", frontLight.on) { setFrontLight(it) })
        rows.add(note("A lit border around the picture lights your face from the screen, at full brightness. It is not recorded."))
        showPanel("Camera settings", rows)
    }

    private fun setFrontLight(on: Boolean) { frontLight.on = on; bulb.visibility = if (on) VISIBLE else GONE }
    private fun openFrontLight() {
        val rows = ArrayList<View>()
        rows.add(Design.label(context, "Border width", 13f, Design.secondary, bold = true))
        rows.add(Design.row(context, *RecorderFrontLight.Width.values().map { w -> choice(w.title, w == frontLight.border) { frontLight.border = w; openFrontLight() } }.toTypedArray()))
        rows.add(Design.label(context, "Color", 13f, Design.secondary, bold = true))
        rows.add(Design.row(context, *RecorderFrontLight.Tone.values().map { t -> choice(t.title, t == frontLight.tone) { frontLight.tone = t; openFrontLight() } }.toTypedArray()))
        fun slider(title: String, max: Int, value: Int, change: (Int) -> Unit) = android.widget.SeekBar(context).apply {
            this.max = max; progress = value; contentDescription = title
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar, p: Int, user: Boolean) { if (user) change(p) }
                override fun onStartTrackingTouch(b: android.widget.SeekBar) { }
                override fun onStopTrackingTouch(b: android.widget.SeekBar) { }
            })
        }
        if (frontLight.tone == RecorderFrontLight.Tone.Custom) {
            rows.add(Design.label(context, "Hue", 13f, Design.secondary, bold = true))
            rows.add(slider("Front light hue", 360, frontLight.customHue.toInt()) { frontLight.customHue = it.toFloat() })
        }
        rows.add(Design.label(context, "Opacity", 13f, Design.secondary, bold = true))
        rows.add(slider("Front light opacity", 20, Math.round(frontLight.opacity * 20)) { frontLight.opacity = it / 20f })
        rows.add(Design.button(context, "Turn off front light") { setFrontLight(false); closePanel() })
        showPanel("Front light", rows)
    }

    companion object {
        private val quickAction = View.generateViewId()
    }

    /// The shutter: a ring with a disc to record, a square to stop, a triangle
    /// to resume. The shape carries the state; colour only repeats it.
    private class RecordButton(context: Context) : View(context) {
        var state = CaptureController.State.Idle
            set(value) { if (field != value) { field = value; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()
        private val square = RectF()
        init { isClickable = true; isFocusable = true }
        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val radius = minOf(cx, cy)
            val line = Design.dp(this, 3f).toFloat()
            paint.style = Paint.Style.FILL; paint.color = 0x44000000
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = line; paint.color = Design.text
            canvas.drawCircle(cx, cy, radius - line, paint)
            paint.style = Paint.Style.FILL
            val inner = radius - line * 3
            when (state) {
                CaptureController.State.Recording -> {
                    paint.color = Design.recording
                    square.set(cx - inner * 0.45f, cy - inner * 0.45f, cx + inner * 0.45f, cy + inner * 0.45f)
                    canvas.drawRoundRect(square, inner * 0.12f, inner * 0.12f, paint)
                }
                CaptureController.State.Paused -> {
                    paint.color = Design.recording; canvas.drawCircle(cx, cy, inner, paint)
                    paint.color = Design.text
                    shape.rewind(); shape.moveTo(cx - inner * 0.28f, cy - inner * 0.4f); shape.lineTo(cx + inner * 0.44f, cy); shape.lineTo(cx - inner * 0.28f, cy + inner * 0.4f); shape.close()
                    canvas.drawPath(shape, paint)
                }
                CaptureController.State.Saving -> { paint.color = Design.separator; canvas.drawCircle(cx, cy, inner, paint) }
                else -> { paint.color = Design.recording; canvas.drawCircle(cx, cy, inner, paint) }
            }
        }
    }

    /// A plain View asked to wrap its content takes all the room offered; these have a size.
    private open class Fixed(context: Context, private val fixedWidth: Int, private val fixedHeight: Int) : View(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(fixedWidth, fixedHeight)
    }

    /// Peak level of the incoming audio from −60 to 0 dBFS, with the held peak as a tick.
    private class LevelMeter(context: Context) : Fixed(context, Design.dp(context, 64f), Design.dp(context, 8f)) {
        private var level = CaptureAudio.floor
        private var hold = CaptureAudio.floor
        private var clipped = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = RectF()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun show(level: Float, hold: Float, clipped: Boolean) { this.level = level; this.hold = hold; this.clipped = clipped; invalidate() }
        private fun x(db: Float) = width * ((db - CaptureAudio.floor) / -CaptureAudio.floor).coerceIn(0f, 1f)
        override fun onDraw(canvas: Canvas) {
            val round = height / 2f
            paint.color = 0x55FFFFFF; bar.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(bar, round, round, paint)
            paint.color = if (clipped) Design.recording else if (level > -6f) Design.warning else Design.positive
            bar.set(0f, 0f, x(level), height.toFloat()); canvas.drawRoundRect(bar, round, round, paint)
            if (hold > CaptureAudio.floor) { paint.color = Design.text; canvas.drawRect(x(hold) - 2f, 0f, x(hold), height.toFloat(), paint) }
        }
    }
}
