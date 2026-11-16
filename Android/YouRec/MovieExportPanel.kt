package yourec

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.UUID

/// Export settings, progress and what to do with the finished movie. An
/// unchanged completed edit offers its existing movie instead of a duplicate.
class MovieExportPanel(context: Context, private val store: ProjectStore, private val navigation: Navigation, private val projectID: UUID,
                       private val close: () -> Unit) : LinearLayout(context) {
    private var export: MovieExport? = null
    private val project: Project? get() = store.projects.firstOrNull { it.id == projectID }

    init { orientation = VERTICAL; showSettings(null) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Leaving the panel abandons the render; nothing partial is published.
        export?.cancel(); export = null
        keepScreenOn = false
    }

    private fun reset(heading: String) {
        removeAllViews()
        addView(Design.label(context, heading, 16f, bold = true), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Design.dp(context, 8f) })
    }
    private fun add(view: View, top: Float = 8f) { addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Design.dp(context, top) }) }

    private fun <T> choice(title: String, options: List<T>, current: T, name: (T) -> String, pick: (T) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Design.label(context, title, 14f, Design.secondary), LayoutParams(Design.dp(context, 84f), ViewGroup.LayoutParams.WRAP_CONTENT))
        for (option in options) {
            val chosen = option == current
            row.addView(Design.button(context, name(option), if (chosen) Design.background else Design.text, if (chosen) Design.accent else Design.raised, 13f) { pick(option) }.apply {
                contentDescription = title + " " + name(option) + if (chosen) ", selected" else ""
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Design.dp(context, 4f) })
        }
        return row
    }

    private fun showSettings(message: String?) {
        val project = project ?: return close()
        reset("Export movie")
        val settings = project.exportSettings
        fun set(next: ExportSettings) { store.update(projectID) { it.exportSettings = next }; showSettings(null) }
        add(choice("Format", ExportSettings.Codec.values().toList(), settings.codec, { it.title }) { set(settings.copy(codec = it)) }, 0f)
        add(choice("Size", ExportSettings.Resolution.values().toList(), settings.resolution, { if (it == ExportSettings.Resolution.Project) "Project" else it.title }) { set(settings.copy(resolution = it)) })
        add(choice("Frames/s", ExportSettings.FrameRate.values().toList(), settings.frameRate, { it.value.toString() }) { set(settings.copy(frameRate = it)) })
        if (message != null) add(Design.label(context, message, 14f, Design.warning))
        val existing = Exports.matching(project, store.exportsFolder(project))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        if (existing != null) {
            add(Design.label(context, "This edit is already exported with these settings.", 14f, Design.secondary))
            actions.addView(Design.button(context, "Share", Design.background, Design.accent) { navigation.share(File(store.exportsFolder(project), existing.filename)) })
            actions.addView(Design.button(context, "Save to gallery") { save(File(store.exportsFolder(project), existing.filename), existing) }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        } else actions.addView(Design.button(context, "Export", Design.background, Design.accent) { begin() })
        actions.addView(Design.button(context, "Close", fill = 0) { close() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        add(actions, 12f)
    }

    private fun begin() {
        val project = project ?: return close()
        reset("Exporting…")
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val percent = Design.label(context, "0%", 14f, mono = true)
        add(bar, 0f); add(percent)
        add(Design.label(context, "Keep YouRec open until the export finishes. Your recordings are not changed.", 13f, Design.secondary))
        add(Design.button(context, "Cancel") { export?.cancel() }, 12f)
        // Rendering can outlast the display timeout.
        keepScreenOn = true
        val folder = store.folder(project); val exports = store.exportsFolder(project)
        export = MovieExport(project, folder, exports, { value -> bar.progress = (value * 1000).toInt(); percent.text = "${(value * 100).toInt()}%" }) { receipt, failure ->
            keepScreenOn = false
            if (export == null) return@MovieExport       // the panel was closed meanwhile
            export = null
            if (receipt != null) showDone(File(exports, receipt.filename), receipt)
            else showSettings(failure ?: "Export cancelled. Nothing was saved.")
        }.also { it.start() }
    }

    private fun showDone(movie: File, receipt: ExportReceipt) {
        reset("Export finished")
        add(Design.label(context, "${receipt.width} × ${receipt.height} · ${receipt.framesPerSecond} fps · ${receipt.codec.title} · ${movie.length() / 1_000_000} MB", 14f, Design.secondary), 0f)
        receipt.audioWarning?.let { add(Design.label(context, it, 14f, Design.warning)) }
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(Design.button(context, "Share", Design.background, Design.accent) { navigation.share(movie) })
        actions.addView(Design.button(context, "Save to gallery") { save(movie, receipt) }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        actions.addView(Design.button(context, "Close", fill = 0) { close() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Design.dp(context, 8f) })
        add(actions, 12f)
    }
    private fun save(movie: File, receipt: ExportReceipt) {
        val status = Design.label(context, "Saving…", 14f, Design.secondary)
        add(status)
        navigation.saveToGallery(movie) { message -> status.text = message }
    }
}
