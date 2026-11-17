package yourec

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import java.io.File

/// The editor's player. It renders the current edit directly from its sources:
/// no intermediate movie is made, so a cut is visible the moment it is committed.
/// Sound is the clock while playing; the picture follows it frame by frame.
/// Public members belong to the main thread.
class EditorController {
    var playhead = 0.0; private set
    var playing = false; private set
    var duration = 0.0; private set
    var canvas = CanvasSize(1920.0, 1080.0); private set
    /// Called on the main thread when playhead, playing or canvas changed.
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("yourec-preview").apply { start() }
    private val render = Handler(renderThread.looper)
    // Render thread state.
    private var egl: EGLCore? = null
    private var program: FrameProgram? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var plan: RenderPlan? = null
    private var sources: RenderSources? = null
    @Volatile private var wanted = 0.0
    @Volatile private var drawQueued = false
    // Playback.
    private var audio: AudioPlayback? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            val player = audio ?: return
            val time = player.time()
            if (player.finished || time >= duration) { playhead = duration; pause(); return }
            playhead = time
            requestDraw()
            onChange?.invoke()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /// Show a project's current edit. The playhead is kept, limited to the new duration.
    fun load(project: Project, folder: File) {
        pause()
        val edit = project.edit
        val next = RenderPlan(edit)
        // Committing an edit must not close and reopen every decoder.
        val files = audioSources?.takeIf { it.key == RenderSources(project, folder).key } ?: RenderSources(project, folder)
        duration = next.duration.seconds
        canvas = files.canvas(edit)
        playhead = playhead.coerceIn(0.0, duration)
        render.post {
            if (sources !== files) sources?.releaseFrames()
            plan = next; sources = files
        }
        audioSources?.let { old -> if (old !== files) audioThreadRelease(old) }
        audioSources = files; audioPlan = next
        requestDraw()
        onChange?.invoke()
    }
    private var audioSources: RenderSources? = null
    private var audioPlan: RenderPlan? = null
    private fun audioThreadRelease(old: RenderSources) { Thread { old.releaseSound() }.start() }

    /// The preview surface appeared or changed size.
    fun attach(surface: SurfaceTexture) {
        render.post {
            try {
                val core = egl ?: EGLCore().also { egl = it; it.makeCurrent(null); program = FrameProgram() }
                core.release(window)
                window = core.windowSurface(surface)
            } catch (e: Exception) { window = EGL14.EGL_NO_SURFACE }
        }
        requestDraw()
    }
    /// The surface is going away; nothing may be drawn to it after this returns.
    fun detach() {
        val done = Object(); var finished = false
        // After release() the thread is gone and took its surfaces with it.
        if (!render.post {
            egl?.release(window); window = EGL14.EGL_NO_SURFACE
            synchronized(done) { finished = true; done.notifyAll() }
        }) return
        synchronized(done) { val limit = System.currentTimeMillis() + 1000; while (!finished && System.currentTimeMillis() < limit) done.wait(100) }
    }

    fun seek(time: Double) {
        if (playing) pause()
        playhead = time.coerceIn(0.0, duration)
        requestDraw()
        onChange?.invoke()
    }
    fun togglePlay() { if (playing) pause() else play() }
    fun play() {
        val plan = audioPlan ?: return; val files = audioSources ?: return
        if (playing || duration <= 0) return
        // Play at the end starts again from the beginning.
        if (playhead >= duration - 1.0 / 30) playhead = 0.0
        audio = AudioPlayback(plan, files, playhead).also { it.start() }
        playing = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        onChange?.invoke()
    }
    fun pause() {
        if (!playing) return
        playing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        audio?.stop(); audio = null
        requestDraw()
        onChange?.invoke()
    }
    /// Another screen, another app or an export needs the hardware decoders.
    /// They reopen on the next frame drawn.
    fun suspend() { pause(); render.post { sources?.releaseFrames() } }
    /// Leave the editor: stop playback and close every decoder.
    fun release() {
        pause()
        val files = audioSources; audioSources = null; audioPlan = null
        files?.let { audioThreadRelease(it) }
        render.post {
            sources?.releaseFrames(); sources = null; plan = null
            program?.release(); program = null
            egl?.let { it.release(window); it.release() }; egl = null; window = EGL14.EGL_NO_SURFACE
            renderThread.quitSafely()
        }
    }

