package yourec

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import java.io.File
import java.util.UUID

/// One activity, three destinations. The recorder opens first: the camera is
/// what the app is for. Each screen holds the camera, a player or neither, and
/// gives it up whenever it is not in front.
class YouRecActivity : Activity(), Navigation {
    private lateinit var store: ProjectStore
    private lateinit var container: FrameLayout
    private var recorder: RecorderView? = null
    private var projects: ProjectsView? = null
    private var editor: EditorView? = null
    private var current: View? = null
    private var resumed = false
    private var permissionRequest: ((Boolean) -> Unit)? = null
    private var pickRequest: ((Uri?) -> Unit)? = null
    private var bars = intArrayOf(0, 0, 0, 0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = ProjectStore(this)
        container = FrameLayout(this).apply { setBackgroundColor(Design.background) }
        setContentView(container)
        // Draw behind the system bars; each screen is padded clear of them,
        // except the recorder, whose preview fills the whole display.
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") { container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
        window.statusBarColor = 0; window.navigationBarColor = 0
        container.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            bars = intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            pad(); insets
        }
        store.recoverUnindexedRecordings()
        showRecorder(store.selectedID)
    }
    private fun pad() { current?.setPadding(bars[0], bars[1], bars[2], bars[3]) }

    private fun show(view: View) {
        if (current === view) return
        (current as? Screen)?.let { if (resumed) it.disappear() }
        container.removeAllViews()
        current = view
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pad()
        if (resumed) (view as Screen).appear()
    }
    override fun showRecorder(project: UUID?) {
        val view = recorder ?: RecorderView(this, store, this).also { recorder = it }
        view.project = project
        show(view)
    }
    override fun showProjects() { show(projects ?: ProjectsView(this, store, this).also { projects = it }) }
    override fun showEditor(project: UUID) {
        val view = editor ?: EditorView(this, store, this).also { editor = it }
        view.projectID = project
        show(view)
    }

    override fun onResume() { super.onResume(); resumed = true; (current as? Screen)?.appear() }
    // Another app, a call or the lock screen: give up the camera and stop playback at once.
    override fun onPause() { resumed = false; (current as? Screen)?.disappear(); super.onPause() }
    override fun onDestroy() { editor?.release(); super.onDestroy() }
    @Deprecated("Deprecated in the platform; still the callback available from API 29.")
    override fun onBackPressed() {
        if ((current as? Screen)?.back() == true) return
        if (current !is ProjectsView) showProjects() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun requestCapturePermissions(completion: (Boolean) -> Unit) {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return completion(true)
        permissionRequest = completion
        requestPermissions(needed.toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        val completion = permissionRequest ?: return
        permissionRequest = null
        completion(results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED })
    }

    override fun pickMedia(type: String, completion: (Uri?) -> Unit) {
        pickRequest = completion
        try { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), 2) }
        catch (e: Exception) { pickRequest = null; completion(null) }
    }
    @Deprecated("Deprecated in the platform; the replacement needs a support library.")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (request != 2) return
        val completion = pickRequest ?: return
        pickRequest = null
        completion(if (result == RESULT_OK) data?.data else null)
    }

    override fun share(movie: File) {
        val uri = ExportProvider.uri(this, movie) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser reads the grant from clip data, not from the extra.
        send.clipData = ClipData.newRawUri(movie.name, uri)
        startActivity(Intent.createChooser(send, "Share movie"))
    }
    override fun saveToGallery(movie: File, completion: (String) -> Unit) {
        Thread {
            var message = "Saved to Movies/YouRec in your gallery."
            val resolver = contentResolver
            var item: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, movie.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/YouRec"); put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                item = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw java.io.IOException("The gallery refused the file.")
                resolver.openOutputStream(item)!!.use { output -> movie.inputStream().use { it.copyTo(output, 1 shl 16) } }
                // Published only when complete: the gallery never shows half a movie.
                resolver.update(item, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                item?.let { try { resolver.delete(it, null, null) } catch (ignored: Exception) { } }
                message = "Could not save to the gallery. " + (e.message ?: "") + " The export is still in the project."
            }
            runOnUiThread { completion(message) }
        }.start()
    }
}

/// Lets another app read one finished export the user chose to share, and
/// nothing else: only files named *.mp4 directly inside a project's Exports folder.
class ExportProvider : ContentProvider() {
    companion object {
        private fun root(context: android.content.Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects")
        fun uri(context: android.content.Context, movie: File): Uri? {
            val project = movie.parentFile?.parentFile?.name ?: return null
            return Uri.Builder().scheme("content").authority(context.packageName + ".exports").appendPath(project).appendPath(movie.name).build()
        }
    }
    private fun file(uri: Uri): File? {
        val context = context ?: return null
        val parts = uri.pathSegments
        if (parts.size != 2 || !parts[1].endsWith(".mp4") || parts.any { it.contains("/") || it.startsWith(".") }) return null
        val file = File(File(File(root(context), parts[0]), "Exports"), parts[1])
        // Resolve links before trusting the location.
        return if (file.isFile && file.canonicalPath.startsWith(root(context).canonicalPath + File.separator)) file else null
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = if (file(uri) != null) "video/mp4" else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Exports are read-only.")
        return ParcelFileDescriptor.open(file(uri) ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, arguments: Array<out String>?, order: String?): Cursor? {
        val file = file(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else if (it == OpenableColumns.SIZE) file.length() else null }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, arguments: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, arguments: Array<out String>?) = 0
}
