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
        // The recorder, another app or an export may need the decoders this screen holds.
        editor.suspend()
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
            if ((a != null && b != null) || !chosen.isEmpty) { tool("Copy") { copyOrCut(false) }; tool("Cut") { copyOrCut(true) } }
            if (store.clipboards[project.id]?.isEmpty == false) tool("Paste") { paste(null) }
            tool("Volume") { openVolume() }
            if (edit.timeline.video.isNotEmpty()) { tool("Speed") { openSpeed() }; tool("Crop") { openCrop() }; tool("Mirror") { openMirror() }; tool("Color") { openColor() } }
            if (edit.timeline.video.isNotEmpty()) tool("Text") { openText(null) }
            if (edit.timeline.video.isNotEmpty()) tool("Cover") { openCover() }
            if (edit.timeline.compile().any { it.video.size > 1 }) tool("Placement") { openPlacement(null) }
            if (!chosen.isEmpty && edit.timeline.links.any { link -> link.members.any { it in chosen.ids } }) tool("Unlink") { unlink() }
            else if (chosen.ids.size >= 2) tool("Link") { link(false) }
            if (edit.timeline.gap(EditTime.seconds(editor.playhead)) != null) tool("Close gap") { closeGap() }
            if (edit.timeline.resolvedBookmarks.isNotEmpty()) { tool("‹ Mark") { jumpBookmark(false) }; tool("Mark ›") { jumpBookmark(true) } }
        }
        tool("Record") { navigation.showRecorder(project.id) }
        tool("Add video") { addMedia(audioOnly = false) }
        tool("Add photo") { addPhoto() }
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
        else { timeline.rangeStart = minOf(start, now); timeline.rangeEnd = maxOf(start, now); say("Range marked. Delete removes it, Copy or Cut take it; Clear keeps the footage.") }
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
    /// What Copy, Cut and Delete act on: the marked range, or else the selected parts.
    private fun scope(edit: EditState): TimelineSelection? {
        val a = timeline.rangeStart; val b = timeline.rangeEnd
        if (a != null && b != null) return TimelineEditing.rangeSelection(minOf(a, b), maxOf(a, b), edit)
        return selection(edit).takeIf { !it.isEmpty }
    }
    private fun copyOrCut(cut: Boolean) {
        val project = project ?: return
        val scope = scope(project.edit) ?: return
        if (cut) {
            val (edit, clipboard, position) = TimelineClipboardEditing.cutting(scope, project.edit, EditTime.seconds(editor.playhead)) ?: return
            timeline.rangeStart = null; timeline.rangeEnd = null; selected = emptySet()
            store.clipboards[project.id] = clipboard
            commit(edit, "Cut to clipboard", position.seconds)
        } else {
            val clipboard = TimelineClipboardEditing.copying(scope, project.edit) ?: return
            editor.pause()
            store.clipboards[project.id] = clipboard
            timeline.rangeStart = null; timeline.rangeEnd = null; selected = emptySet()
            timeline.invalidate(); rebuildTools()
            say("Copied to clipboard")
        }
    }
    /// Paste at the playhead. Where a picture cut and a sound cut meet, or at an
    /// edge of a project with music beyond its pictures, the user picks one.
    private fun paste(target: TimelinePasteTarget?, chosen: Boolean = false) {
        val project = project ?: return
        val clipboard = store.clipboards[project.id] ?: return
        editor.pause()
        val playhead = EditTime.seconds(editor.playhead)
        val sites = TimelineClipboardEditing.targets(project.edit, playhead)
        if (!chosen && sites.size > 1) {
            val buttons = sites.map { choice -> Design.button(context, choice.label(project.edit.timeline), Design.text, Design.raised, 13f) { closePanel(); paste(choice, true) }
                .apply { contentDescription = "Paste at " + choice.label(project.edit.timeline) } }
            val content = Design.column(context, Design.label(context, "Paste where?", 15f, bold = true), spacing = 6f)
            content.addView(choices(*buttons.toTypedArray()), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(Design.button(context, "Cancel") { closePanel() })
            showPanel(content)
            return
        }
        val (edit, added) = TimelineClipboardEditing.pasting(clipboard, project.edit, playhead, if (chosen) target else sites.firstOrNull()) ?: return
        timeline.rangeStart = null; timeline.rangeEnd = null
        commit(edit, "Pasted", edit.timeline.interval(added)?.start?.seconds)
        select(added.ids); timeline.invalidate()
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

    private fun addPhoto() {
        val id = projectID ?: return
        editor.pause()
        navigation.pickMedia("image/*") { uri ->
            if (uri == null) return@pickMedia
            say("Adding photo…")
            store.importPhoto(uri, id) { project -> say(if (project != null) "Photo added at the end of the project · Undo available" else store.error ?: "The photo could not be added.") }
        }
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
        releaseCover()
        panel.removeAllViews()
        content.setPadding(Design.dp(context, 16f), Design.dp(context, 12f), Design.dp(context, 16f), Design.dp(context, 16f))
        content.background = Design.rounded(Design.surface, Design.dp(context, 16f).toFloat())
        panel.addView(content, Design.frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun closePanel() {
        releaseCover()
        panel.removeAllViews()
        // A control released outside its track may have left a draft on screen.
        project?.let { if (it.deferredHistory == null) editor.show(it.edit) }
    }

    // Select cover: frames of the finished edit from the marked range, the
    // selected clips or the whole project. Nothing is stored in the project.
    private var coverFrames: EditFrames? = null
    private fun releaseCover() { coverFrames?.release(); coverFrames = null }
    private fun showCoverPanel(content: View) {
        panel.removeAllViews()
        content.setPadding(Design.dp(context, 16f), Design.dp(context, 12f), Design.dp(context, 16f), Design.dp(context, 16f))
        content.background = Design.rounded(Design.surface, Design.dp(context, 16f).toFloat())
        panel.addView(content, Design.frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private class CoverScope(val pool: CoverFramePool, val label: String, val range: String, val title: String)
    private fun openCover() {
        val project = project ?: return
        // The cover frames need decoders of their own: the preview gives its up.
        editor.suspend()
        val timeline = project.edit.timeline
        val selected = scope(project.edit)
        val (ranges, label) = when {
            selected?.range != null -> listOf(selected.range) to "Selected range"
            selected != null -> (timeline.video.filter { it.id in selected.video }.map { it.interval } + timeline.audio.filter { it.id in selected.audio }.map { it.interval }) to "Selected clips"
            else -> listOf(EditInterval(EditTime.zero, timeline.duration)) to "Whole project"
        }
        val range = EditFrames.timestamp(ranges.minOfOrNull { it.start } ?: EditTime.zero) + " – " + EditFrames.timestamp(ranges.maxOfOrNull { it.end } ?: EditTime.zero)
        releaseCover()
        val frames = EditFrames(project, store.folder(project))
        val scope = CoverScope(CoverFramePool(timeline.video.map { it.interval }, ranges), label, range, project.title)
        coverGrid(frames, scope, emptyList())
        coverFrames = frames
    }
    private fun coverGrid(frames: EditFrames, scope: CoverScope, previous: List<Int>) {
        val picks = scope.pool.picks(previous)
        val aspect = frames.canvas.width / frames.canvas.height
        val content = Design.column(context, spacing = 6f)
        val update = Design.button(context, "Update", Design.text, Design.raised, 13f) { coverGrid(frames, scope, picks) }
            .apply { isEnabled = scope.pool.count > 9; alpha = if (isEnabled) 1f else 0.4f; contentDescription = "Update. Show other frames from the same range." }
        content.addView(Design.row(context, Design.column(context, Design.label(context, "Select cover · " + scope.label, 15f, bold = true),
            Design.label(context, scope.range, 12f, Design.secondary, mono = true), spacing = 2f).apply { layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, update))
        if (picks.isEmpty()) content.addView(Design.label(context, "No video in this range. Select a range that contains video.", 14f, Design.secondary))
        val columns = 3
        val cell = (resources.displayMetrics.widthPixels - Design.dp(context, 32f + 12f)) / columns
        for (rowStart in picks.indices step columns) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (pick in picks.subList(rowStart, minOf(picks.size, rowStart + columns))) {
                val stamp = EditFrames.timestamp(scope.pool.time(pick))
                val image = android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF171717.toInt()) }
                val box = Design.column(context, image, Design.label(context, stamp, 11f, Design.secondary, mono = true), spacing = 2f).apply {
                    isClickable = true; contentDescription = "Frame at $stamp"
                    setOnClickListener { coverDetail(frames, scope, pick, picks) }
                }
                image.layoutParams = LayoutParams(cell - Design.dp(context, 6f), ((cell - Design.dp(context, 6f)) / aspect).toInt().coerceAtMost(cell * 2))
                row.addView(box, LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT))
                frames.grab(scope.pool.time(pick), 512) { picture -> if (picture != null) image.setImageBitmap(picture) else image.contentDescription = "Frame could not be loaded" }
            }
            content.addView(row)
        }
        content.addView(Design.button(context, "Done", Design.accent) { closePanel() })
        showCoverPanel(android.widget.ScrollView(context).apply { addView(content) })
    }
    private fun coverDetail(frames: EditFrames, scope: CoverScope, start: Int, picks: List<Int>) {
        var pick = start
        var shown: Pair<Int, android.graphics.Bitmap>? = null
        val image = android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF121212.toInt()); contentDescription = "Cover image" }
        val title = Design.label(context, "", 15f, bold = true, mono = true)
        val share = Design.button(context, "Share JPEG", Design.background, Design.accent) {
            val (at, picture) = shown?.takeIf { it.first == pick } ?: return@button
            val project = project ?: return@button
            try { navigation.share(EditFrames.jpeg(picture, scope.title, scope.pool.time(at), store.exportsFolder(project))) }
            catch (e: Exception) { say("Couldn’t write the JPEG. " + (e.message ?: "")) }
        }
        fun show(index: Int) {
            pick = index.coerceIn(0, maxOf(0, scope.pool.count - 1))
            val time = scope.pool.time(pick)
            title.text = EditFrames.timestamp(time)
            share.isEnabled = false; share.alpha = 0.4f
            frames.grab(time, 4096, latest = true) { picture ->
                if (picture == null) { say("Couldn’t load this frame."); return@grab }
                shown = pick to picture; image.setImageBitmap(picture)
                image.contentDescription = "Cover image, ${picture.width} × ${picture.height} pixels"
                share.isEnabled = true; share.alpha = 1f
            }
        }
        // Swiping left reveals later frames, a fixed distance per frame, no momentum.
        var origin = 0; var downX = 0f
        image.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { origin = pick; downX = event.x; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val next = origin - ((event.x - downX) / Design.dp(context, 24f)).toInt()
                    if (next.coerceIn(0, scope.pool.count - 1) != pick) show(next)
                    true
                }
                else -> { view.performClick(); true }
            }
        }
        val aspect = frames.canvas.width / frames.canvas.height
        val width = resources.displayMetrics.widthPixels - Design.dp(context, 32f)
        val height = minOf((width / aspect).toInt(), (resources.displayMetrics.heightPixels * 0.45).toInt())
        val content = Design.column(context, Design.row(context, Design.button(context, "‹ Frames", Design.text, Design.raised, 13f) { coverGrid(frames, scope, picks) }, title), spacing = 8f)
        content.addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        content.addView(Design.label(context, "Swipe left for later frames, right for earlier ones.", 12f, Design.secondary))
        content.addView(Design.row(context,
            Design.button(context, "‹", Design.text, Design.raised) { show(pick - 1) }.apply { contentDescription = "Previous frame" },
            Design.button(context, "›", Design.text, Design.raised) { show(pick + 1) }.apply { contentDescription = "Next frame" },
            Design.button(context, "Another", Design.text, Design.raised, 13f) { show(scope.pool.randomAlternative(pick)) }.apply { contentDescription = "Another random frame from the same range" },
            share))
        showCoverPanel(content)
        show(pick)
    }

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

    /// The pictures a clip tool will change: the selected ones, or all of them.
    private fun targets(edit: EditState) = edit.timeline.video.filter { it.id in selected }.ifEmpty { edit.timeline.video }
    private fun scopeTitle(edit: EditState) = if (edit.timeline.video.any { it.id in selected }) "selected clips" else "whole project"
    private fun labelled(title: String, bar: SeekBar, value: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(Design.label(context, title, 14f), LayoutParams(Design.dp(context, 72f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bar, Design.weighted(1f, Design.dp(context, 44f))); addView(value, LayoutParams(Design.dp(context, 56f), ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun choices(vararg items: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { for (item in items) addView(item, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Design.dp(context, 6f) }) })
    }

    /// 0.25× to 4×, pitch preserved. Everything linked after a retimed clip follows it.
    private fun openSpeed() {
        val project = project ?: return
        editor.pause()
        val current = targets(project.edit).map { it.content.rate }.distinct().singleOrNull()
        val rates = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0)
        val buttons = rates.map { rate ->
            val chosen = current == rate
            Design.button(context, ZoomTransition.number(rate) + "×", if (chosen) Design.background else Design.text, if (chosen) Design.accent else Design.raised) {
                val now = this.project ?: return@button
                val edit = TimelineEditing.settingSpeed(rate, selection(now.edit), now.edit)
                if (edit != now.edit) commit(edit, "Speed " + ZoomTransition.number(rate) + "×")
                openSpeed()
            }.apply { contentDescription = "Speed " + ZoomTransition.number(rate) + " times" + if (chosen) ", selected" else "" }
        }
        val content = Design.column(context, Design.label(context, "Speed · " + scopeTitle(project.edit), 15f, bold = true),
            Design.label(context, "Sound keeps its pitch. Clips after a retimed one move with it.", 13f, Design.secondary), spacing = 6f)
        content.addView(choices(*buttons.toTypedArray()), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Design.dp(context, 8f) })
        content.addView(Design.button(context, "Done", Design.accent) { closePanel() })
        showPanel(content)
    }

    /// Flip the finished frame. Titles and overlays added later stay readable.
    private fun openMirror() {
        val project = project ?: return
        val first = targets(project.edit).firstOrNull()?.content?.mirror
        fun toggle(horizontal: Boolean) {
            val now = this.project ?: return
            val mirror = targets(now.edit).firstOrNull()?.content?.mirror
            val h = (mirror?.horizontal ?: false) != horizontal; val v = (mirror?.vertical ?: false) != !horizontal
            commit(TimelineEditing.settingMirror(h, v, selection(now.edit), now.edit), "Mirror changed")
            openMirror()
        }
        fun option(title: String, on: Boolean, horizontal: Boolean) = Design.button(context, title, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised) { toggle(horizontal) }
            .apply { contentDescription = title + if (on) ", on" else ", off" }
        showPanel(Design.column(context, Design.label(context, "Mirror · " + scopeTitle(project.edit), 15f, bold = true),
            Design.row(context, option("Flip left–right", first?.horizontal == true, true), option("Flip top–bottom", first?.vertical == true, false)),
            Design.button(context, "Done", Design.accent) { closePanel() }))
    }

    /// A look or an imported LUT with its strength, after exposure and white
    /// balance. Dragging previews the draft; releasing commits one undo step.
    private fun openColor() {
        val project = project ?: return
        editor.pause()
        var color = targets(project.edit).firstOrNull()?.content?.color ?: ColorSettings()
        val values = List(4) { Design.label(context, "", 14f, mono = true) }
        fun describe() {
            values[0].text = String.format("%.0f%%", color.amount * 100); values[1].text = String.format("%+.1f", color.exposure ?: 0.0)
            values[2].text = String.format("%+.0f", (color.warmth ?: 0.0) * 100); values[3].text = String.format("%+.0f", (color.tint ?: 0.0) * 100)
        }
        fun draft(now: Project) = TimelineEditing.settingColor(color, selection(now.edit), now.edit)
        fun apply(message: String) {
            val now = this.project ?: return
            val edit = draft(now)
            if (edit != now.edit) commit(edit, message)
        }
        fun bar(max: Int, start: Int, change: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    change(value); describe()
                    this@EditorView.project?.let { editor.show(draft(it)) }
                }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { apply("Color changed") }
            })
        }
        describe()
        fun choice(title: String, chosen: Boolean, action: () -> Unit) = Design.button(context, title, if (chosen) Design.background else Design.text, if (chosen) Design.accent else Design.raised, 13f) { action() }
            .apply { contentDescription = "Look $title" + if (chosen) ", selected" else "" }
        val looks = ArrayList<View>()
        color.imported?.let { looks.add(choice(it.name, true) { }) }
        for (look in ColorLook.values()) looks.add(choice(look.title, color.imported == null && color.look == look) {
            color = color.copy(look = look, imported = null); apply("Look " + look.title); openColor()
        })
        looks.add(Design.button(context, "Import LUT…", Design.text, Design.raised, 13f) {
            val id = projectID ?: return@button
            navigation.pickMedia("*/*") { uri ->
                if (uri == null) return@pickMedia
                store.importLUT(uri, id) { lut, failure ->
                    if (lut == null) { say(failure ?: "The LUT could not be imported."); return@importLUT }
                    color = color.copy(imported = lut); apply("LUT " + lut.name); openColor()
                }
            }
        })
        val content = Design.column(context, Design.label(context, "Color · " + scopeTitle(project.edit), 15f, bold = true), spacing = 2f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(choices(*looks.toTypedArray()))
        // Strength in percent; exposure in tenths of a stop over ±2; warmth and tint in percent over ±100.
        add(labelled("Strength", bar(100, Math.round(color.amount * 100).toInt()) { color = color.copy(amount = it / 100.0) }, values[0]))
        add(labelled("Exposure", bar(40, Math.round((color.exposure ?: 0.0) * 10 + 20).toInt()) { color = color.copy(exposure = (it - 20) / 10.0) }, values[1]))
        add(labelled("Warmth", bar(200, Math.round((color.warmth ?: 0.0) * 100 + 100).toInt()) { color = color.copy(warmth = (it - 100) / 100.0) }, values[2]))
        add(labelled("Tint", bar(200, Math.round((color.tint ?: 0.0) * 100 + 100).toInt()) { color = color.copy(tint = (it - 100) / 100.0) }, values[3]))
        add(Design.row(context, Design.button(context, "Reset color") {
            val now = this.project ?: return@button
            val edit = TimelineEditing.settingColor(null, selection(now.edit), now.edit)
            if (edit != now.edit) commit(edit, "Color removed")
            openColor()
        }, Design.button(context, "Done", Design.accent) { closePanel() }).apply { setPadding(0, Design.dp(context, 8f), 0, 0) })
        showPanel(content)
    }

    /// Framing inside the project shape. Dragging a control previews the draft;
    /// releasing it commits one undo step. The shape itself belongs to the whole project.
    private fun openCrop() {
        val project = project ?: return
        editor.pause()
        val target = targets(project.edit).firstOrNull()?.content ?: return
        val file = project.sources.firstOrNull { it.id == target.sourceID }?.let { store.mediaFile(project, it) }
        val video = file?.let { MediaFiles.video(it) }
        val source = video?.let { CanvasSize(it.displayWidth.toDouble(), it.displayHeight.toDouble()) } ?: editor.canvas
        var crop = (target.crop ?: CropSettings()).clamped(source, editor.canvas)
        val values = List(4) { Design.label(context, "", 14f, mono = true) }
        fun describe() {
            values[0].text = String.format("%.2f×", crop.zoom); values[1].text = String.format("%.0f%%", crop.x * 100)
            values[2].text = String.format("%.0f%%", crop.y * 100); values[3].text = String.format("%.0f°", crop.rotation)
        }
        fun draft(now: Project) = TimelineEditing.settingCrop(if (crop.isFullFrame(source, editor.canvas)) null else crop, selection(now.edit), now.edit)
        fun bar(max: Int, start: Int, change: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    change(value); crop = crop.clamped(source, editor.canvas); describe()
                    this@EditorView.project?.let { editor.show(draft(it)) }
                }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) {
                    val now = this@EditorView.project ?: return
                    val edit = draft(now)
                    if (edit != now.edit) commit(edit, "Crop changed")
                }
            })
        }
        describe()
        val content = Design.column(context, Design.label(context, "Crop · " + scopeTitle(project.edit), 15f, bold = true), spacing = 2f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Zoom 1–8 in hundredths; position in thousandths; rotation in whole degrees.
        add(labelled("Zoom", bar(700, Math.round((crop.zoom - 1) * 100).toInt()) { crop = crop.copy(zoom = 1 + it / 100.0) }, values[0]))
        add(labelled("Across", bar(1000, Math.round(crop.x * 1000).toInt()) { crop = crop.copy(x = it / 1000.0) }, values[1]))
        add(labelled("Down", bar(1000, Math.round(crop.y * 1000).toInt()) { crop = crop.copy(y = it / 1000.0) }, values[2]))
        add(labelled("Rotate", bar(360, Math.round(crop.rotation + 180).toInt()) { crop = crop.copy(rotation = it - 180.0) }, values[3]))
        val shapes = OutputShape.values().map { shape ->
            val reference = project.edit.copy().also { it.outputSize = null }
            val natural = RenderSources(project, store.folder(project)).canvas(reference)
            val size = if (shape == OutputShape.Original) null else OutputShape.canvas(natural, shape.aspect)
            val chosen = project.edit.outputSize == size
            Design.button(context, shape.title, if (chosen) Design.background else Design.text, if (chosen) Design.accent else Design.raised, 13f) {
                val now = this.project ?: return@button
                val edit = TimelineEditing.settingShape(size, now.edit)
                if (edit != now.edit) commit(edit, "Video shape " + shape.title)
                openCrop()
            }.apply { contentDescription = "Video shape " + shape.title + if (chosen) ", selected" else "" }
        }
        add(Design.label(context, "Video shape · whole project", 13f, Design.secondary).apply { setPadding(0, Design.dp(context, 6f), 0, Design.dp(context, 4f)) })
        add(choices(*shapes.toTypedArray()))
        add(Design.row(context, Design.button(context, "Reset crop") {
            val now = this.project ?: return@button
            val edit = TimelineEditing.settingCrop(null, selection(now.edit), now.edit)
            if (edit != now.edit) commit(edit, "Crop removed")
            openCrop()
        }, Design.button(context, "Done", Design.accent) { closePanel() }).apply { setPadding(0, Design.dp(context, 8f), 0, 0) })
        showPanel(content)
    }

    /// Arrange the videos that overlap under the playhead: presets, front/back
    /// order, Fit or Fill, and each picture's window and framing.
    private fun openPlacement(chosen: UUID?) {
        val project = project ?: return
        editor.pause()
        val at = EditTime.seconds(editor.playhead)
        val edit = project.edit
        val active = edit.timeline.video.filter { at in it.interval }.sortedWith(TrackTimeline.backToFront)
        if (active.size < 2) { closePanel(); return say("Move the playhead to where two videos overlap, then tap Placement.") }
        val ids = active.map { it.id }.toSet()
        var region = edit.timeline.placementSeed(ids, at)
        val ordered = VideoPlacement.ordered(active.map { it.id }, region.videos)
        val focus = chosen?.takeIf { it in ids } ?: ordered.last()
        fun draft(now: Project) = now.edit.also { it.timeline.setPlacement(region) }
        fun apply(message: String, next: VideoPlacementRegion, reopen: Boolean = true) {
            region = next
            val now = this.project ?: return
            val changed = draft(now)
            if (changed != now.edit) commit(changed, message)
            if (reopen) openPlacement(focus)
        }
        fun change(update: (VideoPlacement) -> VideoPlacement) { region = region.copy(videos = region.videos + (focus to update(region.videos[focus] ?: VideoPlacement()))) }

        val content = Design.column(context, Design.label(context, "Placement · ${active.size} videos", 15f, bold = true), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val presets = listOf("Full" to 0, "Side by side" to 1, "Stacked" to 2, "Inset" to 3).map { (name, kind) ->
            Design.button(context, name, size = 13f) {
                apply("Placement: $name", region.copy(videos = ordered.withIndex().associate { it.value to VideoPlacement.layout(kind, it.index, ordered.size) }))
            }.apply { contentDescription = "Arrange $name" }
        }
        add(choices(*presets.toTypedArray()))
        // Which picture the controls below act on, named by its number on the timeline.
        val numbers = edit.timeline.video.withIndex().associate { it.value.id to it.index + 1 }
        val pickers = ordered.map { id ->
            val on = id == focus
            Design.button(context, "Video ${numbers[id]}", if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { openPlacement(id) }
                .apply { contentDescription = "Adjust video ${numbers[id]}" + if (on) ", selected" else "" }
        }
        add(choices(*pickers.toTypedArray()).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
        val current = region.videos[focus] ?: VideoPlacement()
        val values = List(4) { Design.label(context, "", 14f, mono = true) }
        fun describe() {
            val now = region.videos[focus] ?: VideoPlacement()
            values[0].text = String.format("%.0f%%", now.width * 100); values[1].text = String.format("%.0f%%", now.x * 100)
            values[2].text = String.format("%.0f%%", now.y * 100); values[3].text = String.format("%.2f×", now.framing.zoom)
        }
        fun bar(max: Int, start: Int, update: (VideoPlacement, Int) -> VideoPlacement) = SeekBar(context).apply {
            this.max = max; progress = start.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    change { update(it, value) }; describe()
                    this@EditorView.project?.let { editor.show(draft(it)) }
                }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { apply("Placement changed", region, reopen = false) }
            })
        }
        describe()
        // Size scales the window keeping its shape; 5% to 100% of the frame width.
        val shape = current.height / current.width
        add(labelled("Size", bar(95, Math.round(current.width * 100).toInt() - 5) { p, v -> p.copy(width = (v + 5) / 100.0, height = ((v + 5) / 100.0 * shape).coerceIn(0.01, 3.0)) }, values[0]))
        add(labelled("Across", bar(100, Math.round(current.x * 100).toInt()) { p, v -> p.copy(x = v / 100.0) }, values[1]))
        add(labelled("Down", bar(100, Math.round(current.y * 100).toInt()) { p, v -> p.copy(y = v / 100.0) }, values[2]))
        add(labelled("Zoom", bar(700, Math.round((current.framing.zoom - 1) * 100).toInt()) { p, v -> p.copy(framing = p.framing.copy(zoom = 1 + v / 100.0)) }, values[3]))
        add(Design.row(context,
            Design.button(context, if (current.fill) "Fill" else "Fit", size = 13f) { change { it.copy(fill = !it.fill) }; apply(if (current.fill) "Fit" else "Fill", region) }
                .apply { contentDescription = if (current.fill) "Filling its window. Tap to fit the whole picture." else "Fitting the whole picture. Tap to fill its window." },
            Design.button(context, "Bring to front", size = 13f) {
                val front = ordered.filter { it != focus } + focus
                apply("Brought to front", region.copy(videos = region.videos.mapValues { (id, value) -> value.copy(order = front.indexOf(id)) }))
            },
            Design.button(context, "Reset", size = 13f) {
                apply("Placement reset", region.copy(videos = active.withIndex().associate { it.value.id to VideoPlacement(order = it.index) }))
            },
            Design.button(context, "Done", Design.accent) { closePanel() }, spacing = 6f).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
        showPanel(content)
    }

    /// A title on the picture under the playhead. Typing and dragging preview
    /// the draft; every finished change is one undo step.
    private fun openText(chosen: UUID?) {
        val project = project ?: return
        editor.pause()
        val edit = project.edit
        val here = TimelineEditing.textsAt(editor.playhead, edit)
        val id = chosen ?: here.lastOrNull()?.id
        if (id == null) {
            val (added, created) = TimelineEditing.addingText(TextOverlay(), editor.playhead, 3.0, edit)
            if (created == null) return say("Move the playhead onto a video to add text.")
            commit(added, "Text added")
            return openText(created)
        }
        val owner = edit.timeline.video.firstOrNull { item -> item.content.texts?.any { it.id == id } == true } ?: return closePanel()
        var title = owner.content.texts!!.first { it.id == id }
        val length = project.sources.firstOrNull { it.id == owner.content.sourceID }?.duration ?: owner.content.end
        fun preview() { this.project?.let { editor.show(TimelineEditing.updatingText(title, it.edit)) } }
        fun save(message: String, reopen: Boolean = false) {
            val now = this.project ?: return
            val changed = TimelineEditing.updatingText(title, now.edit)
            if (changed != now.edit) commit(changed, message)
            if (reopen) openText(id)
        }
        val content = Design.column(context, Design.label(context, "Text", 15f, bold = true), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val field = android.widget.EditText(context).apply {
            setText(title.text); setTextColor(Design.text); setHintTextColor(Design.secondary); hint = "Title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            contentDescription = "Title text"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { }
                override fun afterTextChanged(text: android.text.Editable?) { title = title.copy(text = text?.toString() ?: ""); preview() }
            })
            // Leaving the field is the end of one edit of the words.
            setOnFocusChangeListener { _, focused -> if (!focused) save("Text changed") }
        }
        add(field)
        add(choices(*TextStyle.values().map { style ->
            val on = style == title.style
            Design.button(context, style.name, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { title = title.copy(style = style); save("Text style", reopen = true) }
                .apply { contentDescription = "Style " + style.name + if (on) ", selected" else "" }
        }.toTypedArray()))
        add(choices(*TextColor.values().map { color ->
            val on = color == title.color
            Design.button(context, if (on) "●" else " ", if (color == TextColor.White || color == TextColor.Yellow || color == TextColor.Mint) Design.background else Design.text, color.argb, 13f) { title = title.copy(color = color); save("Text colour", reopen = true) }
                .apply { contentDescription = "Colour " + color.name + if (on) ", selected" else "" }
        }.toTypedArray()).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
        val values = List(3) { Design.label(context, "", 14f, mono = true) }
        fun describe() { values[0].text = String.format("%.0f%%", title.size * 100); values[1].text = String.format("%.0f%%", title.x * 100); values[2].text = String.format("%.0f%%", title.y * 100) }
        fun bar(max: Int, start: Int, update: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) { if (user) { update(value); describe(); preview() } }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { save("Text changed") }
            })
        }
        describe()
        // Size from 2% to 30% of the shorter side of the frame.
        add(labelled("Size", bar(280, Math.round(title.size * 1000).toInt() - 20) { title = title.copy(size = (it + 20) / 1000.0) }, values[0]))
        add(labelled("Across", bar(100, Math.round(title.x * 100).toInt()) { title = title.copy(x = it / 100.0) }, values[1]))
        add(labelled("Down", bar(100, Math.round(title.y * 100).toInt()) { title = title.copy(y = it / 100.0) }, values[2]))
        val rate = owner.content.rate
        val lasting = (title.end - title.start) / rate
        add(choices(*EditorDurationPresets.wholeSeconds.filter { title.start + it * rate <= length + 0.001 || it == 1.0 }.map { seconds ->
            val on = Math.abs(lasting - seconds) < 0.02
            Design.button(context, ZoomTransition.number(seconds) + " s", if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) {
                title = title.copy(end = minOf(length, title.start + seconds * rate)); save("Text duration", reopen = true)
            }.apply { contentDescription = "On screen for " + ZoomTransition.number(seconds) + " seconds" + if (on) ", selected" else "" }
        }.toTypedArray()))
        add(Design.row(context,
            Design.button(context, "Remove", Design.recording, size = 13f) {
                val now = this.project ?: return@button
                commit(TimelineEditing.removingText(id, now.edit), "Text removed"); closePanel()
            },
            Design.button(context, "Add another", size = 13f) {
                save("Text changed")
                val now = this.project ?: return@button
                val (added, created) = TimelineEditing.addingText(TextOverlay(y = 0.75), editor.playhead, 3.0, now.edit)
                if (created != null) { commit(added, "Text added"); openText(created) }
            },
            Design.button(context, "Done", Design.accent) { save("Text changed"); closePanel() }, spacing = 6f).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
        showPanel(content)
    }

    private fun openExport() {
        val project = project ?: return
        editor.suspend()
        if (project.edit.duration <= 0) return say("Add a recording before previewing or exporting.")
        showPanel(MovieExportPanel(context, store, navigation, project.id) { closePanel() })
    }
}
