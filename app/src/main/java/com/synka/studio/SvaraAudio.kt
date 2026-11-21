package com.synka.studio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 12-stage Transposed Direct Form II (TDF-II) parametric audio processor for Media3 ExoPlayer.
 * Stages:
 *   - Stage 0: Low-shelf (Sub-Rumble + Bass Depth/Punch globe)
 *   - Stages 1..10: 10-band parametric peaking EQ (+ Mid-Bass / Vocal / Treble contour blend)
 *   - Stage 11: High-shelf (Air contour + Soundstage Air globe)
 * Followed by M/S stereo width, Night Dialogue dynamic leveling, L/R balance,
 * Anti-Crackle soft-knee limiter, and lock-free waveform telemetry.
 */
@OptIn(UnstableApi::class)
class SvaraAudio : BaseAudioProcessor() {

    companion object {
        const val WAVEFORM_BARS: Int = 48
        private const val STAGE_COUNT: Int = 12
        private const val MAX_CHANNELS: Int = 8
        private const val DENORMAL_EPS: Double = 1e-20
    }

    private data class BiquadCoeffs(
        val b0: Double = 1.0,
        val b1: Double = 0.0,
        val b2: Double = 0.0,
        val a1: Double = 0.0,
        val a2: Double = 0.0
    )

    private class DspSnapshot(
        val sampleRate: Int,
        val bypassed: Boolean,
        val preAmpGain: Float,
        val coeffs: Array<BiquadCoeffs>,
        val stereoWidthGain: Float,
        val nightDialogueNorm: Float,
        val antiCrackleNorm: Float,
        val leftGain: Float,
        val rightGain: Float
    )

    @Volatile
    var activeSampleRate: Int = 48_000
        private set

    @Volatile
    var activeEncodingLabel: String = "Idle"
        private set

    @Volatile
    private var latestParams: SvaraParams = SvaraParams()

    @Volatile
    private var latestBypassed: Boolean = false

    @Volatile
    private var snapshot: DspSnapshot = buildSnapshot(
        params = SvaraParams(),
        bypassed = false,
        sampleRate = 48_000
    )

    // TDF-II delay states: [channel * STAGE_COUNT + stage]
    private val z1 = DoubleArray(MAX_CHANNELS * STAGE_COUNT)
    private val z2 = DoubleArray(MAX_CHANNELS * STAGE_COUNT)

    // Smooth envelope tracker for Night Dialogue compression
    private var dialogueEnvelope: Float = 0.05f

    // Lock-free waveform bar magnitudes (0f..1f stored as Float bits)
    private val waveformBits = AtomicIntegerArray(WAVEFORM_BARS)

    // Scratch accumulation buffer owned exclusively by the audio rendering thread
    private val frameBarPeaks = FloatArray(WAVEFORM_BARS)

    fun updateParams(params: SvaraParams, bypassed: Boolean = false) {
        val normalized = params.normalized()
        latestParams = normalized
        latestBypassed = bypassed
        snapshot = buildSnapshot(
            params = normalized,
            bypassed = bypassed,
            sampleRate = activeSampleRate.coerceAtLeast(8_000)
        )
    }

    fun readWaveform(dest: FloatArray) {
        val count = min(dest.size, WAVEFORM_BARS)
        for (i in 0 until count) {
            dest[i] = Float.fromBits(waveformBits.get(i)).coerceIn(0f, 1f)
        }
        for (i in count until dest.size) {
            dest[i] = 0f
        }
    }

