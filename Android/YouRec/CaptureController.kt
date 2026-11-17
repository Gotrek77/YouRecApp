package yourec

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/// Camera, preview and the recording state machine.
///
/// Three threads, each owning its own state:
///  - main: everything public, the published state, settings and the zoom ramp;
///  - camera: every Camera2 call and callback (opening and closing block);
///  - render: the EGL context. Camera frames land in one SurfaceTexture that
///    survives camera changes, and are drawn by the same code to the screen
///    and to the encoder, so the saved framing is the previewed framing.
///
/// A camera change closes one device and opens another on the same Surface.
/// In between no frame arrives, the texture keeps its last picture, and the
/// render thread goes on presenting it to the encoder with advancing times:
/// the take holds its last frame while the microphone, which is not ours, continues.
class CaptureController(private val context: Context) {
    enum class State { Idle, Recording, Paused, Saving }
    enum class Quality(val title: String, val long: Int, val short: Int, val bitrate: Int) {
        HD("1080p · 30 fps", 1920, 1080, 16_000_000), UHD("4K · 30 fps", 3840, 2160, 48_000_000)
    }

    /// One openable camera. `multiplier` is its native framing relative to the
    /// main camera of its side; zoom shown to the user is ratio × multiplier.
    class Camera(val id: String, val front: Boolean, val side: String, val multiplier: Double, val logical: Boolean, private val info: CameraCharacteristics) {
        var name = side; internal set
        val orientation: Int = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val array: Rect = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        // Sensor timestamps on the boot clock can be placed on the audio clock
        // exactly. With any other source only their spacing is meaningful.
        val realtime = info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val zoomRatio: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val minZoom: Double = zoomRatio?.lower?.toDouble() ?: 1.0
        val maxZoom: Double = zoomRatio?.upper?.toDouble() ?: max(1.0, (info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).toDouble())
        private val focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        /// Diopters at the near end. Zero is a lens that cannot move.
        val nearest: Float = info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val continuousFocus: Int? = listOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE).firstOrNull { it in focusModes }
        val triggeredFocus = CameraMetadata.CONTROL_AF_MODE_AUTO in focusModes
        val fixedFocus = nearest <= 0f || (continuousFocus == null && !triggeredFocus)
        /// The lens position can be read and set, so a lock survives a camera change and a slider is possible.
        val manualFocus = !fixedFocus && CameraMetadata.CONTROL_AF_MODE_OFF in focusModes &&
            (info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)).contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val focusRegions = triggeredFocus && (info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        val sizes: List<Size> = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val supports4K = sizes.any { it.width == 3840 && it.height == 2160 }
        val frameRate: Range<Int>? = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { ranges ->
            // A fixed 30 keeps exposure from halving the frame rate in dim rooms.
            ranges.filter { it.upper == 30 }.maxByOrNull { it.lower } ?: ranges.filter { it.upper >= 30 }.minByOrNull { it.upper }
        }
        /// Zoom levels worth a button, in user-facing magnification.
        val presets: List<Double> get() {
            val low = minZoom * multiplier; val high = maxZoom * multiplier
            val list = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0).filter { it >= low - 0.01 && it <= high + 0.01 }.toMutableList()
            // Always offer this lens's own widest framing, whatever number it is.
            if (list.isEmpty() || list.first() > low + 0.05) list.add(0, Math.round(low * 10) / 10.0)
            return list
        }
        fun streamSize(quality: Quality): Size {
            val wide = sizes.filter { it.width * 9 == it.height * 16 && it.width <= quality.long }.maxByOrNull { it.width }
            return wide ?: sizes.filter { it.width <= quality.long && it.height <= quality.short }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: Size(1920, 1080)
        }
    }

    // ---- Published state. Main thread. ----
    var onChange: (() -> Unit)? = null
    /// A finished, playable movie now sits in the take's folder.
    var onSegment: ((File) -> Unit)? = null
    val audio = CaptureAudio(context)
    var cameras: List<Camera> = emptyList(); private set
    var camera: Camera? = null; private set
    var ready = false; private set
    var switching = false; private set
    var state = State.Idle; private set
    var error: String? = null
        set(value) { field = value; onChange?.invoke() }
    /// Stays until acknowledged by setting it to null.
    var warning: String? = null
        set(value) { field = value; onChange?.invoke() }
    var zoom = 1.0; private set
    val displayZoom: Double get() = zoom * (camera?.multiplier ?: 1.0)
    var focusLocked = false; private set
    /// 0 near to 1 far, for a lens whose position can be set. Not a distance.
    var focusPosition: Double? = null; private set
    /// The physical turn of the device, clockwise from upright, in quarter turns.
    @Volatile var turn = 0; private set
    /// Display rotation in degrees. The activity is portrait-locked, so this is
    /// zero on phones; a tablet whose natural orientation is landscape differs.
    @Volatile var displayTurn = 0
    val elapsed: Double get() = (elapsedBefore + if (state == State.Recording) SystemClock.uptimeMillis() - segmentStart else 0L) / 1000.0
    val hasTake: Boolean get() = state != State.Idle

    // ---- Settings, remembered. ----
    private val preferences = context.getSharedPreferences("yourec.recorder", Context.MODE_PRIVATE)
    var quality: Quality = if (preferences.getString("quality", "") == "UHD") Quality.UHD else Quality.HD
        set(value) {
            if (hasTake || field == value) return
            field = value; preferences.edit().putString("quality", value.name).apply()
            // The camera stream follows the quality, so the session is rebuilt.
            val current = camera
            if (current != null && ready && !switching) { cancelRamp(); switching = true; cameraHandler?.post { open(current, null, null) } }
            onChange?.invoke()
        }
    /// The front preview behaves like a mirror; the saved movie keeps writing readable.
    @Volatile var previewMirrored = preferences.getBoolean("mirrorFrontPreview", true)
        set(value) { field = value; preferences.edit().putBoolean("mirrorFrontPreview", value).apply(); redraw() }
    @Volatile var saveMirrored = preferences.getBoolean("mirrorFrontRecording", false)
        set(value) { field = value; preferences.edit().putBoolean("mirrorFrontRecording", value).apply() }
    /// Fill the display, or fit the complete recorded frame inside it.
    @Volatile var fill = preferences.getBoolean("previewFill", true)
        set(value) { field = value; preferences.edit().putBoolean("previewFill", value).apply(); redraw() }
    var zoomDuration = ZoomTransition.boundedDuration(preferences.getFloat("zoomTransitionSeconds", 0f).toDouble())
        set(value) { field = ZoomTransition.boundedDuration(value); preferences.edit().putFloat("zoomTransitionSeconds", field.toFloat()).apply() }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraThread: HandlerThread? = null
    private var renderThread: HandlerThread? = null
    @Volatile private var cameraHandler: Handler? = null
    @Volatile private var renderHandler: Handler? = null
    private var started = false

    // ---- Take. Main thread. ----
    private var writer: CaptureSegmentWriter? = null
    private var takeFolder: File? = null
    private var takeTurn = 0
    private var takeQuality = Quality.HD
    private var segmentStart = 0L
    private var elapsedBefore = 0L
    private var afterSaving = State.Idle
    private var ramp: ZoomTransition? = null

    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return   // lying flat: keep the last answer
            // Change only well past the diagonal, so a hand held at 45° does not flicker.
            val away = abs(((degrees - turn + 540) % 360) - 180)
            if (away <= 60) return
            turn = ((degrees + 45) / 90 * 90) % 360
            redraw(); onChange?.invoke()
        }
    }

    // ================================================================ lifecycle

    /// Start the microphone, the preview and the remembered camera. Never starts a take.
    fun start() {
        if (started) return
        started = true; error = null
        if (cameras.isEmpty()) cameras = discover()
        val camera = HandlerThread("yourec.camera").apply { start() }; val render = HandlerThread("yourec.render").apply { start() }
        cameraThread = camera; renderThread = render
        cameraHandler = Handler(camera.looper); renderHandler = Handler(render.looper)
        if (orientation.canDetectOrientation()) orientation.enable()
        if (!audio.start()) warning = "No microphone is available. Recordings will have no sound."
        val first = cameras.firstOrNull { it.id == preferences.getString("camera", null) } ?: cameras.firstOrNull { !it.front } ?: cameras.firstOrNull()
        if (first == null) { error = "No camera is available on this device."; return }
        switching = true
        renderHandler?.post {
            try { setUpRender() } catch (e: Exception) { main.post { switching = false; error = "The preview could not start. " + (e.message ?: "") }; return@post }
            cameraHandler?.post { retries = 1; open(first, null, null) }
        }
        onChange?.invoke()
    }

    /// Finalize any take and release the camera and the microphone. The movie
    /// is complete on disk when this returns: the app may be killed right after.
    fun stop() {
        if (!started) return
        started = false
        val open = writer
        if (state == State.Recording) end(State.Idle)
        else if (state == State.Paused) { state = State.Idle; audio.endTake() }
        afterSaving = State.Idle
        open?.await(4000)
        saveLens.run(); main.removeCallbacks(saveLens)
        cancelRamp()
        orientation.disable()
        audio.sink = null; audio.stop()
        ready = false; switching = false
        // The screen surface may die right after this, or outlive it and be
        // used again by the next start. Either way nothing draws to it from here.
        val screenSurface = pendingWindow; clearPreview(); pendingWindow = screenSurface
        val cameraQueue = cameraHandler; val renderQueue = renderHandler
        val camera = cameraThread; val render = renderThread
        cameraHandler = null; renderHandler = null; cameraThread = null; renderThread = null
        // In this order: the camera lets go of the Surface before its texture is destroyed.
        cameraQueue?.post {
            attempt++; afterClose = null
            try { device?.close() } catch (e: Exception) { }
            device = null; closing = null; session = null; request = null; opened = null; live = false
            renderQueue?.post { tearDownRender(); render?.quitSafely() }
            camera?.quitSafely()
        }
        onChange?.invoke()
    }

    /// The screen surface the preview is drawn to.
    fun setPreview(surface: Surface, width: Int, height: Int) {
        previewWidth = width; previewHeight = height; pendingWindow = surface
        renderHandler?.post { window = surface; windowWidth = width; windowHeight = height; attachWindow(); draw(0) }
    }
    /// Must return only when nothing draws to the surface any more: the system destroys it next.
    fun clearPreview() {
        pendingWindow = null
        val queue = renderHandler ?: return
        val released = CountDownLatch(1)
        queue.post { window = null; detachWindow(); released.countDown() }
        try { released.await(1500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { }
    }
    private fun redraw() { renderHandler?.post { draw(0) } }

    // ================================================================ cameras

    private fun discover(): List<Camera> {
        class Found(val id: String, val info: CameraCharacteristics, val facing: Int, val focal: Float, val diagonal: Float, val logical: Boolean)
        val found = ArrayList<Found>()
        val ids = try { manager.cameraIdList } catch (e: Exception) { emptyArray<String>() }
        for (id in ids) {
            val info = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            // Depth sensors are not backward compatible; monochrome helpers are not cameras one films with.
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in capabilities) continue
            val facing = info.get(CameraCharacteristics.LENS_FACING) ?: continue
            val focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val sensor = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val diagonal = sensor?.let { sqrt(it.width * it.width + it.height * it.height) } ?: 0f
            val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
            // Some phones list the same sensor twice under different IDs.
            if (found.any { it.facing == facing && it.logical == logical && it.focal == focal && it.diagonal == diagonal && focal > 0f }) continue
            found.add(Found(id, info, facing, focal, diagonal, logical))
        }
        val result = ArrayList<Camera>()
        for (facing in listOf(CameraMetadata.LENS_FACING_BACK, CameraMetadata.LENS_FACING_FRONT, CameraMetadata.LENS_FACING_EXTERNAL)) {
            val side = found.filter { it.facing == facing }
            val word = when (facing) { CameraMetadata.LENS_FACING_BACK -> "Back"; CameraMetadata.LENS_FACING_FRONT -> "Front"; else -> "External" }
            // Field of view as a 35 mm focal length; the lens nearest a phone's
            // usual main camera (about 26 mm) is this side's 1×.
            fun equivalent(camera: Found) = if (camera.focal > 0f && camera.diagonal > 0f) camera.focal / camera.diagonal * 43.27 else 0.0
            val reference = side.filter { equivalent(it) > 0 }.minByOrNull { abs(ln(equivalent(it) / 26.0)) }?.let { equivalent(it) } ?: 0.0
            val made = side.map { camera ->
                val ratio = if (reference > 0 && equivalent(camera) > 0) equivalent(camera) / reference else 1.0
                Camera(camera.id, facing == CameraMetadata.LENS_FACING_FRONT, word, if (ratio < 0.95) Math.round(ratio * 10) / 10.0 else Math.round(ratio * 2) / 2.0, camera.logical, camera.info)
            }
            for (camera in made) {
                // A logical camera picks its own lens while zooming: say so instead of naming one.
                camera.name = if (made.size == 1) word else if (camera.logical) "$word Auto" else word + " " + magnification(camera.multiplier)
                val same = result.count { it.name == camera.name || it.name.startsWith(camera.name + " (") }
                if (same > 0) camera.name += " (" + (same + 1) + ")"
                result.add(camera)
            }
        }
        return result
    }

    /// The most recently used camera on the other side, or the first one there.
    val oppositeCamera: Camera? get() {
        val current = camera ?: return null
        val others = cameras.filter { it.front != current.front && it.side != "External" }
        return others.firstOrNull { it.id == preferences.getString(if (current.front) "lastBack" else "lastFront", null) } ?: others.firstOrNull()
    }
    fun flipCamera() { oppositeCamera?.let { switchCamera(it.id) } }

    /// Change camera, also in the middle of a take. One change at a time.
    fun switchCamera(id: String) {
        val target = cameras.firstOrNull { it.id == id } ?: return
        val previous = camera
        if (!started || switching || state == State.Saving || target.id == previous?.id && ready) return
        cancelRamp()
        saveLens.run()
        switching = true
        cameraHandler?.post { open(target, if (ready) previous else null, null) }
        onChange?.invoke()
    }

    private fun becameLive(now: Camera, note: String?, cameraChanged: Boolean) {
        if (!started) return
        val changed = camera?.id != now.id
        camera = now; ready = true; switching = false
        zoom = savedZoom(now)
        focusLocked = !now.fixedFocus && preferences.getBoolean("focusLocked." + now.id, false)
        focusPosition = if (focusLocked && now.manualFocus && preferences.contains("lens." + now.id)) position(now, preferences.getFloat("lens." + now.id, 0f)) else null
        preferences.edit().putString("camera", now.id).putString(if (now.front) "lastFront" else "lastBack", now.id).apply()
        if (changed && cameraChanged && state == State.Recording) writer?.event("cameraSwitch", "Camera changed to " + now.name)
        if (note != null) error = note
        onChange?.invoke()
    }
    private fun cameraLost(message: String) {
        if (!started) return
        ready = false; switching = false
        // No camera means no valid capture: close the take rather than film a frozen frame.
        if (state == State.Recording) { end(State.Idle); warning = "$message The recording was saved." }
        error = message
    }

    // ---- Camera thread. ----
    private var device: CameraDevice? = null
    private var closing: CameraDevice? = null
    private var afterClose: (() -> Unit)? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var opened: Camera? = null
    private var attempt = 0
    private var live = false
    private var retries = 0
    private var zoomNow = 1.0
    private var lockNow = false
    private var lensNow = Float.NaN            // diopters to hold; NaN when the position cannot be set
    @Volatile private var reportedLens = Float.NaN
    private var focusPoint: MeteringRectangle? = null
    private val cameraExecutor = Executor { command -> cameraHandler?.post(command) }

    private fun closeThen(block: () -> Unit) {
        val current = device
        device = null; session = null; request = null; live = false
        if (current == null && closing == null) { block(); return }
        afterClose = block
        if (current == null) return
        closing = current
        try { current.close() } catch (e: Exception) { closing = null; afterClose = null; block(); return }
        // onClosed is the normal way forward. A camera service that never
        // sends it must not leave a camera change waiting for ever.
        cameraHandler?.postDelayed({ if (closing === current) { closing = null; afterClose?.let { afterClose = null; it() } } }, 2500)
    }

    /// Open `target` on the shared Surface. Until its first frame is drawn the
    /// attempt can fail in many ways; all of them end in fail(), which returns
    /// to `fallback`, the camera that was working before.
    @SuppressLint("MissingPermission")
    private fun open(target: Camera, fallback: Camera?, note: String?) {
        val mine = ++attempt
        val handler = cameraHandler ?: return
        val size = target.streamSize(quality)
        closeThen {
            if (attempt != mine) return@closeThen
            // After the old device is closed, nothing but the new camera can queue
            // a frame: the render thread adopts the new geometry with that frame.
            renderHandler?.post { await(Feed(target, size.width, size.height) { handler.post { if (attempt == mine) { live = true; retries = 0; main.post { becameLive(target, note, fallback != null) } } } }) }
            handler.postDelayed({ if (attempt == mine && !live) fail(mine, target, fallback, "did not start") }, 5000)
            try {
                manager.openCamera(target.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (attempt != mine) { camera.close(); return }
                        device = camera; opened = target
                        configure(camera, target, size, mine, fallback)
                    }
                    override fun onDisconnected(camera: CameraDevice) = lost(camera, "is in use elsewhere")
                    override fun onError(camera: CameraDevice, code: Int) = lost(camera, "reported an error ($code)")
                    private fun lost(camera: CameraDevice, reason: String) {
                        // A device that failed before onOpened is not ours yet, and still needs closing.
                        if (device !== camera) try { camera.close() } catch (e: Exception) { }
                        if (attempt == mine) fail(mine, target, fallback, reason)
                    }
                    override fun onClosed(camera: CameraDevice) {
                        if (closing !== camera) return
                        closing = null
                        afterClose?.let { afterClose = null; it() }
                    }
                }, handler)
            } catch (e: Exception) { fail(mine, target, fallback, "could not be opened") }
        }
    }

    private fun configure(camera: CameraDevice, target: Camera, size: Size, mine: Int, fallback: Camera?) {
        val surface = cameraSurface ?: return fail(mine, target, fallback, "has no preview")
        try {
            cameraTexture?.setDefaultBufferSize(size.width, size.height)
            zoomNow = savedZoom(target)
            lockNow = !target.fixedFocus && preferences.getBoolean("focusLocked." + target.id, false)
            // Only this camera's own saved position is ever applied to it.
            lensNow = if (target.manualFocus && preferences.contains("lens." + target.id)) preferences.getFloat("lens." + target.id, 0f) else Float.NaN
            reportedLens = Float.NaN; focusPoint = null
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), cameraExecutor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (attempt != mine) return
                    try {
                        session = configured
                        request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }
                        // A lock whose lens position cannot be restored focuses once, then holds.
                        submit(trigger = lockNow && lensNow.isNaN())
                    } catch (e: Exception) { fail(mine, target, fallback, "could not start") }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) { if (attempt == mine) fail(mine, target, fallback, "could not be configured") }
            }))
        } catch (e: Exception) { fail(mine, target, fallback, "could not be configured") }
    }

    private fun fail(mine: Int, target: Camera, fallback: Camera?, reason: String) {
        if (attempt != mine) return
        val wasLive = live
        attempt++
        closeThen {
            when {
                fallback != null && !wasLive -> open(fallback, null, "Could not switch to " + target.name + ". Still using " + fallback.name + ".")
                // Closing one recorder and opening the next can overlap for a moment.
                !wasLive && retries > 0 -> { retries--; cameraHandler?.postDelayed({ open(target, null, null) }, 600) }
                else -> main.post { cameraLost("The " + target.name.lowercase(Locale.US) + " camera " + reason + ".") }
            }
        }
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(captured: CameraCaptureSession, sent: CaptureRequest, result: TotalCaptureResult) {
            if (captured !== session) return
            result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE)?.let { reportedLens = it }
        }
    }

    /// Write the current zoom and focus into the repeating request.
    private fun submit(trigger: Boolean = false) {
        val current = session ?: return; val builder = request ?: return; val camera = opened ?: return
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        camera.frameRate?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (Build.VERSION.SDK_INT >= 30 && camera.zoomRatio != null) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomNow.toFloat())
        else builder.set(CaptureRequest.SCALER_CROP_REGION, crop(camera, zoomNow))
        val everywhere = arrayOf(MeteringRectangle(0, 0, camera.array.width(), camera.array.height(), 0))
        if (!camera.fixedFocus) when {
            // Autofocus off with a stated position. Exposure stays automatic.
            lockNow && camera.manualFocus && !lensNow.isNaN() -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lensNow)
            }
            // Without manual control, triggered autofocus that is never
            // triggered leaves the lens where it is: a lock, if not a restorable one.
            lockNow && camera.triggeredFocus -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            focusPoint != null && camera.focusRegions -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusPoint))
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, camera.continuousFocus ?: CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (camera.focusRegions) builder.set(CaptureRequest.CONTROL_AF_REGIONS, everywhere)
            }
        }
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            current.setRepeatingRequest(builder.build(), results, cameraHandler)
            if (trigger) {
                // The trigger travels in one request only; repeated, it would restart the scan every frame.
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                current.capture(builder.build(), results, cameraHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
        } catch (e: Exception) {
            // The session died between callbacks; the device callback reports it.
        }
    }
    /// Before Android 11 zoom is a centred crop of the sensor.
    private fun crop(camera: Camera, zoom: Double): Rect {
        val width = (camera.array.width() / zoom).toInt(); val height = (camera.array.height() / zoom).toInt()
        val left = (camera.array.width() - width) / 2; val top = (camera.array.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }
    private fun onCamera(id: String, change: (Camera) -> Unit) {
        cameraHandler?.post { val camera = opened; if (camera != null && camera.id == id && session != null) change(camera) }
    }

    // ================================================================ zoom

    private fun savedZoom(camera: Camera) = preferences.getFloat("zoom." + camera.id, 1f).toDouble().coerceIn(camera.minZoom, camera.maxZoom)
    private fun now() = SystemClock.uptimeMillis() / 1000.0

    /// Reach a user-facing magnification in `seconds`. A running ramp is
    /// replaced, and the new one starts from the zoom actually applied.
    fun setZoom(display: Double, seconds: Double) {
        val current = camera ?: return
        if (!ready || switching) return
        val target = (display / current.multiplier).coerceIn(current.minZoom, current.maxZoom)
        if (abs(target - zoom) < 0.005 && ramp == null) return
        main.removeCallbacks(rampStep)
        if (seconds <= 0 || abs(target - zoom) < 0.005) { ramp = null; applyZoom(target); return }
        ramp = ZoomTransition(zoom, target, now(), seconds)
        main.post(rampStep)
    }
    /// A pinch sets the zoom directly, by a factor of the current value.
    fun scaleZoom(factor: Double) {
        val current = camera ?: return
        if (!ready || switching || !factor.isFinite() || factor <= 0) return
        ramp = null; main.removeCallbacks(rampStep)
        applyZoom((zoom * factor).coerceIn(current.minZoom, current.maxZoom))
    }
    private val rampStep = object : Runnable {
        override fun run() {
            val transition = ramp ?: return
            val time = now()
            applyZoom(transition.value(time))
            // One request per camera frame is as fine as the picture can show.
            if (transition.isFinished(time)) ramp = null else main.postDelayed(this, 33)
        }
    }
    private fun applyZoom(value: Double) {
        val current = camera ?: return
        zoom = value
        onCamera(current.id) { zoomNow = value; submit() }
        // Remember what was applied, never the target of a ramp still running.
        main.removeCallbacks(saveZoom); main.postDelayed(saveZoom, 400)
        onChange?.invoke()
    }
    private val saveZoom = Runnable { camera?.let { preferences.edit().putFloat("zoom." + it.id, zoom.toFloat()).apply() } }
    private fun cancelRamp() {
        ramp = null; main.removeCallbacks(rampStep)
        main.removeCallbacks(saveZoom); saveZoom.run()
    }

    // ================================================================ focus

    private fun position(camera: Camera, diopters: Float) = (1.0 - diopters / camera.nearest).coerceIn(0.0, 1.0)
    /// Where autofocus has put the lens, on the same near to far scale, if the camera says.
    val reportedFocus: Double? get() { val current = camera; val lens = reportedLens; return if (current == null || !current.manualFocus || lens.isNaN()) null else position(current, lens) }

    /// Hold the lens where it is, or return to autofocus. Exposure is not touched.
    fun setFocusLocked(locked: Boolean) {
        val current = camera ?: return
        if (current.fixedFocus || !ready || locked == focusLocked) return
        focusLocked = locked
        preferences.edit().putBoolean("focusLocked." + current.id, locked).apply()
        if (!locked) { focusPosition = null; pendingLens = Float.NaN; main.removeCallbacks(saveLens); onCamera(current.id) { lockNow = false; focusPoint = null; submit() } }
        else onCamera(current.id) {
            lockNow = true; focusPoint = null
            lensNow = if (it.manualFocus) reportedLens else Float.NaN
            val held = lensNow
            submit()
            main.post {
                if (camera?.id != current.id || !focusLocked) return@post
                if (!held.isNaN()) { focusPosition = position(current, held); preferences.edit().putFloat("lens." + current.id, held).apply() }
                onChange?.invoke()
            }
        }
        onChange?.invoke()
    }
    /// Set the lens by hand, 0 near to 1 far. Implies a lock.
    fun setFocusPosition(value: Double) {
        val current = camera ?: return
        if (!current.manualFocus || !ready) return
        val diopters = (current.nearest * (1.0 - value.coerceIn(0.0, 1.0))).toFloat()
        focusLocked = true; focusPosition = value.coerceIn(0.0, 1.0)
        pendingLens = diopters
        onCamera(current.id) { lockNow = true; focusPoint = null; lensNow = diopters; submit() }
        main.removeCallbacks(saveLens); main.postDelayed(saveLens, 400)
        onChange?.invoke()
    }
    private var pendingLens = Float.NaN
    private val saveLens = Runnable {
        val current = camera
        if (current != null && !pendingLens.isNaN()) preferences.edit().putBoolean("focusLocked." + current.id, true).putFloat("lens." + current.id, pendingLens).apply()
        pendingLens = Float.NaN
    }

    /// Focus once on a point of the preview, in view pixels. A lock is never
    /// released this way. Returns whether the camera was asked.
    fun focusAt(x: Float, y: Float): Boolean {
        val current = camera ?: return false
        if (!ready || switching || focusLocked || !current.focusRegions || previewWidth <= 0 || previewHeight <= 0) return false
        val size = current.streamSize(quality)
        val natural = if (current.orientation % 180 == 0) size.width.toFloat() / size.height else size.height.toFloat() / size.width
        val place = Placement.preview(natural, current.front && !previewMirrored, if (hasTake) takeTurn else turn, displayTurn, previewWidth, previewHeight, fill)
        // Undo the drawing: view pixel to the quad, then back through mirror and rotation.
        var qx = (x / previewWidth * 2 - 1) / place.scaleX; var qy = (1 - y / previewHeight * 2) / place.scaleY
        if (abs(qx) > 1 || abs(qy) > 1) return false
        if (place.mirror) qx = -qx
        val (nx, ny) = when (place.rotation) { 90 -> Pair(-qy, qx); 180 -> Pair(-qx, -qy); 270 -> Pair(qy, -qx); else -> Pair(qx, qy) }
        // u right and v down in the upright picture, as a mirror for the front camera.
        var u = (nx + 1) / 2; val v = (1 - ny) / 2
        if (current.front) u = 1 - u
        // The upright picture is the sensor's turned clockwise by its orientation.
        val (sx, sy) = when (current.orientation) { 90 -> Pair(v, 1 - u); 180 -> Pair(1 - u, 1 - v); 270 -> Pair(1 - v, u); else -> Pair(u, v) }
        // The stream is the centre of the sensor at the stream's shape. With the
        // zoom-ratio control, coordinates already refer to the zoomed view.
        val area = if (Build.VERSION.SDK_INT >= 30 && current.zoomRatio != null) Rect(0, 0, current.array.width(), current.array.height()) else crop(current, zoom)
        val shape = size.width.toFloat() / size.height
        var width = area.width().toFloat(); var height = area.height().toFloat()
        if (width / height > shape) width = height * shape else height = width / shape
        val cx = area.exactCenterX() + (sx - 0.5f) * width; val cy = area.exactCenterY() + (sy - 0.5f) * height
        val half = min(width, height) * 0.08f
        val left = (cx - half).toInt().coerceIn(0, current.array.width() - 2); val top = (cy - half).toInt().coerceIn(0, current.array.height() - 2)
        val right = (cx + half).toInt().coerceIn(left + 1, current.array.width() - 1); val bottom = (cy + half).toInt().coerceIn(top + 1, current.array.height() - 1)
        val region = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
        onCamera(current.id) {
            if (lockNow) return@onCamera
            focusPoint = region; submit(trigger = true)
            // Then give the scene back to continuous autofocus.
            cameraHandler?.postDelayed({ if (focusPoint === region && opened?.id == current.id) { focusPoint = null; submit() } }, 4000)
        }
        return true
    }

    // ================================================================ recording

    /// Begin a take into `folder`. The picture shape is decided here, from how
    /// the device is held now, and stays until Stop.
    fun record(folder: File): Boolean {
        val current = camera
        if (state != State.Idle || !ready || switching || current == null) return false
        takeFolder = folder; takeTurn = turn; elapsedBefore = 0
        takeQuality = quality
        if (quality == Quality.UHD && !(current.supports4K && encodes(Quality.UHD, takeTurn))) { takeQuality = Quality.HD; warning = "This camera uses 1080p for recording." }
        if (!begin()) return false
        audio.beginTake()
        return true
    }
    fun pause() { if (state == State.Recording) end(State.Paused) }
    /// Paused time never enters the movie: each stretch is its own file.
    fun resume() { if (state == State.Paused && ready && !switching) begin() }
    fun finish() {
        if (state == State.Recording) end(State.Idle)
        else if (state == State.Paused) { state = State.Idle; audio.endTake(); onChange?.invoke() }
    }
    fun bookmark() { if (state == State.Recording) writer?.event("bookmark", "Bookmark") }
    fun mark(kind: String, detail: String) { if (state == State.Recording) writer?.event(kind, detail) }

    /// Called about once a second while a take runs. Stops while there is still room to finalize.
    fun checkStorage() {
        val folder = takeFolder ?: return
        if (state == State.Recording && folder.usableSpace < 120L * 1024 * 1024) {
            end(State.Idle)
            warning = "Storage is almost full. The recording was stopped and saved."
        }
    }

    private fun encodes(quality: Quality, turn: Int): Boolean {
        val width = if (turn % 180 == 0) quality.short else quality.long; val height = if (turn % 180 == 0) quality.long else quality.short
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.areSizeAndRateSupported(width, height, 30.0)
            }
        } catch (e: Exception) { false }
    }

    private fun begin(): Boolean {
        val folder = takeFolder ?: return false
        if (folder.usableSpace < 300L * 1024 * 1024) { error = "Not enough free storage to record. Free some space and try again."; return false }
        val upright = takeTurn % 180 == 0
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var number = 1
        while (File(folder, "take-$stamp-$number.mp4").exists()) number++
        val file = File(folder, "take-$stamp-$number.mp4")
        val created = try {
            CaptureSegmentWriter(file, if (upright) takeQuality.short else takeQuality.long, if (upright) takeQuality.long else takeQuality.short,
                                 takeQuality.bitrate, if (audio.running) audio.channels else 0) { finishedWriter, failure -> main.post { finished(finishedWriter, failure) } }
        } catch (e: Exception) { error = "Recording could not start. " + (e.message ?: ""); return false }
        writer = created; segmentStart = SystemClock.uptimeMillis(); state = State.Recording
        val quarter = takeTurn
        renderHandler?.post { attachEncoder(created, quarter) }
        audio.sink = created::audio
        onChange?.invoke()
        return true
    }

    private fun end(next: State) {
        val current = writer ?: return
        if (state != State.Recording) return
        elapsedBefore += SystemClock.uptimeMillis() - segmentStart
        state = State.Saving; afterSaving = next
        audio.sink = null
        // Stop drawing to the encoder first, then let it write its index.
        renderHandler?.post { detachEncoder(current); current.finish() } ?: current.finish()
        onChange?.invoke()
    }

    private fun finished(done: CaptureSegmentWriter, failure: String?) {
        if (done !== writer) return
        if (state == State.Recording) {
            // The writer gave up by itself: an encoder or storage failure.
            elapsedBefore += SystemClock.uptimeMillis() - segmentStart
            audio.sink = null; afterSaving = State.Idle
            renderHandler?.post { detachEncoder(done) }
        }
        writer = null
        state = if (failure == null) afterSaving else State.Idle
        if (state == State.Idle) audio.endTake()
        if (done.playable) onSegment?.invoke(done.file)
        if (failure != null) warning = if (done.playable) "Recording stopped. $failure The footage up to that point was saved." else "Recording stopped. $failure"
        onChange?.invoke()
    }

    // ================================================================ render thread

    /// What is needed to draw one camera's frames: the upright picture's shape and its side.
    private class Feed(val camera: Camera, width: Int, height: Int, val onFirstFrame: () -> Unit) {
        /// SurfaceTexture's matrix already turns the buffer upright for the
        /// device's natural orientation, and mirrors a front camera.
        val natural: Float = if (camera.orientation % 180 == 0) width.toFloat() / height else height.toFloat() / width
    }

    /// Where the camera picture goes on an output. Rotation and mirror are FrameProgram.draw's.
    class Placement(val rotation: Int, val mirror: Boolean, val scaleX: Float, val scaleY: Float, val frameWidth: Int, val frameHeight: Int) {
        companion object {
            /// The movie: turned upright for how the device was held, covering the canvas.
            fun movie(natural: Float, flip: Boolean, turn: Int, width: Int, height: Int): Placement {
                val ratio = (if (turn % 180 == 0) natural else 1 / natural) / (width.toFloat() / height)
                return Placement(turn, flip, max(ratio, 1f), max(1 / ratio, 1f), width, height)
            }
            /// The screen is a window: the upright picture is drawn unturned and the
            /// user's eyes do the rest. The recorded frame is a 9:16 region of it
            /// however the device is held, and is what fills or fits the display.
            /// A flip is meant in the upright movie, so seen from the screen it
            /// turns with the device: mirror∘rotate(turn) seen through rotate(−turn).
            fun preview(natural: Float, flip: Boolean, turn: Int, display: Int, width: Int, height: Int, fill: Boolean): Placement {
                val rotation = if (flip) (display + 2 * turn) % 360 else (360 - display) % 360
                val sideways = display % 180 != 0
                val shape = if (sideways) 16f / 9 else 9f / 16
                val frameWidth = if (fill) max(width.toFloat(), height * shape) else min(width.toFloat(), height * shape)
                val frameHeight = frameWidth / shape
                val ratio = (if (sideways) 1 / natural else natural) / shape
                return Placement(rotation, flip, max(ratio, 1f) * frameWidth / width, max(1 / ratio, 1f) * frameHeight / height, Math.round(frameWidth), Math.round(frameHeight))
            }
        }
    }

    @Volatile private var cameraSurface: Surface? = null
    @Volatile private var cameraTexture: SurfaceTexture? = null
    @Volatile private var pendingWindow: Surface? = null
    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0
    private var egl: EGLCore? = null
    private var program: FrameProgram? = null
    private var textureID = 0
    private var window: Surface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var screen: EGLSurface? = null
    private var encoder: EGLSurface? = null
    private var encoding: CaptureSegmentWriter? = null
    private var encodingTurn = 0
    private var feed: Feed? = null
    private var awaited: Feed? = null
    private var heldStamp = 0L
    private var hasFrame = false
    private val matrix = FloatArray(16)
    private var clockOffset = Long.MIN_VALUE   // sensor clock to System.nanoTime, per camera
    private var lag = 0L                       // how late frames arrive, to time held frames alike
    private var lastTime = 0L                  // last time given to the encoder
    private var lastArrival = 0L
    private var lastSubmit = 0L

    private fun setUpRender() {
        val core = EGLCore(); egl = core
        program = FrameProgram()
        textureID = FrameProgram.externalTexture()
        val texture = SurfaceTexture(textureID)
        texture.setOnFrameAvailableListener({ frame() }, renderHandler)
        cameraTexture = texture; cameraSurface = Surface(texture)
        hasFrame = false; feed = null; awaited = null
        window = pendingWindow; windowWidth = previewWidth; windowHeight = previewHeight
        attachWindow()
    }
    private fun tearDownRender() {
        val core = egl ?: return
        encoding = null
        encoder?.let { core.release(it) }; encoder = null
        detachWindow(); window = null
        cameraSurface?.release(); cameraSurface = null
        cameraTexture?.release(); cameraTexture = null
        program?.release(); program = null
        FrameProgram.deleteTexture(textureID)
        core.release(); egl = null
    }
    private fun attachWindow() {
        val core = egl ?: return; val target = window ?: return
        detachWindow()
        if (!target.isValid) return
        screen = try { core.windowSurface(target) } catch (e: Exception) { null }
    }
    private fun detachWindow() { screen?.let { egl?.release(it) }; screen = null }

    private fun attachEncoder(target: CaptureSegmentWriter, quarter: Int) {
        val core = egl ?: return
        encoder = try { core.windowSurface(target.surface) } catch (e: Exception) { null }
        encoding = target; encodingTurn = quarter; lastTime = 0
        // Start with the picture already on screen instead of waiting a frame.
        lastArrival = System.nanoTime(); lastSubmit = 0
        if (hasFrame) draw(lastArrival - lag)
        renderHandler?.postDelayed(holdStep, 33)
    }
    private fun detachEncoder(target: CaptureSegmentWriter) {
        if (encoding !== target) return
        encoding = null
        encoder?.let { egl?.release(it) }; encoder = null
        renderHandler?.removeCallbacks(holdStep)
    }

    /// The next camera to deliver. The frame now in the texture belongs to the
    /// previous one and stays on screen, drawn with its own geometry, until then.
    private fun await(next: Feed) {
        val texture = cameraTexture ?: return
        try { texture.updateTexImage() } catch (e: Exception) { }
        heldStamp = texture.timestamp
        awaited = next
    }

    private fun frame() {
        val texture = cameraTexture ?: return
        try { texture.updateTexImage() } catch (e: Exception) { return }
        val stamp = texture.timestamp
        val next = awaited
        if (next != null) {
            // A late notice for a frame already consumed changes nothing.
            if (hasFrame && stamp == heldStamp) return
            feed = next; awaited = null; clockOffset = Long.MIN_VALUE
            next.onFirstFrame()
        }
        val current = feed ?: return
        texture.getTransformMatrix(matrix)
        hasFrame = true
        val now = System.nanoTime()
        if (clockOffset == Long.MIN_VALUE) clockOffset = if (current.camera.realtime) now - SystemClock.elapsedRealtimeNanos() else now - stamp
        var time = stamp + clockOffset
        // A frame cannot come from the future or from half a second ago: the
        // clock assumption is wrong on this device, so fall back to arrival times.
        if (time > now || now - time > 500_000_000L) { clockOffset = now - stamp; time = now }
        lag = now - time; lastArrival = now
        draw(time)
    }

    /// While a take runs and the camera is silent, as it is during a camera
    /// change, keep the movie's clock moving with the picture already shown.
    private val holdStep = object : Runnable {
        override fun run() {
            if (encoding == null) return
            val now = System.nanoTime()
            if (hasFrame && now - lastArrival > 80_000_000L && now - lastSubmit >= 30_000_000L) draw(now - lag)
            renderHandler?.postDelayed(this, 33)
        }
    }

    /// Draw the current texture to the encoder, when `time` is a movie time, and to the screen.
    private fun draw(time: Long) {
        val core = egl ?: return; val quad = program ?: return
        val current = feed
        try {
            val target = encoding; val surface = encoder
            if (target != null && surface != null && current != null && hasFrame && time > lastTime + 2_000_000L) {
                val place = Placement.movie(current.natural, current.camera.front && !saveMirrored, encodingTurn, target.width, target.height)
                core.makeCurrent(surface)
                FrameProgram.clear(target.width, target.height)
                quad.draw(textureID, matrix, place.scaleX, place.scaleY, place.rotation, place.mirror)
                core.setPresentationTime(surface, target.videoTime(time))
                core.swap(surface)
                lastTime = time; lastSubmit = System.nanoTime()
            }
            val display = screen ?: return
            if (windowWidth <= 0 || windowHeight <= 0) return
            core.makeCurrent(display)
            FrameProgram.clear(windowWidth, windowHeight)
            if (current != null && hasFrame) {
                val place = Placement.preview(current.natural, current.camera.front && !previewMirrored, if (target != null) encodingTurn else turn, displayTurn, windowWidth, windowHeight, fill)
                // Fit shows the recorded frame and nothing around it.
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor((windowWidth - place.frameWidth) / 2, (windowHeight - place.frameHeight) / 2, place.frameWidth, place.frameHeight)
                quad.draw(textureID, matrix, place.scaleX, place.scaleY, place.rotation, place.mirror)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
            core.swap(display)
        } catch (e: Exception) {
            // A surface vanished under the draw. Its owner is already detaching it.
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    companion object {
        /// 0.5×, 1×, 2.5×
        fun magnification(value: Double): String {
            val rounded = Math.round(value * 10) / 10.0
            return (if (rounded == Math.rint(rounded)) rounded.toLong().toString() else rounded.toString()) + "×"
        }
    }
}
