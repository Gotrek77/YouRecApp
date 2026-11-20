package yourec

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/// DeepFilterNet3 noise reduction, after the iPhone app's DeepFilterNetEngine
/// (Kyle Howells, DeepFilterNet-mlx; Hendrik Schröter, DeepFilterNet). The DSP
/// is the iPhone app's: Vorbis-windowed 960-point STFT with 480-sample hops,
/// 32 ERB bands and 96 deep-filtered bins, the same filterbanks and initial
/// normalization states (ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json).
///
/// The network is upstream's own ONNX export, which runs over a sequence
/// rather than one hop: it runs on chunks of `chunk` frames, each after
/// `warm` frames of context that bring its recurrent state up to speed. The
/// result is close to, not identical with, the iPhone's per-hop stream.

/// Filterbanks and initial normalization states, checked as the iPhone app checks them.
class DeepFilterDSP(val erbFB: FloatArray, val erbInvFB: FloatArray, val meanNorm: FloatArray, val unitNorm: FloatArray) {
    companion object {
        const val sha256 = "3dd66deaeafd7612f7208bfbbccb9d1d9691296acbab313e17fb099d5cdfdf29"
        fun parse(bytes: ByteArray): DeepFilterDSP {
            if (bytes.size >= 1_000_000) throw AudioProcessingError.UnavailableModel()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format("%02x", it) }
            if (digest != sha256) throw AudioProcessingError.UnavailableModel()
            val fields = Fields.of(JSON.parse(bytes))
            fun values(name: String, count: Int): FloatArray {
                val list = fields.list(name)
                if (list.size != count) throw AudioProcessingError.UnavailableModel()
                return FloatArray(count) { Fields.double(list[it]).toFloat().also { v -> if (!v.isFinite()) throw AudioProcessingError.UnavailableModel() } }
            }
            return DeepFilterDSP(values("erb_fb", 481 * 32), values("erb_inv_fb", 32 * 481), values("mean_norm_state", 32), values("unit_norm_state", 96))
        }
    }
}

/// The network over `frames` frames. `erb` is [frames × 32]; `spec` is the real
/// plane then the imaginary plane, each [frames × 96]. Input position j holds
/// the features of frame j + 2 (the network's lookahead). Fills `mask`
/// [frames × 32] and `coefs` [frames × 96 × 5 × (re, im)].
interface DeepFilterModel : AutoCloseable {
    fun run(erb: FloatArray, spec: FloatArray, frames: Int, mask: FloatArray, coefs: FloatArray)
}

/// Complex FFT of any size made of the factors 2, 3 and 5.
class MixedRadixFFT(private val n: Int) {
    private val cosTable = DoubleArray(n) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n) { sin(2 * PI * it / n) }
    private val factors: IntArray
    private val scratchRe = FloatArray(n); private val scratchIm = FloatArray(n)
    private val tRe = DoubleArray(5); private val tIm = DoubleArray(5)
    init {
        val list = ArrayList<Int>(); var rest = n
        for (p in intArrayOf(2, 3, 5)) while (rest % p == 0) { list.add(p); rest /= p }
        require(rest == 1) { "FFT size $n" }
        factors = list.toIntArray()
    }
    /// Forward when `sign` is −1 (e^{−2πi kn/N}), inverse unscaled when +1.
    fun transform(re: FloatArray, im: FloatArray, outRe: FloatArray, outIm: FloatArray, sign: Int) {
        step(re, im, 0, 1, n, outRe, outIm, 0, 0, sign)
    }
    private fun step(re: FloatArray, im: FloatArray, offset: Int, stride: Int, size: Int, outRe: FloatArray, outIm: FloatArray, out: Int, level: Int, sign: Int) {
        if (size == 1) { outRe[out] = re[offset]; outIm[out] = im[offset]; return }
        val p = factors[level]; val m = size / p
        for (q in 0 until p) step(re, im, offset + q * stride, stride * p, m, outRe, outIm, out + q * m, level + 1, sign)
        val unit = n / size
        for (k in 0 until m) {
            for (q in 0 until p) {
                val a = outRe[out + q * m + k].toDouble(); val b = outIm[out + q * m + k].toDouble()
                val index = (q * k * unit) % n
                val c = cosTable[index]; val s = sign * sinTable[index]
                tRe[q] = a * c - b * s; tIm[q] = a * s + b * c
            }
            for (r in 0 until p) {
                var sr = 0.0; var si = 0.0
                for (q in 0 until p) {
                    val index = ((q * r) % p) * (n / p)
                    val c = cosTable[index]; val s = sign * sinTable[index]
                    sr += tRe[q] * c - tIm[q] * s; si += tRe[q] * s + tIm[q] * c
                }
                scratchRe[r] = sr.toFloat(); scratchIm[r] = si.toFloat()
            }
            for (r in 0 until p) { outRe[out + k + r * m] = scratchRe[r]; outIm[out + k + r * m] = scratchIm[r] }
        }
    }
}

