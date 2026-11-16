package yourec

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.UUID
import kotlin.math.abs

/// The cut editor: a large preview, transport, a row of plainly named tools and
/// the shared thumbnail/waveform timeline. Every tool builds a new edit from the
/// current one and commits it as one undo step; nothing here mutates media.
class EditorView(context: Context, private val store: ProjectStore, private val navigation: Navigation) : LinearLayout(context), Screen {
    var projectID: UUID? = null
    private val editor = EditorController()
    private val timeline = EditorTimeline(context)
    private val preview = TextureView(context)
    private val previewBox = FrameLayout(context)
    private val title = Design.label(context, "", 16f, bold = true)
    private val time = Design.label(context, "0:00.00", 15f, mono = true)
    private val feedback = Design.label(context, "", 13f, Design.secondary)
    private val play = Design.symbol(context, "▶", "Play", 56f, Design.raised) { editor.togglePlay() }
    private val undo = Design.symbol(context, "↶", "Undo", fill = 0) { history(redo = false) }
    private val redo = Design.symbol(context, "↷", "Redo", fill = 0) { history(redo = true) }
    private val tools = LinearLayout(context)
    private val panel = FrameLayout(context)
    private var selected: Set<UUID> = emptySet()
    private var visible = false
    // Move works on a draft until Done; Cancel leaves no trace in history.
    private var moveDraft: EditState? = null
    private var moveSelection: TimelineSelection? = null
    private var moveLatch: TimelineMoveSnap? = null

    private val project: Project? get() = store.projects.firstOrNull { it.id == projectID }