    fun clearWaveform() {
        for (i in 0 until WAVEFORM_BARS) {
            waveformBits.set(i, 0)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.channelCount > MAX_CHANNELS) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        activeSampleRate = sr
        activeEncodingLabel = when (encoding) {
            C.ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
            C.ENCODING_PCM_16BIT -> "PCM 16-bit"
            else -> "PCM"
        }

        resetFilterStates()
        snapshot = buildSnapshot(
            params = latestParams,
            bypassed = latestBypassed,
            sampleRate = sr
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val startPos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val byteCount = limit - startPos
        if (byteCount <= 0) return

        val format = inputAudioFormat
        val channels = format.channelCount.coerceIn(1, MAX_CHANNELS)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frameCount = byteCount / bytesPerFrame
        if (frameCount <= 0) {
            inputBuffer.position(limit)
            return
        }

        val activeSnap = snapshot
        val outBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)
        frameBarPeaks.fill(0f)

        val coeffs = activeSnap.coeffs
        val bypassed = activeSnap.bypassed
        val preAmpGain = activeSnap.preAmpGain
        val widthGain = activeSnap.stereoWidthGain
        val nightNorm = activeSnap.nightDialogueNorm
        val limiterNorm = activeSnap.antiCrackleNorm
        val leftGain = activeSnap.leftGain
        val rightGain = activeSnap.rightGain

        // Night Dialogue envelope time constants (sample-rate aware)
        val srF = activeSnap.sampleRate.toFloat().coerceAtLeast(8_000f)
        val attackCoeff = 1f - kotlin.math.exp(-1f / (0.012f * srF))
        val releaseCoeff = 1f - kotlin.math.exp(-1f / (0.180f * srF))

        val frameSamples = FloatArray(channels)

        for (frame in 0 until frameCount) {
            // 1. Read frame samples normalized to -1f..1f
            for (ch in 0 until channels) {
                frameSamples[ch] = if (isFloat) {
                    inputBuffer.float
                } else {
                    inputBuffer.short / 32768f
                }
            }

            if (!bypassed) {
                // 2. Pre-amp + 12-stage TDF-II Biquad cascade per channel
                for (ch in 0 until channels) {
                    var x = (frameSamples[ch] * preAmpGain).toDouble()
                    val chOffset = ch * STAGE_COUNT

                    for (stage in 0 until STAGE_COUNT) {
                        val c = coeffs[stage]
                        val idx = chOffset + stage
                        val y = c.b0 * x + z1[idx]
                        var nextZ1 = c.b1 * x - c.a1 * y + z2[idx]
                        var nextZ2 = c.b2 * x - c.a2 * y

                        if (abs(nextZ1) < DENORMAL_EPS || !nextZ1.isFinite()) nextZ1 = 0.0
                        if (abs(nextZ2) < DENORMAL_EPS || !nextZ2.isFinite()) nextZ2 = 0.0

                        z1[idx] = nextZ1
                        z2[idx] = nextZ2
                        x = if (y.isFinite()) y else 0.0
                    }

                    frameSamples[ch] = x.toFloat()
                }

                // 3. Stereo Mid/Side Soundstage Width (only on 2-channel stereo)
                if (channels == 2 && abs(widthGain - 1f) > 0.001f) {
                    val mid = 0.5f * (frameSamples[0] + frameSamples[1])
                    val side = 0.5f * (frameSamples[0] - frameSamples[1]) * widthGain
                    frameSamples[0] = mid + side
                    frameSamples[1] = mid - side
                }

                // 4. Night Dialogue dynamic range leveling
                if (nightNorm > 0.005f) {
                    var peakAbs = 0f
                    for (ch in 0 until channels) {
                        peakAbs = max(peakAbs, abs(frameSamples[ch]))
                    }
                    val coeff = if (peakAbs > dialogueEnvelope) attackCoeff else releaseCoeff
                    dialogueEnvelope += (peakAbs - dialogueEnvelope) * coeff

                    val targetLevel = 0.28f
                    val ratio = 1f + nightNorm * 3.0f
                    val env = dialogueEnvelope.coerceAtLeast(0.02f)
                    val desiredGain = (targetLevel / env).pow(1f - 1f / ratio).coerceIn(0.42f, 1.85f)
                    val blendedGain = 1f + (desiredGain - 1f) * nightNorm
                    for (ch in 0 until channels) {
                        frameSamples[ch] *= blendedGain
                    }
                }

                // 5. L/R Balance (stereo)
                if (channels == 2) {
                    frameSamples[0] *= leftGain
                    frameSamples[1] *= rightGain
                }

                // 6. Anti-Crackle Soft-Knee Limiter
                for (ch in 0 until channels) {
                    frameSamples[ch] = applySoftLimiter(frameSamples[ch], limiterNorm)
                }
            }

            // 7. Accumulate waveform bar energy and write output frame
            var monoAbs = 0f
            for (ch in 0 until channels) {
                val sample = frameSamples[ch].coerceIn(-1f, 1f)
                monoAbs += abs(sample)
                if (isFloat) {
                    outBuffer.putFloat(sample)
                } else {
                    val int16 = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    outBuffer.putShort(int16)
                }
            }
            monoAbs /= channels.toFloat()

            val barIdx = ((frame.toLong() * WAVEFORM_BARS) / frameCount.toLong())
                .toInt()
                .coerceIn(0, WAVEFORM_BARS - 1)
            if (monoAbs > frameBarPeaks[barIdx]) {
                frameBarPeaks[barIdx] = monoAbs
            }
        }

        // Smoothly publish waveform telemetry for UI polling
        for (i in 0 until WAVEFORM_BARS) {
            val prev = Float.fromBits(waveformBits.get(i))
            val target = sqrt(frameBarPeaks[i].coerceIn(0f, 1f))
            val smoothed = if (target >= prev) {
                prev + (target - prev) * 0.65f
            } else {
                prev * 0.78f + target * 0.22f
            }.coerceIn(0f, 1f)
            waveformBits.set(i, smoothed.toBits())
        }

        inputBuffer.position(limit)
        outBuffer.flip()
    }