    // Many seeks can arrive per frame while scrubbing. Only the newest matters.
    private fun requestDraw() {
        wanted = playhead
        if (drawQueued) return
        drawQueued = true
        render.post { drawQueued = false; draw(wanted) }
    }
    private fun draw(time: Double) {
        val core = egl ?: return; val shader = program ?: return
        if (window == EGL14.EGL_NO_SURFACE) return
        try {
            core.makeCurrent(window)
            val width = core.width(window); val height = core.height(window)
            FrameProgram.clear(width, height)
            // The last frame stays on screen at the very end, as a player would leave it.
            val moment = EditTime.seconds(if (time >= duration && duration > 0) duration - 1.0 / 60 else time)
            val picture = plan?.picture(moment)
            val reader = picture?.let { sources?.frames(it.source) }
            try {
                if (picture != null && reader != null && reader.show(picture.sourceTime)) {
                    val fit = RenderPlan.fit(reader.video.displayWidth, reader.video.displayHeight, width, height)
                    shader.draw(reader.texture, reader.matrix, fit.scaleX, fit.scaleY)
                }
            } catch (e: Exception) {
                // The system can reclaim a decoder at any time. Close this one; the next frame opens a new one.
                picture?.let { sources?.drop(it.source) }
            }
            core.swap(window)
        } catch (e: Exception) {
            // A surface destroyed mid-frame: skip this frame.
        }
    }

    /// Mixes and plays the project sound from one start time, and reports how far
    /// the listener has actually heard.
    private class AudioPlayback(plan: RenderPlan, private val sources: RenderSources, start: Double) {
        private val first = AudioMix.frame(EditTime.seconds(start))
        private val total = AudioMix.frame(plan.duration)
        private val mix = AudioMix(plan.timeline) { sources.sound(it) }
        private val track: AudioTrack
        private val stamp = AudioTimestamp()   // guarded by this
        @Volatile private var running = true
        @Volatile var finished = false; private set
        private val thread = Thread({ pump() }, "yourec-playback")

        init {
            val format = AudioFormat.Builder().setSampleRate(AudioMix.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val minimum = AudioTrack.getMinBufferSize(AudioMix.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                format, maxOf(minimum, AudioMix.sampleRate / 5 * AudioMix.channels * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
        private var began = 0L
        @Volatile private var silent = false   // no output device is taking sound
        fun start() {
            began = System.nanoTime()
            try { track.play() } catch (e: IllegalStateException) { silent = true }
            thread.start()
        }
        private fun pump() {
            try {
                val block = FloatArray(2048 * AudioMix.channels)
                var position = first
                while (running && position < total) {
                    val count = minOf(2048L, total - position).toInt()
                    mix.render(position, block, count)
                    var written = 0
                    while (running && written < count * AudioMix.channels) {
                        val n = track.write(block, written, count * AudioMix.channels - written, AudioTrack.WRITE_BLOCKING)
                        // Nothing accepts the sound: keep the mix moving at its own pace so
                        // the picture still plays, timed by the clock instead.
                        if (n <= 0) { silent = true; Thread.sleep(2048L * 1000 / AudioMix.sampleRate); break }
                        written += n
                    }
                    position += count
                }
                if (running) {
                    // Let what was written be heard before reporting the end. The track
                    // keeps playing: stopping it would reset the position being watched.
                    java.util.Arrays.fill(block, 0f)
                    track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                    val limit = System.nanoTime() + 1_500_000_000L
                    while (running && !silent && heard() < total - first - 480 && System.nanoTime() < limit) Thread.sleep(10)
                }
            } catch (e: Exception) {
                // A decoder that failed mid-playback ends playback; it must not end the app.
            }
            finished = true
        }
        /// Frames the device has played. A timestamp accounts for output latency;
        /// the head position is the fallback before the first one is available.
        @Synchronized private fun heard(): Long {
            if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                val since = (System.nanoTime() - stamp.nanoTime) * AudioMix.sampleRate / 1_000_000_000L
                return stamp.framePosition + (if (track.playState == AudioTrack.PLAYSTATE_PLAYING) maxOf(0L, since) else 0L)
            }
            return track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        fun time(): Double {
            val wall = (System.nanoTime() - began) * AudioMix.sampleRate / 1_000_000_000L
            var frames = heard()
            // A device that reports no progress half a second in is not playing:
            // without this the playhead would stay where Play was pressed.
            if (frames == 0L && wall > AudioMix.sampleRate / 2) silent = true
            if (silent) frames = wall
            return (first + minOf(frames, total - first)).toDouble() / AudioMix.sampleRate
        }
        fun stop() {
            running = false
            try { track.pause(); track.flush() } catch (e: IllegalStateException) { }
            thread.join(1500)
            track.release()
        }
    }
}
