package yourec

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/// The microphone, independent of any camera. Opening, closing or replacing a
/// camera never touches this engine, so the audio timeline of a take continues
/// through a camera change. Public members belong to the main thread; `sink`
/// is called on the reading thread.
class CaptureAudio(context: Context) {
    class Input(val id: Int, val name: String, val device: AudioDeviceInfo)

    companion object {
        const val sampleRate = 48_000
        const val floor = -60f                 // dBFS shown as silence
        private const val chunkFrames = 800    // three chunks make one 50 ms meter update
    }

    /// PCM 16-bit interleaved, its byte count, and the monotonic time
    /// (System.nanoTime base) at which its first frame was captured.
    @Volatile var sink: ((ByteArray, Int, Long) -> Unit)? = null
    /// Level, held peak (both dBFS) and whether the input clipped recently.
    var onLevel: ((Float, Float, Boolean) -> Unit)? = null
    /// The input list or the effective input changed.
    var onRoute: (() -> Unit)? = null
    /// Something the user must read: a failed selection, or a lost microphone.
    /// The second value is the capture event to store when a take is running.
    var onWarning: ((String, Boolean) -> Unit)? = null

    var inputs: List<Input> = emptyList(); private set
    /// The input the system reports as feeding the recorder. A request is not shown as selected until it is this.
    var active: Input? = null; private set
    var channels = 0; private set
    val running: Boolean get() = record != null
    val activeName: String get() = if (record == null) "No microphone" else active?.name ?: "Microphone"
    /// Started, yet no audio has arrived for a second: not the same as silence.
    val stalled: Boolean get() = record != null && SystemClock.uptimeMillis() - lastDelivery > 1000
    /// Another app holds the microphone and the system is feeding us zeros.
    val silenced: Boolean get() = try { record?.activeRecordingConfiguration?.isClientSilenced == true } catch (e: Exception) { false }

    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var lastDelivery = 0L
    private var requested: Int? = null     // the user's quick choice; null is the system's choice
    private var takeInput: Input? = null   // pinned while a take runs
    private var failures = 0
    private var verification: Runnable? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }
    private val routing = AudioRouting.OnRoutingChangedListener { refresh() }

    fun start(): Boolean {
        if (record != null) return true
        // Stereo where the device offers it; the camcorder source follows the lens side.
        for ((source, mask) in listOf(MediaRecorder.AudioSource.CAMCORDER to AudioFormat.CHANNEL_IN_STEREO, MediaRecorder.AudioSource.CAMCORDER to AudioFormat.CHANNEL_IN_MONO,
                                      MediaRecorder.AudioSource.MIC to AudioFormat.CHANNEL_IN_MONO)) {
            val count = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minimum = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val created = try {
                AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
                    // Room for a quarter second, so a busy moment loses nothing.
                    .setBufferSizeInBytes(max(minimum * 2, sampleRate / 4 * count * 2)).build()
            } catch (e: Exception) { null } ?: continue
            if (created.state != AudioRecord.STATE_INITIALIZED) { created.release(); continue }
            try { created.startRecording() } catch (e: Exception) { created.release(); continue }
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) { created.release(); continue }
            record = created; channels = count
            break
        }
        val current = record ?: run { onRoute?.invoke(); return false }
        current.addOnRoutingChangedListener(routing, main)
        manager.registerAudioDeviceCallback(devices, main)
        lastDelivery = SystemClock.uptimeMillis()
        val mine = ++generation
        reader = Thread({ read(current, channels, mine) }, "yourec.microphone").apply { priority = Thread.MAX_PRIORITY; start() }
        requested?.let { id -> usable().firstOrNull { it.id == id }?.let { current.setPreferredDevice(it) } }
        refresh()
        return true
    }

    fun stop() {
        val current = record ?: return
        generation++
        record = null; active = null; takeInput = null
        verification?.let { main.removeCallbacks(it) }; verification = null
        manager.unregisterAudioDeviceCallback(devices)
        current.removeOnRoutingChangedListener(routing)
        try { current.stop() } catch (e: Exception) { }
        // read() returns once the record stops; release only after the thread left it.
        try { reader?.join(500) } catch (e: InterruptedException) { }
        reader = null
        current.release()
    }

    /// Ask for an input, or for the system's own choice with null. The route is
    /// checked afterwards: Android may refuse, notably for Bluetooth headsets
    /// whose microphone link is not up.
    fun select(id: Int?) {
        val current = record ?: return
        val wanted = inputs.firstOrNull { it.id == id }
        if (id != null && wanted == null) { onWarning?.invoke("That microphone is no longer available.", false); return }
        requested = id
        current.setPreferredDevice(wanted?.device)
        if (takeInput != null && wanted != null) takeInput = wanted
        verification?.let { main.removeCallbacks(it) }
        val check = Runnable {
            verification = null
            refresh()
            val now = active
            if (wanted != null && now?.id != wanted.id) {
                requested = null
                onWarning?.invoke("Could not switch to " + wanted.name + "." + (now?.let { " Using " + it.name + "." } ?: ""), false)
            }
            // A take keeps whatever is really recording, so a later arrival cannot replace it.
            if (takeInput != null && now != null) { takeInput = now; record?.setPreferredDevice(now.device) }
        }
        verification = check
        main.postDelayed(check, 600)
    }

    /// From here to endTake the input may not change by itself: without a
    /// preferred device Android moves to a headset the moment one is connected.
    fun beginTake() {
        refresh()
        takeInput = active
        active?.let { record?.setPreferredDevice(it.device) }
    }
    /// A quick selection lasts until Stop.
    fun endTake() {
        takeInput = null; requested = null
        record?.setPreferredDevice(null)
    }

    private fun usable(): List<AudioDeviceInfo> {
        val types = hashSetOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                              AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
        if (Build.VERSION.SDK_INT >= 31) types.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && it.type in types }
    }
    private fun name(device: AudioDeviceInfo, builtIn: Int): String {
        val product = device.productName?.toString()?.trim().orEmpty()
        return when (device.type) {
            // Phones list each built-in capsule separately; the address tells them apart.
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone" + (if (builtIn > 1 && device.address.isNotEmpty()) " (" + device.address + ")" else "")
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> (product.ifEmpty { "Headset" }) + " (Bluetooth)"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line input"
            else -> product.ifEmpty { "USB microphone" }
        }
    }

    /// Read the system's list and the effective route again. Called for every
    /// device and routing change, including the ones Android makes by itself.
    private fun refresh() {
        val current = record ?: return
        val found = usable()
        val builtIn = found.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        inputs = found.map { Input(it.id, name(it, builtIn), it) }
        val routed = current.routedDevice
        active = routed?.let { device -> inputs.firstOrNull { it.id == device.id } ?: Input(device.id, name(device, builtIn), device) }
        val pinned = takeInput; val now = active
        if (pinned != null && now != null && now.id != pinned.id && verification == null) {
            if (inputs.none { it.id == pinned.id }) {
                // The recording microphone is gone. Keep the replacement for the
                // rest of the take, even if the lost one comes back.
                takeInput = now; requested = null
                current.setPreferredDevice(now.device)
                onWarning?.invoke(pinned.name + " disconnected. Using " + now.name + ".", true)
            } else current.setPreferredDevice(pinned.device)
        }
        onRoute?.invoke()
    }

    private fun read(record: AudioRecord, channels: Int, mine: Int) {
        val chunk = ByteArray(chunkFrames * channels * 2)
        val stamp = AudioTimestamp()
        var position = 0L
        var peak = 0; var metered = 0
        var hold = floor; var holdUntil = 0L; var clippedUntil = 0L
        while (generation == mine) {
            val count = record.read(chunk, 0, chunk.size)
            if (generation != mine) break
            if (count <= 0) { main.post { if (generation == mine) recover() }; return }
            val frames = count / (channels * 2)
            // The system knows when a frame reached the converter; the time at
            // which read() returned is late by however long the buffer was.
            val time = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                stamp.nanoTime + (position - stamp.framePosition) * 1_000_000_000L / sampleRate
            else System.nanoTime() - frames * 1_000_000_000L / sampleRate
            position += frames
            sink?.invoke(chunk, count, time)
            var i = 0
            while (i + 1 < count) {
                val sample = abs((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF))
                if (sample > peak) peak = sample
                i += 2
            }
            metered += frames
            if (metered < chunkFrames * 3) continue
            val now = SystemClock.uptimeMillis()
            val level = if (peak <= 0) floor else max(floor, 20f * log10(peak / 32768f))
            if (peak >= 32760) clippedUntil = now + 2000
            if (level >= hold) { hold = level; holdUntil = now + 1000 }
            else if (now > holdUntil) hold = max(level, hold - 1.5f)
            val held = hold; val clipped = now < clippedUntil
            lastDelivery = now
            main.post { if (generation == mine) onLevel?.invoke(level, held, clipped) }
            peak = 0; metered = 0
        }
    }

    /// The stream died under us, for example when its USB device was pulled
    /// faster than the system rerouted. Open it again on whatever remains.
    private fun recover() {
        val wasTake = takeInput
        stop()
        if (++failures > 3 || !start()) {
            onWarning?.invoke("No microphone audio is arriving. Check the microphone.", wasTake != null)
            onRoute?.invoke()
            return
        }
        main.postDelayed({ failures = 0 }, 10_000)
        if (wasTake != null) {
            beginTake()
            val now = active
            if (now != null && now.id != wasTake.id) onWarning?.invoke(wasTake.name + " disconnected. Using " + now.name + ".", true)
        }
    }
}
