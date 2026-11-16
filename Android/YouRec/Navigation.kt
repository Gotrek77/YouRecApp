package yourec

import java.util.UUID

/// Recorder, Projects and Editor are separate destinations. Each screen is a
/// plain view; the activity shows one at a time and tells it when it is in use.
interface Navigation {
    /// Open the recorder. With a project, new takes are appended to it.
    fun showRecorder(project: UUID?)
    fun showProjects()
    fun showEditor(project: UUID)
    /// Ask for camera and microphone access; true once both are granted.
    fun requestCapturePermissions(completion: (Boolean) -> Unit)
    /// Let the user choose one document of a MIME type, e.g. "video/*". Null if they cancel.
    fun pickMedia(type: String, completion: (android.net.Uri?) -> Unit)
    /// Offer a finished movie to other apps.
    fun share(movie: java.io.File)
    /// Copy a finished movie into the device gallery. The message is for the user.
    fun saveToGallery(movie: java.io.File, completion: (String) -> Unit)
}

interface Screen {
    /// The screen is visible and may hold the camera, a player or the audio route.
    fun appear()
    /// Release them: another destination, or another app, is in front.
    fun disappear()
    /// Return true if Back was used here, for example to close a panel.
    fun back(): Boolean = false
}