    override fun onFlush() {
        resetFilterStates()
    }

    override fun onReset() {
        resetFilterStates()
        clearWaveform()
        activeEncodingLabel = "Idle"
    }

    private fun resetFilterStates() {
        z1.fill(0.0)
        z2.fill(0.0)
        dialogueEnvelope = 0.05f
    }

    private fun applySoftLimiter(sample: Float, strength: Float): Float {
        if (!sample.isFinite()) return 0f
        if (strength <= 0.005f) return sample.coerceIn(-1f, 1f)

        val threshold = (0.96f - strength * 0.26f).coerceIn(0.65f, 0.96f)
        val absVal = abs(sample)
        if (absVal <= threshold) return sample

        val headroom = (1f - threshold).coerceAtLeast(0.04f)
        val excess = (absVal - threshold) / headroom
        val limited = threshold + headroom * tanh(excess.toDouble()).toFloat()
        return if (sample >= 0f) limited else -limited
    }

    private fun buildSnapshot(
        params: SvaraParams,
        bypassed: Boolean,
        sampleRate: Int
    ): DspSnapshot {
        val sr = sampleRate.toDouble().coerceAtLeast(8_000.0)
        val nyquistSafeMax = sr * 0.46

        val coeffs = Array(STAGE_COUNT) { BiquadCoeffs() }

        // Stage 0: Low-shelf combining SU (Sub-Rumble) + Bass Punch & Depth globe
        val lowShelfFreq = (75.0 + params.bassDepthX * 35.0).coerceIn(35.0, min(140.0, nyquistSafeMax))
        val lowShelfGainDb = (params.su + params.bassDepthY * 5.0f).toDouble().coerceIn(-18.0, 18.0)
        coeffs[0] = designLowShelf(lowShelfFreq, lowShelfGainDb, sr)

        // Stages 1..10: 10-band parametric EQ + 5-zone contour & night dialogue blend
        val nightSpeechBoostDb = (params.nightDialogue / 100f) * 4.0f
        val defaultFreqs = SvaraParams.DEFAULT_FREQUENCIES_HZ
        for (i in 0 until 10) {
            val band = params.bands.getOrElse(i) { EqBand(freqHz = defaultFreqs[i]) }.clamped()
            val contourOffset = when (i) {
                2 -> params.mi * 0.55f
                3 -> params.mi * 0.45f
                5 -> params.vo * 0.55f + nightSpeechBoostDb * 0.6f
                6 -> params.vo * 0.55f + nightSpeechBoostDb * 0.8f
                7 -> params.tr * 0.55f + nightSpeechBoostDb * 0.3f
                8 -> params.tr * 0.55f
                else -> 0f
            }
            val totalGainDb = (band.gainDb + contourOffset).toDouble().coerceIn(-20.0, 20.0)
            val freq = band.freqHz.toDouble().coerceIn(20.0, nyquistSafeMax)
            val q = band.q.toDouble().coerceIn(0.1, 20.0)
            coeffs[i + 1] = designPeakingEq(freq, q, totalGainDb, sr)
        }

        // Stage 11: High-shelf combining AI (Air) + Soundstage Air globe Y
        val highShelfFreq = min(10_500.0, nyquistSafeMax)
        val highShelfGainDb = (params.ai + params.airWidthY * 5.0f).toDouble().coerceIn(-18.0, 18.0)
        coeffs[11] = designHighShelf(highShelfFreq, highShelfGainDb, sr)

        val preAmpGain = 10.0.pow(params.preAmpDb.toDouble() / 20.0).toFloat()
        val stereoWidthGain = (1f + params.airWidthX * 0.55f).coerceIn(0.35f, 1.65f)
        val nightDialogueNorm = (params.nightDialogue / 100f).coerceIn(0f, 1f)
        val antiCrackleNorm = (params.antiCrackle / 100f).coerceIn(0f, 1f)

        val bal = (params.lrBalance / 100f).coerceIn(-1f, 1f)
        val leftGain = if (bal > 0f) (1f - bal).coerceIn(0f, 1f) else 1f
        val rightGain = if (bal < 0f) (1f + bal).coerceIn(0f, 1f) else 1f

        return DspSnapshot(
            sampleRate = sampleRate,
            bypassed = bypassed,
            preAmpGain = preAmpGain,
            coeffs = coeffs,
            stereoWidthGain = stereoWidthGain,
            nightDialogueNorm = nightDialogueNorm,
            antiCrackleNorm = antiCrackleNorm,
            leftGain = leftGain,
            rightGain = rightGain
        )
    }

