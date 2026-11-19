package yourec

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// The project library: continue recording, edit, export, rename, and a
/// reversible Recently Deleted. A project that cannot be read is listed with
/// its reason; its files are never touched from here.
class ProjectsView(context: Context, private val store: ProjectStore, private val navigation: Navigation) : LinearLayout(context), Screen {
    private val list = LinearLayout(context)
    private val notice = Design.label(context, "", 14f, Design.warning)
    private val panel = FrameLayout(context)
    private var visible = false
    private var offered = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Design.background)
        val heading = Design.label(context, "Projects", 28f, bold = true)
        val bar = Design.row(context, heading, Design.button(context, "Import", fill = Design.raised) { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add("Import video").setOnMenuItemClickListener { importVideo(); true }
                menu.add("Open project backup").setOnMenuItemClickListener { openBackup(); true }
            }.show()
        },
            Design.button(context, "● Record", Design.text, Design.recording) { navigation.showRecorder(null) })
        (heading.layoutParams as LayoutParams).apply { width = 0; weight = 1f }
        bar.setPadding(Design.dp(context, 16f), Design.dp(context, 12f), Design.dp(context, 12f), Design.dp(context, 8f))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        notice.setPadding(Design.dp(context, 16f), 0, Design.dp(context, 16f), Design.dp(context, 8f)); notice.visibility = View.GONE
        notice.setOnClickListener { store.error = null }
        addView(notice, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.orientation = VERTICAL; list.setPadding(Design.dp(context, 12f), 0, Design.dp(context, 12f), Design.dp(context, 24f))
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun appear() { visible = true; store.observe(this) { if (visible) refresh() }; refresh(); offerExpiredDeletions() }
    override fun disappear() { visible = false; store.stopObserving(this); panel.removeAllViews() }
    override fun back(): Boolean { if (panel.childCount == 0) return false; panel.removeAllViews(); return true }

    private fun section(title: String) = Design.label(context, title, 13f, Design.secondary, bold = true).apply { setPadding(Design.dp(context, 4f), Design.dp(context, 20f), 0, Design.dp(context, 6f)) }
    private fun date(moment: Moment) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(moment.millis))

    private fun refresh() {
        notice.text = listOfNotNull(store.error, if (store.unsavedDrafts.isNotEmpty()) "Changes not saved." else null).joinToString(" ")
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (store.unsavedDrafts.isNotEmpty()) list.addView(Design.button(context, "Changes not saved → Retry", Design.background, Design.warning) { store.retrySaves() })
        if (store.importing) list.addView(Design.label(context, "${store.operationLabel}… ${(store.operationProgress * 100).toInt()}%", 15f, Design.secondary).apply { setPadding(Design.dp(context, 4f), Design.dp(context, 12f), 0, Design.dp(context, 12f)) })
        if (store.projects.isEmpty() && !store.importing) {
            list.addView(Design.label(context, "No projects yet.\nRecord a new video, or import one to edit.", 16f, Design.secondary).apply { gravity = Gravity.CENTER; setPadding(0, Design.dp(context, 80f), 0, 0) },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        for (project in store.projects) list.addView(card(project), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Design.dp(context, 8f) })
        if (store.unavailableProjects.isNotEmpty()) {
            list.addView(section("COULD NOT BE OPENED"))
            for ((id, reason) in store.unavailableProjects) list.addView(Design.label(context, "${id.text.take(8)}… · $reason", 14f, Design.secondary).apply { setPadding(Design.dp(context, 4f), Design.dp(context, 6f), 0, Design.dp(context, 6f)) })
        }
        if (store.deletedProjects.isNotEmpty()) {
            list.addView(section("RECENTLY DELETED"))
            for (project in store.deletedProjects) {
                val record = store.deletionRecords[project.id]
                val name = Design.label(context, project.title + (record?.let { "\nDeleted " + date(it.date) } ?: ""), 14f, Design.secondary)
                val row = Design.row(context, name, Design.button(context, "Restore") { store.restoreDeleted(project) },
                    Design.button(context, "Erase", Design.recording) { confirm("Erase “${project.title}” permanently?", "Its recordings and editing history will be removed from this phone. This cannot be undone.", "Erase") { store.eraseDeleted(project) } })
                (name.layoutParams as LayoutParams).apply { width = 0; weight = 1f }
                list.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Design.dp(context, 4f) })
            }
        }
    }

    private fun card(project: Project): View {
        val thumbnail = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Design.raised) }
        val first = project.edit.timeline.chronologicalVideo.firstOrNull()
        val source = first?.let { item -> project.sources.firstOrNull { it.id == item.content.sourceID } }
        if (first != null && source != null) {
            val file = store.mediaFile(project, source); val height = Design.dp(context, 72f)
            fun load(): Bitmap? = SourceMediaCache.thumbnail(file, first.content.start, height) { load()?.let { thumbnail.setImageBitmap(it) } }
            if (file.exists()) load()?.let { thumbnail.setImageBitmap(it) }
        }
        val duration = project.edit.duration
        val details = Design.column(context, Design.label(context, project.title, 16f, bold = true).apply { maxLines = 2 },
            Design.label(context, if (project.sources.isEmpty()) "No recordings yet" else Design.clock(duration) + " · " + date(project.updatedAt), 13f, Design.secondary), spacing = 4f)
        val menu = Design.symbol(context, "⋯", "More actions for ${project.title}", fill = 0) { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add("Continue recording").setOnMenuItemClickListener { navigation.showRecorder(project.id); true }
                menu.add("Rename").setOnMenuItemClickListener { rename(project); true }
                menu.add("Duplicate project").setOnMenuItemClickListener { store.duplicate(project); true }
                menu.add("Back up project").setOnMenuItemClickListener { store.backUp(project) { file -> if (file != null) navigation.share(file) }; true }
                if (project.edit.duration > 0) menu.add("Export movie").setOnMenuItemClickListener { export(project); true }
                menu.add("Delete").setOnMenuItemClickListener { store.moveToDeleted(project); true }
            }.show()
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Design.rounded(Design.surface, Design.dp(context, 14f).toFloat())
            setPadding(Design.dp(context, 10f), Design.dp(context, 10f), Design.dp(context, 4f), Design.dp(context, 10f))
            addView(thumbnail, LayoutParams(Design.dp(context, 96f), Design.dp(context, 72f)))
            addView(details, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Design.dp(context, 12f) })
            addView(menu)
            isClickable = true
            contentDescription = "Edit ${project.title}"
            // A project without footage has nothing to edit yet: go and record it.
            setOnClickListener { if (store.select(project.id)) { if (project.sources.isEmpty()) navigation.showRecorder(project.id) else navigation.showEditor(project.id) } }
        }
        thumbnail.clipToOutline = true; thumbnail.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        thumbnail.background = Design.rounded(Design.raised, Design.dp(context, 8f).toFloat())
        return row
    }

    private fun rename(project: Project) {
        val field = EditText(context).apply { setText(project.title); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; selectAll() }
        AlertDialog.Builder(context).setTitle("Rename project").setView(field)
            .setPositiveButton("Rename") { _, _ -> store.rename(project, field.text.toString()) }.setNegativeButton("Cancel", null).show()
    }
    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        AlertDialog.Builder(context).setTitle(title).setMessage(message).setPositiveButton(action) { _, _ -> run() }.setNegativeButton("Cancel", null).show()
    }
    private fun export(project: Project) {
        if (!store.openProject(project.id)) return
        val content = MovieExportPanel(context, store, navigation, project.id) { panel.removeAllViews() }
        content.setPadding(Design.dp(context, 16f), Design.dp(context, 12f), Design.dp(context, 16f), Design.dp(context, 16f))
        content.background = Design.rounded(Design.surface, Design.dp(context, 16f).toFloat())
        panel.removeAllViews(); panel.addView(content)
    }
    private fun openBackup() {
        navigation.pickMedia("application/zip") { uri -> if (uri != null) store.restoreBackup(uri) { project -> if (project != null && visible && project.sources.isNotEmpty()) navigation.showEditor(project.id) } }
    }
    private fun importVideo() {
        navigation.pickMedia("video/*") { uri -> if (uri != null) store.importVideo(uri) { project -> if (project != null && visible) navigation.showEditor(project.id) } }
    }
    /// Permanent removal after thirty days is offered, never done silently.
    private fun offerExpiredDeletions() {
        val expired = store.expiredDeletions()
        // Asked once per launch, not again on every return to this screen.
        if (expired.isEmpty() || offered) return
        offered = true
        confirm("Erase old deleted projects?", "${expired.size} project${if (expired.size == 1) " was" else "s were"} deleted more than 30 days ago. Erasing frees their storage and cannot be undone.", "Erase") { store.eraseExpiredDeletions(expired) }
    }
}