    init {
        orientation = VERTICAL
        setBackgroundColor(Design.background)
        val bar = Design.row(context, Design.button(context, "‹ Projects", fill = 0) { navigation.showProjects() }, title, spacing = 4f)
        title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        (title.layoutParams as LayoutParams).apply { width = 0; weight = 1f }
        bar.addView(undo); bar.addView(redo)
        bar.addView(Design.button(context, "Export", Design.background, Design.accent) { openExport() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 4f); rightMargin = Design.dp(context, 8f) })
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        previewBox.addView(preview, Design.frame(gravity = Gravity.CENTER))
        previewBox.contentDescription = "Preview. Tap to play or pause."
        previewBox.setOnClickListener { editor.togglePlay() }
        addView(previewBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { editor.attach(surface) }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { editor.attach(surface) }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { editor.detach(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }
        }
        previewBox.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) fitPreview() }

        val transport = Design.row(context,
            Design.symbol(context, "⏮", "Previous cut", fill = 0) { jump(false) }, play,
            Design.symbol(context, "⏭", "Next cut", fill = 0) { jump(true) }, spacing = 12f, gravity = Gravity.CENTER)
        val status = FrameLayout(context)
        status.addView(time, Design.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = Design.dp(context, 12f) })
        status.addView(transport, Design.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        status.addView(Design.symbol(context, "◆", "Add bookmark", fill = 0) { addBookmark() }, Design.frame(Design.dp(context, 48f), Design.dp(context, 48f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = Design.dp(context, 8f) })
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        feedback.gravity = Gravity.CENTER; feedback.minHeight = Design.dp(context, 20f)
        addView(feedback, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tools.orientation = HORIZONTAL; tools.setPadding(Design.dp(context, 8f), 0, Design.dp(context, 8f), 0)
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tools) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(timeline, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(context, 190f)).apply { topMargin = Design.dp(context, 6f) })
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        editor.onChange = { refreshTransport() }
        // Scrubbing pauses playback and stays paused after release.
        timeline.onScrub = { time, _ -> editor.seek(time) }
        timeline.onTap = { id -> select(if (id == null) emptySet() else project?.edit?.timeline?.selection(id)?.ids ?: emptySet()) }
        timeline.onLongPress = { id -> project?.let { select(it.edit.timeline.toggling(id, selected)) } }
        timeline.onTrim = { id, left, delta -> trim(id, left, delta) }
        timeline.onMove = { seconds, ended -> dragMove(seconds, ended) }
    }

    override fun appear() {
        visible = true
        val id = projectID ?: return navigation.showProjects()
        if (!store.openProject(id)) return navigation.showProjects()
        store.observe(this) { if (visible) reload() }
        Exports.clean(store.exportsFolder(project ?: return))
        reload()
    }
    override fun disappear() {
        visible = false
        store.stopObserving(this)
        editor.pause()
        cancelMove()
        closePanel()
    }
    override fun back(): Boolean {
        if (panel.childCount > 0) { closePanel(); return true }
        if (moveDraft != null) { cancelMove(); return true }
        return false
    }
    /// The activity is finishing: close decoders and the render thread.
    fun release() { editor.release(); SourceMediaCache.close() }

    private fun reload() {
        val project = project ?: return navigation.showProjects()
        if (project.deferredHistory != null) return
        val edit = moveDraft ?: project.edit
        title.text = project.title
        selected = selected.intersect(edit.timeline.allIDs)
        timeline.timeline = edit.timeline; timeline.sources = project.sources
        timeline.folder = store.folder(project); timeline.cache = store.cacheFolder(project)
        editor.load(project, store.folder(project))
        fitPreview()
        undo.alpha = if (project.canUndo) 1f else 0.3f; redo.alpha = if (project.canRedo) 1f else 0.3f
        val missing = store.missingSources(project)
        if (missing.isNotEmpty()) feedback.text = "${missing.size} source file${if (missing.size == 1) " is" else "s are"} missing. The project has been preserved."
        else if (project.sources.isEmpty()) feedback.text = "Add a recording before previewing or exporting."
        rebuildTools()
        refreshTransport()
    }
    private fun refreshTransport() {
        time.text = Design.timecode(editor.playhead) + " / " + Design.timecode(editor.duration)
        play.text = if (editor.playing) "❚❚" else "▶"; play.contentDescription = if (editor.playing) "Pause" else "Play"
        timeline.playhead = editor.playhead; timeline.selected = selected
        timeline.invalidate()
        // Keep the screen awake while the movie plays.
        keepScreenOn = editor.playing
    }
    /// Give the preview the project's shape inside the space available.
    private fun fitPreview() {
        val w = previewBox.width; val h = previewBox.height
        if (w == 0 || h == 0) return
        val shape = editor.canvas.width / editor.canvas.height
        val params = preview.layoutParams as FrameLayout.LayoutParams
        val width = if (w.toDouble() / h > shape) (h * shape).toInt() else w
        val height = if (w.toDouble() / h > shape) h else (w / shape).toInt()
        if (params.width != width || params.height != height) { params.width = width; params.height = height; preview.layoutParams = params }
    }

    private fun select(ids: Set<UUID>) { selected = ids; rebuildTools(); refreshTransport() }
    private fun selection(edit: EditState) = edit.timeline.selection(selected)
    private fun say(message: String) { feedback.text = message }

    /// Tools appear only when they apply, so the row always reads as what can be done now.
    private fun rebuildTools() {
        tools.removeAllViews()
        val project = project ?: return
        fun tool(name: String, color: Int = Design.text, action: () -> Unit) {
            tools.addView(Design.button(context, name, color) { action() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Design.dp(context, 6f) })
        }
        if (moveDraft != null) {
            tool("Done", Design.accent) { finishMove() }; tool("Cancel") { cancelMove() }
            say("Drag the highlighted parts along the timeline. They snap to cuts.")
            return
        }
        val edit = project.edit
        val chosen = selection(edit)
        val a = timeline.rangeStart; val b = timeline.rangeEnd
        if (edit.timeline.duration > EditTime.zero) {
            tool("Split") { split() }
            tool(if (a == null) "Start" else "End") { mark() }
            // Clear removes either a Start mark or a completed range without deleting footage.
            if (a != null) tool("Clear") { timeline.rangeStart = null; timeline.rangeEnd = null; rebuildTools(); timeline.invalidate(); say("") }
            if ((a != null && b != null) || !chosen.isEmpty) tool("Delete", Design.recording) { delete() }
            if (!chosen.isEmpty) tool("Move") { beginMove() }
            tool("Volume") { openVolume() }
            if (!chosen.isEmpty && edit.timeline.links.any { link -> link.members.any { it in chosen.ids } }) tool("Unlink") { unlink() }
            else if (chosen.ids.size >= 2) tool("Link") { link(false) }
            if (edit.timeline.gap(EditTime.seconds(editor.playhead)) != null) tool("Close gap") { closeGap() }
            if (edit.timeline.resolvedBookmarks.isNotEmpty()) { tool("‹ Mark") { jumpBookmark(false) }; tool("Mark ›") { jumpBookmark(true) } }
        }
        tool("Record") { navigation.showRecorder(project.id) }
        tool("Add video") { addMedia(audioOnly = false) }
        tool("Add audio") { addMedia(audioOnly = true) }
    }

    private fun commit(edit: EditState, message: String, position: Double? = null) {
        val id = projectID ?: return
        position?.let { editor.seek(it) }
        if (store.commit(id, edit)) say("$message · Undo available")
    }
    private fun history(redo: Boolean) {
        val project = project ?: return
        if (if (redo) !project.canRedo else !project.canUndo) return
        store.update(project.id) { if (redo) it.redo() else it.undo() }
        say(if (redo) "Redone" else "Undone")
    }
    private fun split() {
        val project = project ?: return
        val edit = TimelineEditing.split(project.edit, editor.playhead, selected)
        if (edit == project.edit) return say("Move the playhead inside a clip to split it.")
        commit(edit, "Split")
    }
    private fun mark() {
        val now = TimelineEditing.quantize(editor.playhead)
        val start = timeline.rangeStart
        if (start == null) { timeline.rangeStart = now; timeline.rangeEnd = null; say("Start marked. Move the playhead and tap End.") }
        else if (abs(now - start) < TimelineEditing.frame) return say("Move the playhead away from Start, then tap End.")
        else { timeline.rangeStart = minOf(start, now); timeline.rangeEnd = maxOf(start, now); say("Range marked. Delete removes it; Clear keeps the footage.") }
        rebuildTools(); timeline.invalidate()
    }
    private fun delete() {
        val project = project ?: return
        val a = timeline.rangeStart; val b = timeline.rangeEnd
        val playhead = EditTime.seconds(editor.playhead)
        val (edit, position) = if (a != null && b != null) TimelineEditing.removing(a, b, project.edit, playhead)
                               else TimelineEditing.deleting(selection(project.edit), project.edit, playhead)
        if (edit == project.edit) return
        timeline.rangeStart = null; timeline.rangeEnd = null; selected = emptySet()
        commit(edit, "Deleted", position?.seconds)
    }
    private fun trim(id: UUID, left: Boolean, delta: Double) {
        val project = project ?: return
        val (edit, position) = TimelineEditing.trimming(id, left, delta, project.edit, project.sources, EditTime.seconds(editor.playhead))
        if (edit == project.edit) return
        commit(edit, if (left && delta < 0 || !left && delta > 0) "Trim extended" else "Trimmed", position?.seconds)
    }
    private fun unlink() {
        val project = project ?: return
        val edit = project.edit
        edit.timeline.unlink(selection(edit))
        selected = emptySet()
        commit(edit, "Unlinked")
    }
    private fun link(intersectionOnly: Boolean) {
        val project = project ?: return
        try {
            val (joined, scope) = project.edit.timeline.joining(TimelineSelection(selected.intersect(project.edit.timeline.video.map { it.id }.toSet()),
                selected.intersect(project.edit.timeline.audio.map { it.id }.toSet())), intersectionOnly)
            selected = scope.ids
            commit(EditState(joined, project.edit.outputSize), "Linked")
        } catch (e: TimelineLinkError) {
            if (e.kind != TimelineLinkError.Kind.ConfirmationRequired) return say(e.message ?: "")
            showPanel(Design.column(context, Design.label(context, e.message ?: ""),
                Design.row(context, Design.button(context, "Link intersection", Design.accent) { closePanel(); link(true) }, Design.button(context, "Cancel") { closePanel() })))
        }
    }
    private fun closeGap() {
        val project = project ?: return
        val edit = project.edit
        val gap = edit.timeline.gap(EditTime.seconds(editor.playhead)) ?: return
        edit.timeline.closeGap(EditTime.seconds(editor.playhead))
        commit(edit, "Gap closed", gap.start.seconds)
    }
    private fun jump(forward: Boolean) {
        val project = project ?: return
        TimelineNavigation.jump(project.edit.timeline, editor.playhead, forward)?.let { editor.seek(it) }
    }
    private fun addBookmark() {
        val project = project ?: return
        val edit = project.edit
        edit.timeline.addBookmark(EditTime.seconds(editor.playhead), selected.firstOrNull())
        if (store.commit(project.id, edit)) say("Bookmark added")
    }
    private fun jumpBookmark(forward: Boolean) {
        val marks = project?.edit?.timeline?.resolvedBookmarks ?: return
        val mark = if (forward) marks.firstOrNull { it.time.seconds > editor.playhead + 0.02 } else marks.lastOrNull { it.time.seconds < editor.playhead - 0.02 }
        mark?.let { editor.seek(it.time.seconds) }
    }

    private fun beginMove() {
        val project = project ?: return
        editor.pause()
        val draft = project.edit
        val scope = selection(draft)
        if (scope.isEmpty) return
        moveDraft = draft; moveSelection = scope; moveLatch = null; moveOrigin = draft.copy()
        timeline.moving = scope; timeline.moveDelta = EditTime.zero
        rebuildTools(); timeline.invalidate()
    }
    private var moveOrigin: EditState? = null
    private var moveApplied = EditTime.zero
    private fun dragMove(seconds: Double, ended: Boolean) {
        val origin = moveOrigin ?: return; val scope = moveSelection ?: return
        // Targets use the timeline as it was when Move began, so the draft never creates another stop.
        val result = origin.timeline.snappedMove(scope, moveApplied + EditTime.seconds(seconds), timeline.viewport.scale, moveLatch)
        if (result.snap != null && result.snap != moveLatch) Design.tick(this)
        moveLatch = result.snap
        timeline.moveDelta = result.delta
        if (ended) { moveApplied = result.delta; moveLatch = null }
        timeline.invalidate()
    }
    private fun finishMove() {
        val origin = moveOrigin ?: return; val scope = moveSelection ?: return
        val edit = origin.copy()
        edit.timeline.move(scope, moveApplied)
        cancelMove()
        if (edit != origin) commit(edit, "Moved")
    }
    private fun cancelMove() {
        if (moveDraft == null) return
        moveDraft = null; moveSelection = null; moveOrigin = null; moveLatch = null; moveApplied = EditTime.zero
        timeline.moving = null; timeline.moveDelta = EditTime.zero
        say(""); rebuildTools(); timeline.invalidate()
    }

    private fun addMedia(audioOnly: Boolean) {
        val id = projectID ?: return
        editor.pause()
        val at = editor.playhead
        navigation.pickMedia(if (audioOnly) "audio/*" else "video/*") { uri ->
            if (uri == null) return@pickMedia
            say(if (audioOnly) "Adding audio…" else "Importing video…")
            store.importMedia(uri, id, audioOnly) { project, source ->
                if (project == null || source == null) return@importMedia say(store.error ?: "The file could not be added.")
                if (!audioOnly) return@importMedia say("Video added at the end of the project.")
                // An independent layer at the playhead; nothing else shifts.
                val edit = project.edit
                selected = setOf(edit.timeline.insertAudio(source, EditTime.seconds(at)))
                commit(edit, "Audio added")
            }
        }
    }

    // Panels sit below the timeline, inside the editor: no dialog covers the preview.
    private fun showPanel(content: View) {
        panel.removeAllViews()
        content.setPadding(Design.dp(context, 16f), Design.dp(context, 12f), Design.dp(context, 16f), Design.dp(context, 16f))
        content.background = Design.rounded(Design.surface, Design.dp(context, 16f).toFloat())
        panel.addView(content, Design.frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun closePanel() { panel.removeAllViews() }

    /// Volume and fades for the selected sound, or for all sound when nothing is selected.
    /// Each completed slider gesture is one undo step.
    private fun openVolume() {
        val project = project ?: return
        fun scope(edit: EditState) = selection(edit).let { if (it.audio.isEmpty()) edit.timeline.entireSelection() else it }
        val first = project.edit.timeline.audio.firstOrNull { it.id in scope(project.edit).audio } ?: return say("There is no sound here to adjust.")
        val level = Design.label(context, "", 15f, mono = true)
        fun describe(db: Double, muted: Boolean) { level.text = if (muted) "Muted" else String.format("%+.1f dB", db) }
        describe(first.volumeDB, first.muted)
        // The slider covers −60…+12 dB in half-decibel steps.
        val slider = SeekBar(context).apply { max = 144; progress = ((first.volumeDB + 60) * 2).toInt() }
        var muted = first.muted
        fun apply(message: String) {
            val current = this.project ?: return
            val edit = current.edit
            edit.timeline.setVolume(slider.progress / 2.0 - 60, muted, scope(edit))
            if (edit != current.edit) commit(edit, message)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { describe(value / 2.0 - 60, muted) }
            override fun onStartTrackingTouch(bar: SeekBar) { }
            override fun onStopTrackingTouch(bar: SeekBar) { apply("Volume changed") }
        })
        val mute = Design.button(context, if (muted) "Unmute" else "Mute") { view -> muted = !muted; (view as TextView).text = if (muted) "Unmute" else "Mute"; describe(slider.progress / 2.0 - 60, muted); apply(if (muted) "Muted" else "Unmuted") }
        fun fade(edge: AudioFadeEdge): View {
            val target = project.edit.timeline.let { t -> edge.index(t.audio, scope(project.edit).audio)?.let { t.audio[it] } }
            val value = Design.label(context, "", 14f, mono = true)
            fun show(seconds: Double) { value.text = if (seconds == 0.0) "Off" else String.format("%.1f s", seconds) }
            show(target?.fadeSeconds(edge) ?: 0.0)
            // Tenths of a second, up to ten seconds.
            val bar = SeekBar(context).apply { max = 100; progress = Math.round((target?.fadeSeconds(edge) ?: 0.0) * 10).toInt() }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) { show(v / 10.0) }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) {
                    val current = this@EditorView.project ?: return
                    val edit = current.edit
                    edit.timeline.setFade(edge, b.progress / 10.0, scope(edit))
                    if (edit != current.edit) commit(edit, edge.title + " changed")
                }
            })
            return LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Design.label(context, edge.title, 14f), LayoutParams(Design.dp(context, 72f), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(bar, Design.weighted(1f, Design.dp(context, 48f))); addView(value, LayoutParams(Design.dp(context, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val heading = Design.row(context, Design.label(context, if (selection(project.edit).audio.isEmpty()) "Volume · all sound" else "Volume · selected sound", 15f, bold = true), level, spacing = 12f)
        val content = Design.column(context, heading, spacing = 4f)
        content.addView(slider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(context, 48f)))
        content.addView(fade(AudioFadeEdge.FadeIn), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(fade(AudioFadeEdge.FadeOut), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Design.row(context, mute, Design.button(context, "Done", Design.accent) { closePanel() }))
        showPanel(content)
    }

    private fun openExport() {
        val project = project ?: return
        editor.pause()
        if (project.edit.duration <= 0) return say("Add a recording before previewing or exporting.")
        showPanel(MovieExportPanel(context, store, navigation, project.id) { closePanel() })
    }
}