    private fun designPeakingEq(
        freqHz: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double
    ): BiquadCoeffs {
        if (abs(gainDb) < 0.01) return BiquadCoeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / a

        return normalizeBiquad(b0, b1, b2, a0, a1, a2)
    }

    private fun designLowShelf(
        freqHz: Double,
        gainDb: Double,
        sampleRate: Double
    ): BiquadCoeffs {
        if (abs(gainDb) < 0.01) return BiquadCoeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0) // S = 1.0 Butterworth slope
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha

        return normalizeBiquad(b0, b1, b2, a0, a1, a2)
    }

    private fun designHighShelf(
        freqHz: Double,
        gainDb: Double,
        sampleRate: Double
    ): BiquadCoeffs {
        if (abs(gainDb) < 0.01) return BiquadCoeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0) // S = 1.0 Butterworth slope
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha

        return normalizeBiquad(b0, b1, b2, a0, a1, a2)
    }

    private fun normalizeBiquad(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double
    ): BiquadCoeffs {
        if (abs(a0) < 1e-12 || !a0.isFinite()) return BiquadCoeffs()
        val invA0 = 1.0 / a0
        return BiquadCoeffs(
            b0 = b0 * invA0,
            b1 = b1 * invA0,
            b2 = b2 * invA0,
            a1 = a1 * invA0,
            a2 = a2 * invA0
        )
    }
}