/// One channel. Each 480-sample hop in gives the enhanced hop `latency` hops earlier.
class DeepFilterStream(private val dsp: DeepFilterDSP, private val model: DeepFilterModel, private val chunk: Int = 1500, private val warm: Int = 300) : AutoCloseable {
    companion object {
        const val hop = 480; const val fftSize = 960; const val bins = 481; const val nbErb = 32; const val nbDf = 96
        const val order = 5; const val dfLookahead = 2; const val convLookahead = 2
        const val alpha = 0.99f
        private val window = FloatArray(fftSize) { val inner = sin(0.5 * PI * (it + 0.5) / (fftSize / 2)); sin(0.5 * PI * inner * inner).toFloat() }
        private val wnorm = 1f / (fftSize.toFloat() * fftSize) * (2 * hop)
    }
    /// Hops between a hop in and its enhanced version out.
    val latency = chunk + 2
    private val fft = MixedRadixFFT(fftSize)
    private val analysisMem = FloatArray(hop); private val synthesisMem = FloatArray(hop)
    private val meanState = dsp.meanNorm.copyOf(); private val unitState = dsp.unitNorm.copyOf()
    private val featureCapacity = warm + chunk + 3
    private val erbFeatures = FloatArray(featureCapacity * nbErb)
    private val specFeatures = FloatArray(featureCapacity * nbDf * 2)
    private val spectrumCapacity = chunk + 6
    private val spectrumRe = FloatArray(spectrumCapacity * bins); private val spectrumIm = FloatArray(spectrumCapacity * bins)
    private val outputCapacity = chunk + 8
    private val outputs = FloatArray(outputCapacity * hop)
    private var received = 0L      // frames analysed
    private var nextChunk = 0L     // first frame of the next chunk to run
    private var ready = -1L        // last output hop made
    private var pushed = 0L        // hops in
    // Work buffers.
    private val frameRe = FloatArray(fftSize); private val frameIm = FloatArray(fftSize)
    private val binRe = FloatArray(fftSize); private val binIm = FloatArray(fftSize)
    private val gains = FloatArray(bins); private val power = FloatArray(bins)
    private var erbIn = FloatArray(0); private var specIn = FloatArray(0); private var maskOut = FloatArray(0); private var coefOut = FloatArray(0)

    /// Push one hop; fill `output` with the enhanced hop `latency` hops ago (silence before the first).
    fun process(input: FloatArray, output: FloatArray) {
        analyse(input)
        pushed++
        while (received >= nextChunk + chunk + convLookahead) { run(nextChunk); nextChunk += chunk }
        val wanted = pushed - 1 - latency
        if (wanted < 0) { java.util.Arrays.fill(output, 0, hop, 0f); return }
        check(wanted <= ready) { "DeepFilterNet output not ready" }
        System.arraycopy(outputs, ((wanted % outputCapacity) * hop).toInt(), output, 0, hop)
    }

