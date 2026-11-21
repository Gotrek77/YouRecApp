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
        val bar = Design.row(context, Design.button(context, "‹ Projects", fill = 0) { navigation.showProjects() }, title,
            Design.symbol(context, "⚙", "Editor settings", fill = 0) { openEditorSettings() }, spacing = 4f)
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
    private var shownAudioStatus: String? = null
    private var beforeAudioStatus: CharSequence = ""
    private fun refreshTransport() {
        // Progress takes the message line; when it ends, the message it covered comes back.
        if (editor.audioStatus != shownAudioStatus) {
            val previous = shownAudioStatus; shownAudioStatus = editor.audioStatus
            // A message said while progress showed is the one to bring back.
            if (shownAudioStatus != null) { if (previous == null || feedback.text.toString() != previous) beforeAudioStatus = feedback.text; feedback.text = shownAudioStatus }
            else if (previous != null && feedback.text.toString() == previous) feedback.text = beforeAudioStatus
        }
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
            if (edit.timeline.audio.isNotEmpty()) tool("Enhance") { openEnhance() }
            if (a != null && b != null && edit.timeline.audio.isNotEmpty()) tool("Censor") { openCensor() }
            if (edit.timeline.video.any { item -> project.sources.firstOrNull { it.id == item.content.sourceID }?.isStillPhoto != true }) tool("Pauses") { openPauses() }
            if (edit.timeline.video.isNotEmpty()) { tool("Speed") { openSpeed() }; tool("Crop") { openCrop() }; tool("Mirror") { openMirror() }; tool("Color") { openColor() }; tool("Effects") { effectsScroll = 0; openEffects() } }
            if (edit.timeline.video.isNotEmpty()) tool("Text") { openText(null) }
            if (edit.timeline.video.isNotEmpty()) tool("Transition") { openTransition(null) }
            if (edit.timeline.video.isNotEmpty()) tool("Image") { openImage(null) }
            if (edit.timeline.video.isNotEmpty()) tool("Cover") { openCover() }
            if (edit.timeline.audio.isNotEmpty()) tool("Subtitles") { openSubtitles() }
            if (edit.timeline.compile().any { it.video.size > 1 }) tool("Placement") { openPlacement(null) }
            if (!chosen.isEmpty && edit.timeline.links.any { link -> link.members.any { it in chosen.ids } }) tool("Unlink") { unlink() }
            else if (chosen.ids.size >= 2) tool("Link") { link(false) }
            if (edit.timeline.gap(EditTime.seconds(editor.playhead)) != null) tool("Close gap") { closeGap() }
            if (edit.timeline.resolvedBookmarks.isNotEmpty()) { tool("‹ Mark") { jumpBookmark(false) }; tool("Mark ›") { jumpBookmark(true) } }
        }
        tool("Record") {
            // Inside the edit, new takes go in at the playhead (Record here); at its end, after it.
            val at = TimelineEditing.quantize(editor.playhead)
            if (edit.timeline.video.isNotEmpty() && at < edit.duration - TimelineEditing.frame) store.beginRecordingInsertion(project.id, at)
            navigation.showRecorder(project.id)
        }
        tool("Add video") { addMedia(audioOnly = false) }
        tool("Add photo") { addPhoto() }
        tool("Add audio") { addMedia(audioOnly = true) }
    }

    private fun commit(edit: EditState, message: String, position: Double? = null) {
        val id = projectID ?: return
        position?.let { editor.seek(it) }
        if (store.commit(id, edit)) say("$message · Undo available")
        else {
            // A refused edit must say so where it was made, not only in the project list.
            android.util.Log.w("YouRec", "edit not saved ($message): ${store.error}")
            say(store.error ?: "The change could not be saved.")
        }
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

    /// A transition at the start, at a cut between two pictures, or at the end:
    /// the join nearest the playhead first. Each change is one undo step.
    private fun openTransition(index: Int?) {
        val project = project ?: return
        editor.pause()
        val joins = TransitionLayout.joins(project.edit.timeline).filter { it.selectable }
        if (joins.isEmpty()) return say("There is no cut here to join.")
        val join = joins.firstOrNull { it.index == index } ?: joins.minByOrNull { Math.abs(it.time - editor.playhead) }!!
        val current = join.transition
        val position = joins.indexOf(join)
        if (index == null) editor.seek(join.time)
        fun apply(value: ClipTransition?, message: String) {
            val now = this.project ?: return
            val edit = if (value == null && current != null && join.edge == 0) TransitionLayout.restoringStraightCut(now.edit, join.index) else TransitionLayout.setting(now.edit, value, join.index)
            if (edit != now.edit) commit(edit, message, TransitionLayout.joins(edit.timeline).firstOrNull { it.index == join.index }?.time)
            else if (value != null) say("Not enough footage on both sides for a transition here.")
            openTransition(join.index)
        }
        val where = when (join.edge) { -1 -> "Start"; 1 -> "End"; else -> "Cut " + join.index }
        val content = Design.column(context, Design.row(context,
            Design.button(context, "‹", Design.text, Design.raised) { joins.getOrNull(position - 1)?.let { editor.seek(it.time); openTransition(it.index) } }.apply { contentDescription = "Previous join"; isEnabled = position > 0; alpha = if (isEnabled) 1f else 0.4f },
            Design.label(context, "Transition · $where · " + EditFrames.timestamp(EditTime.seconds(join.time)), 15f, bold = true),
            Design.button(context, "›", Design.text, Design.raised) { joins.getOrNull(position + 1)?.let { editor.seek(it.time); openTransition(it.index) } }.apply { contentDescription = "Next join"; isEnabled = position < joins.size - 1; alpha = if (isEnabled) 1f else 0.4f }),
            spacing = 6f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun pick(title: String, on: Boolean, description: String, action: () -> Unit) =
            Design.button(context, title, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { action() }
                .apply { contentDescription = description + if (on) ", selected" else "" }
        fun slider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, steps: Int, format: String, change: (Double) -> ClipTransition) {
            val label = Design.label(context, String.format(format, value), 14f, mono = true)
            var chosen = value
            add(labelled(title, SeekBar(context).apply {
                max = steps; progress = Math.round((value - range.start) / (range.endInclusive - range.start) * steps).toInt().coerceIn(0, steps)
                contentDescription = title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) { if (user) { chosen = range.start + (range.endInclusive - range.start) * p / steps; label.text = String.format(format, chosen) } }
                    override fun onStartTrackingTouch(b: SeekBar) { }
                    override fun onStopTrackingTouch(b: SeekBar) { apply(change(chosen), "Transition $title") }
                })
            }, label))
        }
        add(choices(*(listOf<TransitionStyle?>(null) + TransitionStyle.values()).map { style ->
            val title = style?.title ?: "None"
            pick(title, current?.style == style, "Transition style $title") {
                apply(style?.let { (current ?: ClipTransition(duration = 0.5)).copy(style = it) }, if (style == null) "Transition removed" else "Transition $title")
            }
        }.toTypedArray()))
        if (current != null) {
            val shown = (join.timing.videoDuration).let { if (it > 0) it else current.duration }
            add(choices(*listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 5.0).map { seconds ->
                pick(ZoomTransition.number(seconds) + " s", Math.abs(current.duration - seconds) < 0.001, "Transition lasts " + ZoomTransition.number(seconds) + " seconds") {
                    apply(current.copy(duration = seconds), "Transition " + ZoomTransition.number(seconds) + " s")
                }
            }.toTypedArray()))
            if (Math.abs(shown - current.duration - current.holdDuration) > 0.01 && current.style != TransitionStyle.Still)
                add(Design.label(context, String.format("Shortened to %.2f s to fit the footage on both sides.", shown), 13f, Design.secondary))
            if (current.style.supportsHold) add(choices(*listOf(0.0, 0.5, 1.0, 2.0, 4.0).map { seconds ->
                pick("Hold " + ZoomTransition.number(seconds) + " s", Math.abs(current.holdDuration - seconds) < 0.001, "Hold at the middle for " + ZoomTransition.number(seconds) + " seconds") {
                    apply(current.copy(hold = if (seconds == 0.0) null else seconds), "Transition hold")
                }
            }.toTypedArray()))
            if (current.style == TransitionStyle.FadeColor || join.edge != 0) {
                val colours = listOf("Black" to TransitionColor.black, "White" to TransitionColor(1.0, 1.0, 1.0), "Warm" to TransitionColor(0.96, 0.72, 0.45), "Blue" to TransitionColor(0.12, 0.25, 0.55))
                add(choices(*colours.map { (title, colour) ->
                    pick(title, current.resolvedFadeColor == colour, "Fade colour $title") { apply(current.copy(fadeColor = if (colour == TransitionColor.black) null else colour), "Fade colour $title") }
                }.toTypedArray()))
            }
            when (current.style) {
                TransitionStyle.Wipe -> {
                    val angle = TransitionMix.angle(current.copy(reverse = false))
                    add(choices(*listOf("→" to 0.0, "↘" to 45.0, "↓" to 90.0, "↙" to 135.0, "←" to 180.0, "↖" to -135.0, "↑" to -90.0, "↗" to -45.0).map { (glyph, degrees) ->
                        pick(glyph, Math.abs(((angle - degrees) % 360 + 360) % 360) < 0.5 && !current.reverse, "Wipe direction $degrees degrees") { apply(current.copy(wipeAngle = degrees, reverse = false), "Wipe direction") }
                    }.toTypedArray()))
                    add(choices(pick("Soft edge", current.hardEdge != true, "Soft wipe edge") { apply(current.copy(hardEdge = null), "Wipe edge") },
                        pick("Hard edge", current.hardEdge == true, "Hard wipe edge") { apply(current.copy(hardEdge = true), "Wipe edge") }))
                }
                TransitionStyle.Split, TransitionStyle.Whip -> {
                    add(choices(pick("Left / right", current.axis == TransitionAxis.Horizontal, "Axis left and right") { apply(current.copy(axis = TransitionAxis.Horizontal), "Transition axis") },
                        pick("Top / bottom", current.axis == TransitionAxis.Vertical, "Axis top and bottom") { apply(current.copy(axis = TransitionAxis.Vertical), "Transition axis") },
                        pick("Reverse", current.reverse, "Reverse direction") { apply(current.copy(reverse = !current.reverse), "Transition direction") }))
                    if (current.style == TransitionStyle.Split) {
                        slider("Divider", current.divider, 0.15..0.85, 70, "%.2f") { current.copy(divider = it) }
                        slider("Zoom 1", current.first.zoom, 1.0..4.0, 30, "%.1f×") { current.copy(first = current.first.copy(zoom = it)) }
                        slider("Zoom 2", current.second.zoom, 1.0..4.0, 30, "%.1f×") { current.copy(second = current.second.copy(zoom = it)) }
                        add(choices(*TransitionAudio.values().map { audio ->
                            val title = when (audio) { TransitionAudio.Crossfade -> "Crossfade"; TransitionAudio.First -> "First clip's sound"; TransitionAudio.Second -> "Second clip's sound" }
                            pick(title, current.audio == audio, "Split sound $title") { apply(current.copy(audio = audio), "Split sound") }
                        }.toTypedArray()))
                    }
                }
                TransitionStyle.Hitchcock -> add(choices(pick("Shadows first", current.highlightsFirst != true, "Shadows first") { apply(current.copy(highlightsFirst = null), "Hitchcock order") },
                    pick("Highlights first", current.highlightsFirst == true, "Highlights first") { apply(current.copy(highlightsFirst = true), "Hitchcock order") }))
                TransitionStyle.FlashCut -> {
                    slider("Flashes", (current.flashCount ?: 3).toDouble(), 1.0..20.0, 19, "%.0f") { current.copy(flashCount = Math.round(it).toInt()) }
                    slider("Accelerate", current.flashAcceleration ?: 2.0, 0.25..4.0, 15, "%.2f") { current.copy(flashAcceleration = it) }
                }
                TransitionStyle.Still -> add(choices(*listOf(0.0, 0.25, 0.5, 1.0).map { seconds ->
                    pick(if (seconds == 0.0) "Cut" else "Dissolve " + ZoomTransition.number(seconds) + " s", Math.abs((current.stillDissolve ?: 0.0) - seconds) < 0.001, "Still ends with " + if (seconds == 0.0) "a cut" else "a dissolve of $seconds seconds") {
                        apply(current.copy(stillDissolve = if (seconds == 0.0) null else seconds), "Still ending")
                    }
                }.toTypedArray()))
                TransitionStyle.Defocus -> slider("Blur", current.blurAmount ?: 0.75, 0.0..1.0, 20, "%.2f") { current.copy(blurAmount = it) }
                else -> { }
            }
            // Sound can change before (J cut) or after (L cut) the picture, over its own length.
            slider("Sound shift", current.audioOffset ?: 0.0, -2.0..2.0, 80, "%+.2f s") { current.copy(audioOffset = it) }
            slider("Sound blend", current.audioBlend ?: current.defaultAudioBlend, 0.0..3.0, 60, "%.2f s") { current.copy(audioBlend = it) }
            if (current.customAudio) add(Design.button(context, "Sound with the picture", size = 13f) { apply(current.copy(audioOffset = null, audioBlend = null), "Transition sound") })
        }
        add(Design.button(context, "Done", Design.accent) { closePanel() }.apply { contentDescription = "Done with transition" })
        showPanel(content)
    }

    /// A picture over the video, timed and placed like a title. A new one is
    /// picked from the phone, kept in the project folder, and starts at the playhead.
    private fun openImage(chosen: UUID?) {
        val project = project ?: return
        editor.pause()
        val edit = project.edit
        val id = chosen ?: TimelineEditing.imagesAt(editor.playhead, edit).lastOrNull()?.id
        if (id == null) {
            if (TimelineEditing.pictureAt(editor.playhead, edit) == null) return say("Move the playhead onto a video to add an image.")
            val at = editor.playhead; val projectID = project.id
            navigation.pickMedia("image/*") { uri ->
                if (uri == null) return@pickMedia
                say("Adding image…")
                store.importOverlayImage(uri, projectID) { filename, failure ->
                    if (filename == null) return@importOverlayImage say(failure ?: "The image could not be added.")
                    val now = this.project ?: return@importOverlayImage
                    val (added, created) = TimelineEditing.addingImage(ImageOverlay(filename = filename), at, 3.0, now.edit)
                    if (created == null) return@importOverlayImage say("Move the playhead onto a video to add an image.")
                    commit(added, "Image added"); openImage(created)
                }
            }
            return
        }
        val owner = edit.timeline.video.firstOrNull { item -> item.content.images?.any { it.id == id } == true } ?: return closePanel()
        var image = owner.content.images!!.first { it.id == id }
        val length = project.sources.firstOrNull { it.id == owner.content.sourceID }?.duration ?: owner.content.end
        fun preview() { this.project?.let { editor.show(TimelineEditing.updatingImage(image, it.edit)) } }
        fun save(message: String, reopen: Boolean = false) {
            val now = this.project ?: return
            val changed = TimelineEditing.updatingImage(image, now.edit)
            if (changed != now.edit) commit(changed, message)
            if (reopen) openImage(id)
        }
        val content = Design.column(context, Design.label(context, "Image", 15f, bold = true), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val values = List(4) { Design.label(context, "", 14f, mono = true) }
        fun describe() {
            values[0].text = String.format("%.0f%%", image.width * 100); values[1].text = String.format("%.0f%%", image.x * 100)
            values[2].text = String.format("%.0f%%", image.y * 100); values[3].text = String.format("%.0f%%", image.opacity * 100)
        }
        fun bar(max: Int, start: Int, update: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) { if (user) { update(value); describe(); preview() } }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { save("Image changed") }
            })
        }
        describe()
        // Size from 3% to 300% of the shorter side of the frame.
        add(labelled("Size", bar(297, Math.round(image.width * 100).toInt() - 3) { image = image.copy(width = (it + 3) / 100.0) }, values[0]))
        add(labelled("Across", bar(100, Math.round(image.x * 100).toInt()) { image = image.copy(x = it / 100.0) }, values[1]))
        add(labelled("Down", bar(100, Math.round(image.y * 100).toInt()) { image = image.copy(y = it / 100.0) }, values[2]))
        add(labelled("Opacity", bar(100, Math.round(image.opacity * 100).toInt()) { image = image.copy(opacity = it / 100.0) }, values[3]))
        val rate = owner.content.rate
        val lasting = (image.end - image.start) / rate
        add(choices(*EditorDurationPresets.wholeSeconds.filter { image.start + it * rate <= length + 0.001 || it == 1.0 }.map { seconds ->
            val on = Math.abs(lasting - seconds) < 0.02
            Design.button(context, ZoomTransition.number(seconds) + " s", if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) {
                image = image.copy(end = minOf(length, image.start + seconds * rate)); save("Image duration", reopen = true)
            }.apply { contentDescription = "Image on screen for " + ZoomTransition.number(seconds) + " seconds" + if (on) ", selected" else "" }
        }.toTypedArray()))
        add(Design.row(context,
            Design.button(context, "Remove", Design.recording, size = 13f) {
                val now = this.project ?: return@button
                commit(TimelineEditing.removingImage(id, now.edit), "Image removed"); closePanel()
            }.apply { contentDescription = "Remove image" },
            Design.button(context, "Done", Design.accent) { save("Image changed"); closePanel() }.apply { contentDescription = "Done with image" }, spacing = 6f).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
        showPanel(content)
    }

    /// Subtitles from the project's own sound with the iPhone app's speech model
    /// (Qwen3-ASR-0.6B through antirez's qwen-asr), downloaded once on request.
    /// Transcription covers the marked range or the whole project; captions can
    /// be regrouped, styled, corrected or removed, each one undo step.
    private var transcribing: Thread? = null
    private fun openSubtitles() {
        val project = project ?: return
        editor.pause()
        val root = NeuralAssets.folder ?: return say("The speech model has no place to be stored.")
        val track = project.edit.timeline
        val settings = track.subtitles ?: SubtitleSettings()
        val content = Design.column(context, Design.label(context, "Subtitles", 15f, bold = true), spacing = 6f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val status = Design.label(context, "", 13f, Design.secondary)
        fun change(message: String, apply: (TrackTimeline) -> Unit) {
            val now = this.project ?: return
            val edit = now.edit.copy(); apply(edit.timeline)
            if (edit != now.edit) commit(edit, message)
            openSubtitles()
        }
        fun pick(title: String, on: Boolean, action: () -> Unit) = Design.button(context, title, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { action() }
            .apply { contentDescription = title + if (on) ", selected" else "" }
        if (!SubtitleModelPack.installed(root)) {
            add(Design.label(context, "Subtitles use a speech model that runs on this phone. Download it once (${SubtitleModelPack.byteCount / 1_000_000} MB) from Hugging Face; no audio is sent anywhere.", 13f, Design.secondary))
            add(Design.button(context, "Download speech model", Design.background, Design.accent) {
                if (transcribing != null) return@button
                status.text = "Downloading speech model…"
                transcribing = Thread {
                    val failure = try { SubtitleModelPack.download(root, { value -> post { status.text = "Downloading speech model " + (value * 100).toInt() + "%" } }, { !isAttachedToWindow }); null }
                        catch (e: Exception) { e.message ?: "The speech model could not be downloaded. Please retry." }
                    post { transcribing = null; if (failure != null) status.text = failure else openSubtitles() }
                }.also { it.start() }
            }.apply { contentDescription = "Download speech model" })
            add(status); add(Design.button(context, "Done") { closePanel() }); showPanel(content); return
        }
        // The language picked in this panel, else the project's.
        val language = openSubtitlesLanguage ?: settings.language
        add(choices(*SubtitleTranscriber.languages.map { name -> pick(name, name == language) { openSubtitlesLanguage = name; openSubtitles() } }.toTypedArray()))
        val a = timeline.rangeStart; val b = timeline.rangeEnd
        val range = if (a != null && b != null) minOf(a, b)..maxOf(a, b) else 0.0..track.duration.seconds
        add(Design.button(context, if (a != null && b != null) "Transcribe marked range" else "Transcribe whole project", Design.background, Design.accent) {
            if (transcribing != null) return@button
            val now = this.project ?: return@button
            val folder = store.folder(now)
            val chosen = language
            status.text = "Reading the sound…"
            transcribing = Thread {
                val scratch = java.io.File(OfflineAudioPipeline.cacheFolder, "subtitles-" + UUID.randomUUID() + ".f32")
                val result = try {
                    OfflineAudioPipeline.cacheFolder.mkdirs()
                    val sources = RenderSources(now, folder)
                    try {
                        sources.prepareAudio(now.edit.timeline)
                        val mix = AudioMix(now.edit.timeline, { id, effects -> sources.processed(id, effects) }) { sources.sound(it) }
                        val levels = SubtitleTranscriber.extract(mix, range, scratch, { value -> post { status.text = "Reading the sound " + (value * 100).toInt() + "%" } }, { !isAttachedToWindow })
                        post { status.text = "Loading speech model…" }
                        SpeechModel.open(SubtitleModelPack.directory(root)).use { model ->
                            SubtitleTranscriber.transcribe(scratch, levels, range, chosen, model, { _, message -> post { status.text = message } }, { !isAttachedToWindow })
                        }
                    } finally { sources.releaseSound() }
                } catch (e: Exception) { e } finally { scratch.delete() }
                post {
                    transcribing = null
                    @Suppress("UNCHECKED_CAST")
                    if (result is Exception) status.text = result.message ?: "The transcription failed. Your previous subtitles are unchanged."
                    else change("Subtitles made") { SubtitleEditing.install(it, result as List<SubtitleWord>, range, chosen) }
                }
            }.also { it.start() }
        }.apply { contentDescription = "Transcribe subtitles" })
        add(status)
        if (track.subtitles != null) {
            if (SubtitleEditing.groupingAvailable(track))
                add(labelled("Words", SeekBar(context).apply {
                    max = 15; progress = settings.maxWords - 1
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { }
                        override fun onStartTrackingTouch(bar: SeekBar) { }
                        override fun onStopTrackingTouch(bar: SeekBar) { change("Caption length") { SubtitleEditing.regroup(it, bar.progress + 1) } }
                    })
                }, Design.label(context, "${settings.maxWords}", 14f, mono = true)))
            else add(Design.label(context, "The edit changed since transcription: transcribe again to change caption length.", 12f, Design.secondary))
            add(choices(*TextStyle.values().map { style -> pick(style.name, style == settings.style) { change("Subtitle style") { it.subtitles = it.subtitles?.copy(style = style) } } }.toTypedArray()))
            add(choices(*TextColor.values().map { color -> pick(color.name, color == settings.color) { change("Subtitle colour") { it.subtitles = it.subtitles?.copy(color = color) } } }.toTypedArray()))
            add(choices(pick("Small", settings.size < 0.045) { change("Subtitle size") { it.subtitles = it.subtitles?.copy(size = 0.04) } },
                pick("Medium", settings.size in 0.045..0.06) { change("Subtitle size") { it.subtitles = it.subtitles?.copy(size = 0.052) } },
                pick("Large", settings.size > 0.06) { change("Subtitle size") { it.subtitles = it.subtitles?.copy(size = 0.07) } },
                pick("Bottom", settings.y > 0.7) { change("Subtitle position") { it.subtitles = it.subtitles?.copy(y = 0.86) } },
                pick("Middle", settings.y in 0.3..0.7) { change("Subtitle position") { it.subtitles = it.subtitles?.copy(y = 0.5) } },
                pick("Top", settings.y < 0.3) { change("Subtitle position") { it.subtitles = it.subtitles?.copy(y = 0.14) } }))
            val cues = SubtitleEditing.cues(track)
            add(Design.label(context, "${cues.size} caption" + (if (cues.size == 1) "" else "s") + ". Tap one to correct it.", 13f, Design.secondary))
            val list = Design.column(context, spacing = 2f)
            for (cue in cues.take(200)) {
                list.addView(Design.button(context, EditFrames.timestamp(EditTime.seconds(cue.start)) + "  " + cue.text, Design.text, Design.raised, 13f) {
                    editor.seek(cue.start)
                    val field = android.widget.EditText(context).apply { setText(cue.text); setSelection(text.length) }
                    android.app.AlertDialog.Builder(context).setTitle("Correct caption").setView(field).setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ -> change("Caption corrected") { SubtitleEditing.correct(it, cue, field.text.toString()) } }.show()
                }.apply { contentDescription = "Caption " + cue.text })
            }
            add(android.widget.ScrollView(context).apply { addView(list) }.also { it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(context, 160f)) })
            add(Design.button(context, "Remove subtitles", Design.recording) { change("Subtitles removed") { SubtitleEditing.remove(it) } }.apply { contentDescription = "Remove subtitles" })
        }
        add(Design.button(context, "Done") { openSubtitlesLanguage = null; closePanel() }.apply { contentDescription = "Done with subtitles" })
        showPanel(content)
    }
    private var openSubtitlesLanguage: String? = null

    /// Replace the marked sound with a tone: the selected sound parts, or all
    /// sound in the range when none is selected. Apply is one undo step.
    private fun openCensor() {
        val project = project ?: return
        val a = timeline.rangeStart ?: return; val b = timeline.rangeEnd ?: return
        editor.pause()
        val range = TimelineEditing.rangeSelection(minOf(a, b), maxOf(a, b), project.edit)?.range ?: return
        val chosen = selection(project.edit).audio
        val audio = project.edit.timeline.audio.filter { (chosen.isEmpty() || it.id in chosen) && it.start < range.end && range.start < it.end }.map { it.id }.toSet()
        if (audio.isEmpty()) return say("Move the range over sound, or select a sound part inside it.")
        val target = TimelineSelection(audio = audio, range = range)
        val existing = project.edit.timeline.audio.filter { it.id in audio }.flatMap { it.censors }
            .firstOrNull { it.interval.start < range.end && range.start < it.interval.end }
        var settings = existing?.settings ?: AudioCensorSettings()
        val values = List(2) { Design.label(context, "", 14f, mono = true) }
        fun describe() { values[0].text = String.format("%.0f Hz", settings.frequency); values[1].text = String.format("%.0f dB", settings.levelDB) }
        fun bar(max: Int, start: Int, change: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) { if (user) { change(value); describe() } }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { }
            })
        }
        describe()
        val content = Design.column(context, Design.label(context, "Censor audio · " + EditFrames.timestamp(range.start) + " – " + EditFrames.timestamp(range.end), 15f, bold = true),
            Design.label(context, if (chosen.isEmpty()) "All sound in the marked range is replaced by a tone." else "The selected sound is replaced by a tone; other tracks keep playing.", 13f, Design.secondary), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lateinit var refresh: () -> Unit
        val waves = LinearLayout(context); val envelopes = LinearLayout(context)
        refresh = {
            waves.removeAllViews(); envelopes.removeAllViews()
            for (wave in CensorWaveform.values()) {
                val on = settings.waveform == wave; val title = wave.name
                waves.addView(Design.button(context, title, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { settings = settings.copy(waveform = wave); refresh() }
                    .apply { contentDescription = "Waveform $title" + if (on) ", selected" else "" }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Design.dp(context, 6f) })
            }
            for ((envelope, title) in listOf(CensorEnvelope.Short to "Short fade", CensorEnvelope.Soft to "Soft fade", CensorEnvelope.Pulse to "Pulsed")) {
                val on = settings.envelope == envelope
                envelopes.addView(Design.button(context, title, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) { settings = settings.copy(envelope = envelope); refresh() }
                    .apply { contentDescription = "Envelope $title" + if (on) ", selected" else "" }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Design.dp(context, 6f) })
            }
        }
        refresh()
        add(waves); add(envelopes)
        // Frequency 100–4000 Hz in 25 Hz steps; level −36 to −6 dBFS.
        add(labelled("Pitch", bar(156, Math.round((settings.frequency - 100) / 25).toInt()) { settings = settings.copy(frequency = 100.0 + it * 25) }, values[0]))
        add(labelled("Level", bar(30, Math.round(settings.levelDB + 36).toInt()) { settings = settings.copy(levelDB = it - 36.0) }, values[1]))
        val actions = Design.row(context, Design.button(context, "Apply", Design.background, Design.accent) {
            val now = this.project ?: return@button
            val edit = now.edit.copy()
            AudioCensor.censor(edit.timeline, settings, target, existing?.id ?: UUID.randomUUID())
            timeline.rangeStart = null; timeline.rangeEnd = null
            closePanel()
            if (edit != now.edit) commit(edit, "Censored")
        }.apply { contentDescription = "Apply censor" })
        if (existing != null) actions.addView(Design.button(context, "Remove censor", Design.recording) {
            val now = this.project ?: return@button
            val edit = now.edit.copy()
            AudioCensor.censor(edit.timeline, null, target)
            timeline.rangeStart = null; timeline.rangeEnd = null
            closePanel()
            if (edit != now.edit) commit(edit, "Censor removed")
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        actions.addView(Design.button(context, "Cancel") { closePanel() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        add(actions.apply { setPadding(0, Design.dp(context, 8f), 0, 0) })
        showPanel(content)
    }

    /// A chain of filters over the selected sound, the marked range of it, or
    /// all sound: noise reduction, low-cut, EQ and gain, in any order. The
    /// whole source is processed once per chain; Apply is one undo step.
    private fun openEnhance() {
        val project = project ?: return
        editor.pause()
        val a = timeline.rangeStart; val b = timeline.rangeEnd
        val range = if (a != null && b != null) TimelineEditing.rangeSelection(minOf(a, b), maxOf(a, b), project.edit)?.range else null
        val chosen = selection(project.edit).audio
        val audio = project.edit.timeline.audio.filter { (chosen.isEmpty() || it.id in chosen) && (range == null || (it.start < range.end && range.start < it.end)) }.map { it.id }.toSet()
        if (audio.isEmpty()) return say("Select a sound part, or mark a range over sound.")
        val target = TimelineSelection(audio = audio, range = range)
        val existing = project.edit.timeline.audio.filter { it.id in audio }.flatMap { item -> item.enhancements.map { item to it } }
            .firstOrNull { (item, region) -> range == null || (item.start + region.interval.start < range.end && range.start < item.start + region.interval.end) }?.second
        var chain = existing?.effects ?: emptyList()
        val scope = (if (range != null) EditFrames.timestamp(range.start) + " – " + EditFrames.timestamp(range.end) else "whole parts") +
            " · " + (if (chosen.isEmpty()) "all sound" else "${audio.size} sound part" + if (audio.size == 1) "" else "s")
        val content = Design.column(context, Design.label(context, "Enhance audio · $scope", 15f, bold = true), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val stages = Design.column(context, spacing = 6f)
        lateinit var refresh: () -> Unit
        val status = Design.label(context, "", 13f, Design.secondary)
        var downloading = false
        add(choices(*listOf(EffectKind.voiceIsolation, EffectKind.studioVoice, EffectKind.studioRestoration, EffectKind.highPass, EffectKind.equalizer, EffectKind.gain)
            .filter { StudioAssets.carries(it) || StudioAssets.downloadable(it) }.map { kind ->
            val here = StudioAssets.carries(kind)
            val size = StudioModelPack.entry(kind)?.let { " ↓ " + (it.bytes + 500_000) / 1_000_000 + " MB" } ?: ""
            Design.button(context, "+ " + kind.stripName + if (here) "" else size, Design.text, Design.raised, 13f) {
                if (StudioAssets.carries(kind)) { chain = chain + AudioEffect.make(kind); refresh(); return@button }
                if (downloading) return@button
                // Only the network is downloaded, once, from the published model files; nothing is sent.
                android.app.AlertDialog.Builder(context).setTitle("Download ${kind.title}?")
                    .setMessage("This build does not include its network. Download it once (${size.removePrefix(" ↓ ")}) from Hugging Face? It is checked before use and works offline afterwards. No audio or project data is sent.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download") { _, _ ->
                        downloading = true; status.text = "Downloading " + kind.stripName + "…"
                        Thread {
                            val failure = try {
                                StudioAssets.download(kind, { value -> post { status.text = "Downloading " + kind.stripName + " " + (value * 100).toInt() + "%" } }, { !isAttachedToWindow }); null
                            } catch (e: Exception) { e.message ?: "The model could not be downloaded." }
                            post {
                                downloading = false
                                if (failure != null) status.text = failure
                                else { status.text = kind.title + " is ready."; chain = chain + AudioEffect.make(kind); refresh() }
                            }
                        }.start()
                    }.show()
            }.apply { contentDescription = "Add " + kind.title + if (here) "" else ", download" }
        }.toTypedArray()))
        add(status)
        add(stages)
        fun bar(start: Double, change: (Double) -> Unit) = SeekBar(context).apply {
            max = 1000; progress = Math.round(start * 1000).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) { if (user) change(value / 1000.0) }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { }
            })
        }
        fun replace(effect: AudioEffect) { chain = chain.map { if (it.id == effect.id) effect else it } }
        refresh = {
            stages.removeAllViews()
            if (chain.isEmpty()) stages.addView(Design.label(context, "Add a filter above, in any order." + if (listOf(EffectKind.studioVoice, EffectKind.studioRestoration).all { StudioAssets.carries(it) || StudioAssets.downloadable(it) }) "" else " This build lacks the LavaSR or VoiceFixer network (see scripts/neural).", 13f, Design.secondary))
            for ((index, effect) in chain.withIndex()) {
                val head = Design.row(context, Design.label(context, "${index + 1} · " + effect.kind.title, 14f, bold = true), spacing = 4f)
                (head.getChildAt(0).layoutParams as LayoutParams).apply { width = 0; weight = 1f }
                head.addView(Design.button(context, if (effect.enabled) "On" else "Bypassed", if (effect.enabled) Design.background else Design.text,
                    if (effect.enabled) Design.accent else Design.raised, 13f) { replace(effect.copy(enabled = !effect.enabled)); refresh() }
                    .apply { contentDescription = effect.kind.title + if (effect.enabled) " enabled" else " bypassed" })
                head.addView(Design.symbol(context, "←", "Move effect earlier", fill = 0) {
                    if (index > 0) { chain = chain.toMutableList().apply { add(index - 1, removeAt(index)) }; refresh() } })
                head.addView(Design.symbol(context, "→", "Move effect later", fill = 0) {
                    if (index + 1 < chain.size) { chain = chain.toMutableList().apply { add(index + 1, removeAt(index)) }; refresh() } })
                head.addView(Design.symbol(context, "✕", "Remove effect", fill = 0) { chain = chain.filter { it.id != effect.id }; refresh() })
                stages.addView(head)
                if (!effect.available) {
                    stages.addView(Design.label(context, if (effect.supported) "This filter uses a model YouRec for Android cannot run yet. Bypass or remove it to process audio; its saved settings are retained."
                        else "This filter is unavailable in this app version. Bypass or remove it to process audio; its saved settings are retained.", 13f, Design.recording))
                    continue
                }
                val value = Design.label(context, effect.primaryText, 14f, mono = true)
                stages.addView(labelled(effect.primaryLabel, bar(effect.normalizedPrimary) { n ->
                    val next = chain.first { it.id == effect.id }.settingPrimary(n); replace(next); value.text = next.primaryText }, value))
                if (effect.kind == EffectKind.voiceIsolation) {
                    // As on iPhone: RNNoise by default, DeepFilterNet3 on request; the project keeps the choice.
                    stages.addView(Design.row(context, *listOf(AudioModel.rnnoise to "RNNoise", AudioModel.deepFilter to "DeepFilterNet3").map { (model, name) ->
                        val on = effect.model == model
                        Design.button(context, name, if (on) Design.background else Design.text, if (on) Design.accent else Design.raised, 13f) {
                            replace(chain.first { it.id == effect.id }.copy(model = model)); refresh()
                        }.apply { contentDescription = "Noise reduction model $name" + if (on) ", selected" else "" }
                    }.toTypedArray(), spacing = 6f))
                }
                if (effect.kind == EffectKind.equalizer) {
                    val band = Design.label(context, effect.frequencyText, 14f, mono = true)
                    stages.addView(labelled("Frequency", bar(AudioEffect.equalizerPosition(effect.frequency)) { n ->
                        val next = chain.first { it.id == effect.id }.copy(frequency = AudioEffect.equalizerFrequency(n)); replace(next); band.text = next.frequencyText }, band))
                }
                stages.addView(Design.label(context, when (effect.kind) {
                    EffectKind.voiceIsolation -> if (effect.model == AudioModel.deepFilter) "DeepFilterNet3 reduces background noise more strongly and is slower to process. Amount blends the cleaned voice with the recording."
                        else "RNNoise reduces background noise. Amount blends the cleaned voice with the recording."
                    EffectKind.studioVoice -> "LavaSR restores high-frequency speech detail. It does not simulate a studio microphone or remove room echo. Start low: clean recordings may need none."
                    EffectKind.studioRestoration -> "VoiceFixer restores the full speech signal, reducing recording defects such as noise, room echo and muffling. Amount blends the restored voice with the recording. Neural resynthesis can alter vocal character: for speech, not music; compare with Bypass. VoiceFixer · Haohe Liu et al. · CC BY 4.0"
                    EffectKind.highPass -> "Removes low rumble below the cutoff."
                    EffectKind.equalizer -> "Boosts or cuts this frequency. Add another EQ stage for another band."
                    else -> "Adjusts the output level in decibels."
                }, 12f, Design.secondary))
            }
        }
        refresh()
        val actions = Design.row(context, Design.button(context, "Apply", Design.background, Design.accent) {
            val now = this.project ?: return@button
            if (chain.isEmpty() && existing == null) return@button say("Add a filter first.")
            val edit = now.edit.copy()
            edit.timeline.enhance(chain, target, existing?.id ?: UUID.randomUUID())
            android.util.Log.w("YouRec", "enhance: ${chain.size} stages on ${audio.size} sound parts, changed ${edit != now.edit}")
            timeline.rangeStart = null; timeline.rangeEnd = null
            closePanel()
            if (edit != now.edit) commit(edit, if (chain.isEmpty()) "Enhance audio removed" else "Audio enhanced")
        }.apply { contentDescription = "Apply enhance audio" })
        if (existing != null) actions.addView(Design.button(context, "Remove", Design.recording) {
            val now = this.project ?: return@button
            val edit = now.edit.copy()
            edit.timeline.enhance(emptyList(), target)
            timeline.rangeStart = null; timeline.rangeEnd = null
            closePanel()
            if (edit != now.edit) commit(edit, "Enhance audio removed")
        }.apply { contentDescription = "Remove enhance audio" }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        actions.addView(Design.button(context, "Cancel") { closePanel() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        add(actions.apply { setPadding(0, Design.dp(context, 8f), 0, 0) })
        showPanel(content)
    }

    /// Find silence in the selected clips, or in all, and shorten it. Each pause
    /// found can be kept or cut; Apply is one undo step and Remove restores the media.
    private var pauseLevels = HashMap<UUID, SourceLevels>()
    private fun openPauses(settings: PauseSettings? = null, found: Map<UUID, List<DetectedPause>>? = null) {
        val project = project ?: return
        editor.pause()
        val clips = targets(project.edit).filter { item -> project.sources.firstOrNull { it.id == item.content.sourceID }?.isStillPhoto != true }
        if (clips.isEmpty()) return
        var current = settings ?: clips.firstNotNullOfOrNull { it.content.pauses }?.copy(cuts = emptyList(), candidates = null) ?: PauseSettings()
        val values = List(4) { Design.label(context, "", 14f, mono = true) }
        fun describe() {
            values[0].text = if (current.levelMode == PauseLevelMode.Automatic) "Auto" else String.format("%.0f dB", current.thresholdDB)
            values[1].text = String.format("%.1f s", current.minimum); values[2].text = String.format("%.2f s", current.before); values[3].text = String.format("%.2f s", current.after)
        }
        fun bar(max: Int, start: Int, change: (Int) -> Unit) = SeekBar(context).apply {
            this.max = max; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) { if (user) { change(value); describe() } }
                override fun onStartTrackingTouch(b: SeekBar) { }
                override fun onStopTrackingTouch(b: SeekBar) { }
            })
        }
        describe()
        val content = Design.column(context, Design.label(context, "Pauses · " + scopeTitle(project.edit), 15f, bold = true), spacing = 2f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val automatic = current.levelMode == PauseLevelMode.Automatic
        add(Design.row(context, Design.button(context, if (automatic) "Level: automatic" else "Level: manual", if (automatic) Design.background else Design.text, if (automatic) Design.accent else Design.raised, 13f) {
            openPauses(current.copy(levelMode = if (automatic) PauseLevelMode.Manual else PauseLevelMode.Automatic), null)
        }.apply { contentDescription = "Silence level " + if (automatic) "automatic" else "manual" }))
        // Level −60 to −20 dB; pause 0.2–3 s in tenths; padding 0–0.5 s in hundredths.
        if (!automatic) add(labelled("Silence", bar(40, Math.round(current.thresholdDB + 60).toInt()) { current = current.copy(thresholdDB = it - 60.0) }, values[0]))
        add(labelled("Longer than", bar(28, Math.round(current.minimum * 10 - 2).toInt()) { current = current.copy(minimum = (it + 2) / 10.0) }, values[1]))
        add(labelled("Keep before", bar(50, Math.round(current.before * 100).toInt()) { current = current.copy(before = it / 100.0) }, values[2]))
        add(labelled("Keep after", bar(50, Math.round(current.after * 100).toInt()) { current = current.copy(after = it / 100.0) }, values[3]))
        val actions = Design.row(context)
        if (found == null) {
            actions.addView(Design.button(context, "Find pauses", Design.background, Design.accent) {
                say("Listening for pauses…")
                val chosen = current
                val files = clips.associate { it.content.sourceID to project.sources.first { s -> s.id == it.content.sourceID }.let { store.mediaFile(project, it) } }
                Thread {
                    val results = HashMap<UUID, List<DetectedPause>>()
                    var failure: String? = null
                    try {
                        for (item in clips) {
                            val levels = synchronized(pauseLevels) { pauseLevels[item.content.sourceID] } ?: SourceMediaCache.levels(files[item.content.sourceID]!!).also { synchronized(pauseLevels) { pauseLevels[item.content.sourceID] = it } }
                            results[item.id] = PauseDetector.raw(levels, item.content.copy(pauses = null), chosen).map { DetectedPause(it) }
                        }
                    } catch (e: Exception) { failure = "The sound could not be read. " + (e.message ?: "") }
                    post { if (failure != null) say(failure!!) else { say(""); openPauses(chosen, results) } }
                }.start()
            })
        } else {
            val total = clips.sumOf { item -> PauseDetector.effectiveCuts(found[item.id] ?: emptyList(), item.content.copy(pauses = null), current).sumOf { it.duration } / item.content.rate }
            val count = found.values.sumOf { list -> list.count { it.enabled } }
            add(Design.label(context, if (found.values.all { it.isEmpty() }) "No pauses found. Try a longer silence level or a shorter pause." else
                String.format("%d of %d pauses cut · %.1f s shorter. Tap a pause to keep it.", count, found.values.sumOf { it.size }, total), 13f, Design.secondary))
            val list = LinearLayout(context).apply { orientation = VERTICAL }
            for (item in clips) for ((index, pause) in (found[item.id] ?: emptyList()).withIndex()) {
                val at = item.start.seconds + item.content.copy(pauses = null).timelineTime(pause.raw.start)
                val label = EditFrames.timestamp(EditTime.seconds(at)) + String.format(" · %.1f s", pause.raw.duration / item.content.rate) + if (pause.enabled) " · cut" else " · kept"
                list.addView(Design.button(context, label, if (pause.enabled) Design.text else Design.secondary, Design.raised, 13f) {
                    val toggled = found.toMutableMap(); toggled[item.id] = found[item.id]!!.mapIndexed { i, p -> if (i == index) p.copy(enabled = !p.enabled) else p }
                    openPauses(current, toggled)
                }.apply { contentDescription = "Pause at " + label }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Design.dp(context, 4f) })
            }
            if (list.childCount > 0) add(android.widget.ScrollView(context).apply { addView(list) }.also { it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(context, 160f)) })
            actions.addView(Design.button(context, "Apply", Design.background, Design.accent) {
                val now = this.project ?: return@button
                val edit = now.edit.copy()
                edit.timeline.replaceContents(clips.filter { item -> edit.timeline.video.any { it.id == item.id } }.associate { item ->
                    val plain = item.content.copy(pauses = null)
                    val candidates = found[item.id] ?: emptyList()
                    item.id to item.content.copy(pauses = current.copy(cuts = PauseDetector.effectiveCuts(candidates, plain, current), candidates = candidates,
                        analyzed = SourceInterval(plain.start, plain.end)))
                })
                closePanel()
                if (edit != now.edit) commit(edit, "Pauses cut")
            }.apply { contentDescription = "Apply pause cuts" })
            actions.addView(Design.button(context, "Find again") { openPauses(current, null) }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        }
        if (clips.any { it.content.pauses != null }) actions.addView(Design.button(context, "Remove", Design.recording) {
            val now = this.project ?: return@button
            val edit = now.edit.copy()
            edit.timeline.replaceContents(clips.filter { item -> edit.timeline.video.any { it.id == item.id } }.associate { it.id to it.content.copy(pauses = null) })
            closePanel()
            if (edit != now.edit) commit(edit, "Pauses restored")
        }.apply { contentDescription = "Remove pause cuts" }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        actions.addView(Design.button(context, "Cancel") { closePanel() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        add(android.widget.HorizontalScrollView(context).apply { addView(actions); setPadding(0, Design.dp(context, 8f), 0, 0) })
        showPanel(content)
    }

    private fun openEditorSettings() {
        val on = EditingPreferences.photosFollow
        showPanel(Design.column(context, Design.label(context, "Editor settings", 15f, bold = true),
            Design.button(context, "Photos follow the edit  ·  " + if (on) "On" else "Off", if (on) Design.accent else Design.text) {
                EditingPreferences.photosFollow = !on
                context.getSharedPreferences("yourec", android.content.Context.MODE_PRIVATE).edit().putBoolean("photosFollow", !on).apply()
                openEditorSettings()
            }.apply { contentDescription = "Photos follow the edit, " + if (on) "on" else "off" },
            Design.label(context, "Off, as on iPhone: a photo keeps its place when a clip before it gets shorter (Pauses, Speed, a transition), and a gap opens. On: photos move up with the clips before them. Projects are the same either way.", 13f, Design.secondary),
            Design.button(context, "Done", Design.accent) { closePanel() }, spacing = 8f))
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

    /// Video effects on the selected clips or the whole project, after the
    /// iPhone app's effects editor: controls show the value at the playhead;
    /// with Animate on, a change keys it there. Dragging previews the draft;
    /// releasing commits one undo step.
    private var effectsAnimate = false
    private var effectsScroll = 0
    private fun openEffects() {
        val project = project ?: return
        editor.pause()
        val items = targets(project.edit)
        val at = EditTime.seconds(editor.playhead)
        val reference = items.firstOrNull { at in it.interval } ?: items.firstOrNull() ?: return
        val source = if (at in reference.interval) reference.mapping.source(at - reference.start) else null
        val time = source ?: reference.content.start
        fun draft(now: Project, change: (TimelineClip, Double?) -> TimelineClip) = TimelineEditing.settingVideoEffects(selection(now.edit), now.edit, editor.playhead, change)
        fun apply(message: String, reopen: Boolean = true, change: (TimelineClip, Double?) -> TimelineClip) {
            val now = this.project ?: return
            val edit = draft(now, change)
            if (edit != now.edit) commit(edit, message)
            if (reopen) openEffects()
        }
        // Done sits by the title: the list below scrolls and can be taller than the screen.
        val content = Design.column(context, Design.row(context, Design.label(context, "Effects · " + scopeTitle(project.edit), 15f, bold = true).also { it.layoutParams = Design.weighted() },
            Design.button(context, "Done", Design.accent) { closePanel() }), spacing = 4f)
        fun add(view: View) = content.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun choice(title: String, chosen: Boolean, description: String, action: () -> Unit) = Design.button(context, title, if (chosen) Design.background else Design.text, if (chosen) Design.accent else Design.raised, 13f) { action() }
            .apply { contentDescription = description + if (chosen) ", selected" else "" }
        add(Design.row(context, choice(if (effectsAnimate) "Animate: on" else "Animate: off", effectsAnimate, "Animate") { effectsAnimate = !effectsAnimate; openEffects() },
            Design.label(context, if (effectsAnimate) "Changes add keyframes at " + Design.timecode(editor.playhead) else "", 12f, Design.secondary)))
        val list = Design.column(context, spacing = 4f)
        fun listed(view: View) = list.addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val effects = VideoEffectSettings.ordered(reference.content.videoEffects ?: emptyList())
        for (effect in effects) {
            val kind = effect.kind; val name = kind.descriptor.displayName
            if (kind == VideoEffectID.freezeFrame) {
                // A hold is a piece of the timeline: removing it closes up the footage after it.
                listed(Design.row(context, Design.label(context, "$name · hold frame", 14f, bold = true).also { it.layoutParams = Design.weighted() },
                    Design.button(context, "Remove hold", Design.recording, Design.raised, 13f) {
                        val now = this.project ?: return@button
                        val (edit, _) = TimelineEditing.deleting(now.edit.timeline.selection(setOf(reference.id)), now.edit)
                        commit(edit, "Hold removed"); openEffects()
                    }))
            } else listed(Design.row(context, Design.label(context, name, 14f, bold = true).also { it.layoutParams = Design.weighted() },
                choice(if (effect.enabled) "On" else "Off", effect.enabled, "$name enabled") { apply(name + if (effect.enabled) " off" else " on") { c, _ -> VideoEffectEditing.enabling(c, kind, !effect.enabled) } },
                Design.button(context, "Reset", Design.text, Design.raised, 13f) { apply("$name reset") { c, _ -> VideoEffectEditing.resetting(c, kind) } },
                Design.button(context, "Remove", Design.recording, Design.raised, 13f) { apply("$name removed") { c, _ -> VideoEffectEditing.removing(c, kind) } }))
            for (parameter in effect.parameters) {
                val value = effect.value(parameter.id, time) ?: parameter.value
                fun set(newValue: EffectValue, message: String, reopen: Boolean = true) {
                    if (kind == VideoEffectID.freezeFrame && parameter.id == "hold" && newValue is EffectValue.Number) {
                        val now = this.project ?: return
                        commit(TimelineEditing.settingHold(now.edit, reference.id, newValue.value), message); if (reopen) openEffects(); return
                    }
                    apply(message, reopen) { c, s -> VideoEffectEditing.setting(c, kind, parameter.id, newValue, s, effectsAnimate) }
                }
                val keyed = source != null && parameter.keyframes.any { kotlin.math.abs(it.time - source) < 1.0 / 60 }
                val diamond = if (source != null && parameter.animatable && (effectsAnimate || parameter.keyframes.isNotEmpty()))
                    Design.button(context, if (keyed) "◆" else "◇", Design.text, Design.raised, 13f) {
                        apply(if (keyed) "Keyframe removed" else "Keyframe added") { c, s -> if (s == null) c else VideoEffectEditing.togglingKeyframe(c, kind, parameter.id, s) }
                    }.apply { contentDescription = "Keyframe for " + parameter.title + if (keyed) ", set" else "" } else null
                when (val k = parameter.kind) {
                    is EffectParameterKind.Slider -> {
                        val steps = Math.round((k.max - k.min) / k.step).toInt()
                        val number = (value as? EffectValue.Number)?.value ?: k.defaultValue
                        fun at(progress: Int) = (k.min + progress * k.step).coerceIn(k.min, k.max).let { Math.round(it / k.step) * k.step }.coerceIn(k.min, k.max)
                        fun text(v: Double) = if (parameter.id == "intensity") String.format("%.0f%%", v * 100) else if (k.step >= 1) String.format("%.0f", v) else if (k.step >= 0.1) String.format("%.1f", v) else String.format("%.2f", v)
                        val shown = Design.label(context, text(number), 13f, mono = true)
                        var pending = number
                        val bar = SeekBar(context).apply {
                            max = steps; progress = Math.round((number - k.min) / k.step).toInt()
                            contentDescription = parameter.title
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                                    if (!user) return
                                    pending = at(p); shown.text = text(pending)
                                    this@EditorView.project?.let { now -> editor.show(draft(now) { c, s -> VideoEffectEditing.setting(c, kind, parameter.id, EffectValue.Number(pending), s, effectsAnimate) }) }
                                }
                                override fun onStartTrackingTouch(b: SeekBar) { }
                                override fun onStopTrackingTouch(b: SeekBar) { set(EffectValue.Number(pending), "$name: " + parameter.title, reopen = effectsAnimate) }
                            })
                        }
                        val line = LinearLayout(context).apply {
                            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            addView(Design.label(context, parameter.title, 13f), LayoutParams(Design.dp(context, 96f), ViewGroup.LayoutParams.WRAP_CONTENT))
                            addView(bar, Design.weighted(1f, Design.dp(context, 44f))); addView(shown, LayoutParams(Design.dp(context, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
                            diamond?.let { addView(it) }
                        }
                        listed(line)
                    }
                    is EffectParameterKind.Toggle -> {
                        val on = (value as? EffectValue.Toggle)?.value ?: false
                        listed(Design.row(context, choice(parameter.title + if (on) ": on" else ": off", on, parameter.title) { set(EffectValue.Toggle(!on), "$name: " + parameter.title) }, *listOfNotNull(diamond).toTypedArray()))
                    }
                    is EffectParameterKind.Stepper -> {
                        val current = (value as? EffectValue.Number)?.value
                        listed(Design.label(context, parameter.title, 13f))
                        listed(choices(*(k.values.map { v -> choice(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString(), v == current, parameter.title + " " + v) { set(EffectValue.Number(v), "$name: " + parameter.title) } } + listOfNotNull(diamond)).toTypedArray()))
                    }
                    is EffectParameterKind.Picker -> {
                        val current = (value as? EffectValue.Choice)?.value
                        listed(Design.label(context, parameter.title, 13f))
                        val offered = k.options.filter { it == current || !VideoEffectRendering.unavailable(kind, parameter.id, it) }
                        listed(choices(*(offered.map { o -> choice(o, o == current, parameter.title + " " + o) { set(EffectValue.Choice(o), "$name: " + parameter.title) } } + listOfNotNull(diamond)).toTypedArray()))
                    }
                    is EffectParameterKind.Color -> {
                        // Twelve hues around the wheel, full colour, and the one set now.
                        val now = (value as? EffectValue.Color)?.value
                        listed(Design.label(context, parameter.title, 13f))
                        val swatches = (0 until 12).map { i -> android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 0.85f, 0.9f)) }.map { c ->
                            EffectRGB(android.graphics.Color.red(c) / 255.0, android.graphics.Color.green(c) / 255.0, android.graphics.Color.blue(c) / 255.0) }
                        fun swatch(rgb: EffectRGB, chosen: Boolean) = Design.button(context, if (chosen) "●" else " ", Design.text,
                            android.graphics.Color.rgb((rgb.red * 255).toInt(), (rgb.green * 255).toInt(), (rgb.blue * 255).toInt()), 13f) {
                            set(EffectValue.Color(rgb), "$name: " + parameter.title) }.apply { contentDescription = parameter.title + " " + EffectHues.name(rgb) + if (chosen) ", selected" else "" }
                        listed(choices(*((listOfNotNull(now?.takeIf { it !in swatches }).map { swatch(it, true) } + swatches.map { swatch(it, it == now) }) + listOfNotNull(diamond)).toTypedArray()))
                    }
                    is EffectParameterKind.Media -> listed(Design.label(context, parameter.title + " is kept as set on iPhone.", 12f, Design.secondary))
                }
            }
        }
        if (effects.isEmpty()) listed(Design.label(context, "No effects on " + scopeTitle(project.edit) + ". Add one below.", 13f, Design.secondary))
        // Effects to add, by family, three to a row.
        val missing = VideoEffectCatalog.all.filter { it.id in VideoEffectRendering.drawn && effects.none { e -> e.kind == it.id } && (reference.content.frozenFrame == null || it.family != EffectFamily.Time) }
        for ((family, group) in missing.groupBy { it.family }) {
            listed(Design.label(context, "Add · " + family.title, 13f, Design.secondary).apply { setPadding(0, Design.dp(context, 6f), 0, 0) })
            for (row in group.chunked(3)) listed(LinearLayout(context).apply {
                orientation = HORIZONTAL
                for (d in row) addView(Design.button(context, d.displayName, Design.text, Design.raised, 13f) {
                    if (d.id == VideoEffectID.freezeFrame) {
                        // A hold of the frame under the playhead, inserted there.
                        val now = this@EditorView.project ?: return@button
                        val made = TimelineEditing.freezing(now.edit, editor.playhead)
                        if (made == null) { say("Move the playhead outside a transition, onto one picture, to freeze one frame."); return@button }
                        commit(made.first, "Freeze frame added"); openEffects()
                    } else apply(d.displayName + " added") { c, _ -> VideoEffectEditing.adding(c, d.id) } }
                    .apply { contentDescription = "Add " + d.displayName }, Design.weighted(1f).apply { rightMargin = Design.dp(context, 6f); bottomMargin = Design.dp(context, 6f) })
                repeat(3 - row.size) { addView(View(context), Design.weighted(1f)) }
            })
        }
        // Rebuilt after each change; the list keeps its place.
        add(android.widget.ScrollView(context).apply {
            addView(list); post { scrollTo(0, effectsScroll) }
            setOnScrollChangeListener { _, _, y, _, _ -> effectsScroll = y }
        }.also { it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(context, 260f)) })
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

/// Names for the hue swatches, read by TalkBack.
object EffectHues {
    fun name(rgb: EffectRGB): String {
        val hsv = FloatArray(3); android.graphics.Color.RGBToHSV((rgb.red * 255).toInt(), (rgb.green * 255).toInt(), (rgb.blue * 255).toInt(), hsv)
        val names = listOf("red", "orange", "yellow", "lime", "green", "spring green", "cyan", "azure", "blue", "violet", "magenta", "rose")
        return names[(Math.round(hsv[0] / 30f) % 12)]
    }
}