    private fun analyse(input: FloatArray) {
        for (i in 0 until hop) { frameRe[i] = analysisMem[i] * window[i]; frameRe[hop + i] = input[i] * window[hop + i] }
        System.arraycopy(input, 0, analysisMem, 0, hop)
        java.util.Arrays.fill(frameIm, 0f)
        fft.transform(frameRe, frameIm, binRe, binIm, -1)
        val t = received
        val s = ((t % spectrumCapacity) * bins).toInt()
        for (k in 0 until bins) {
            val re = binRe[k] * wnorm; val im = binIm[k] * wnorm
            spectrumRe[s + k] = re; spectrumIm[s + k] = im; power[k] = re * re + im * im
        }
        val f = (t % featureCapacity).toInt()
        for (b in 0 until nbErb) {
            var sum = 0f
            for (k in 0 until bins) sum += power[k] * dsp.erbFB[k * nbErb + b]
            val x = 10f * log10(sum + 1e-10f)
            meanState[b] = x * (1 - alpha) + meanState[b] * alpha
            erbFeatures[f * nbErb + b] = (x - meanState[b]) / 40f
        }
        for (k in 0 until nbDf) {
            val re = spectrumRe[s + k]; val im = spectrumIm[s + k]
            unitState[k] = sqrt(re * re + im * im) * (1 - alpha) + unitState[k] * alpha
            val norm = sqrt(maxOf(unitState[k], 1e-10f))
            specFeatures[(f * nbDf + k) * 2] = re / norm; specFeatures[(f * nbDf + k) * 2 + 1] = im / norm
        }
        received++
    }

    private fun run(start: Long) {
        val first = maxOf(0L, start - warm)
        val frames = (start + chunk - first).toInt()
        if (erbIn.size < frames * nbErb) {
            erbIn = FloatArray(frames * nbErb); specIn = FloatArray(frames * nbDf * 2)
            maskOut = FloatArray(frames * nbErb); coefOut = FloatArray(frames * nbDf * order * 2)
        }
        for (j in 0 until frames) {
            val f = ((first + j + convLookahead) % featureCapacity).toInt()
            System.arraycopy(erbFeatures, f * nbErb, erbIn, j * nbErb, nbErb)
            for (k in 0 until nbDf) {
                specIn[j * nbDf + k] = specFeatures[(f * nbDf + k) * 2]
                specIn[(frames + j) * nbDf + k] = specFeatures[(f * nbDf + k) * 2 + 1]
            }
        }
        model.run(erbIn, specIn, frames, maskOut, coefOut)
        for (t in start until start + chunk) {
            val j = (t - first).toInt()
            val s = ((t % spectrumCapacity) * bins).toInt()
            for (k in 0 until bins) {
                var g = 0f
                for (b in 0 until nbErb) g += maskOut[j * nbErb + b] * dsp.erbInvFB[b * bins + k]
                gains[k] = g
            }
            for (k in 0 until bins) { binRe[k] = spectrumRe[s + k] * gains[k]; binIm[k] = spectrumIm[s + k] * gains[k] }
            // Deep filter: frames t−2 … t+2 weighted by complex coefficients.
            for (k in 0 until nbDf) {
                var sr = 0f; var si = 0f
                for (o in 0 until order) {
                    val frame = t - (order - dfLookahead - 1) + o
                    if (frame < 0) continue
                    val p = ((frame % spectrumCapacity) * bins).toInt() + k
                    val xr = spectrumRe[p]; val xi = spectrumIm[p]
                    val c = ((j * nbDf + k) * order + o) * 2
                    val cr = coefOut[c]; val ci = coefOut[c + 1]
                    sr += xr * cr - xi * ci; si += xr * ci + xi * cr
                }
                binRe[k] = sr; binIm[k] = si
            }
            synthesise(t)
        }
    }

    private fun synthesise(t: Long) {
        for (k in 0 until bins) { frameRe[k] = binRe[k] / wnorm; frameIm[k] = binIm[k] / wnorm }
        for (k in 1 until fftSize / 2) { frameRe[fftSize - k] = frameRe[k]; frameIm[fftSize - k] = -frameIm[k] }
        fft.transform(frameRe, frameIm, binRe, binIm, 1)
        // Frame t's first half completes hop t − 1: the analysis window's first half lies one hop back.
        val h = t - 1
        for (i in 0 until hop) {
            val value = binRe[i] / fftSize * window[i] + synthesisMem[i]
            if (h >= 0) outputs[((h % outputCapacity) * hop).toInt() + i] = value
        }
        for (i in 0 until hop) synthesisMem[i] = binRe[hop + i] / fftSize * window[hop + i]
        if (h >= 0) ready = h
    }

    /// The network is shared by the channels and closed by its owner.
    override fun close() { }
}
