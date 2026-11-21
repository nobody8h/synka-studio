package com.synka.studio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

@OptIn(UnstableApi::class)
class SvaraAudio : BaseAudioProcessor() {

    companion object {
        const val STAGE_COUNT = 12
        const val WAVEFORM_BARS = 48

        private const val MIN_FREQ_HZ = 20.0
        private const val NYQUIST_SAFETY_RATIO = 0.45
        private const val MIN_Q = 0.3
        private const val MAX_Q = 12.0
        private const val DENORMAL_EPS = 1e-25
        private const val PCM16_INV_SCALE = 1.0 / 32768.0
        private const val PCM16_OUT_SCALE = 32767.0
        private const val CONTOUR_WEIGHT = 0.55
    }

    // Read-only telemetry for UI verification strip
    @Volatile
    var activeSampleRate: Int = 48000
        private set

    @Volatile
    var activeChannelCount: Int = 2
        private set

    @Volatile
    var activeEncoding: Int = C.ENCODING_PCM_16BIT
        private set

    // UI-side serialization lock (NEVER acquired on audio thread)
    private val paramLock = Any()
    private var lastParams: SvaraParams = SvaraParams()
    private var lastBypassed: Boolean = false

    // Lock-free seqlock + dirty flag for tear-free double-buffering
    private val stagingVersion = AtomicInteger(0)

    @Volatile
    private var dirty: Boolean = false

    // Staging coefficient banks (written on UI/config thread)
    private val stagingB0 = DoubleArray(STAGE_COUNT) { 1.0 }
    private val stagingB1 = DoubleArray(STAGE_COUNT)
    private val stagingB2 = DoubleArray(STAGE_COUNT)
    private val stagingA1 = DoubleArray(STAGE_COUNT)
    private val stagingA2 = DoubleArray(STAGE_COUNT)

    private var stagingPreAmpGain: Double = 1.0
    private var stagingStereoWidth: Double = 1.0
    private var stagingNightDialogue: Double = 0.0
    private var stagingAntiCrackle: Double = 0.75
    private var stagingBalanceL: Double = 1.0
    private var stagingBalanceR: Double = 1.0
    private var stagingBypassed: Boolean = false

    // Audio-thread scratch bank to guarantee zero torn reads before commit
    private val scratchB0 = DoubleArray(STAGE_COUNT) { 1.0 }
    private val scratchB1 = DoubleArray(STAGE_COUNT)
    private val scratchB2 = DoubleArray(STAGE_COUNT)
    private val scratchA1 = DoubleArray(STAGE_COUNT)
    private val scratchA2 = DoubleArray(STAGE_COUNT)

    // Active coefficient banks (read exclusively on audio thread)
    private val b0 = DoubleArray(STAGE_COUNT) { 1.0 }
    private val b1 = DoubleArray(STAGE_COUNT)
    private val b2 = DoubleArray(STAGE_COUNT)
    private val a1 = DoubleArray(STAGE_COUNT)
    private val a2 = DoubleArray(STAGE_COUNT)

    // Pre-allocated TDF-II delay states for all 12 stages (Left & Right)
    private val s1L = DoubleArray(STAGE_COUNT)
    private val s2L = DoubleArray(STAGE_COUNT)
    private val s1R = DoubleArray(STAGE_COUNT)
    private val s2R = DoubleArray(STAGE_COUNT)

    // Active smoothed audio-thread scalars
    private var targetPreAmpGain: Double = 1.0
    private var currentPreAmpGain: Double = 1.0
    private var targetStereoWidth: Double = 1.0
    private var currentStereoWidth: Double = 1.0
    private var targetBalanceL: Double = 1.0
    private var currentBalanceL: Double = 1.0
    private var targetBalanceR: Double = 1.0
    private var currentBalanceR: Double = 1.0
    private var targetWetMix: Double = 1.0
    private var currentWetMix: Double = 1.0
    private var activeNightDialogue: Double = 0.0
    private var activeAntiCrackle: Double = 0.75

    // Envelope follower states (Night Dialogue + Anti-Crackle peak guard)
    private var smoothCoeff: Double = 0.005
    private var niAttackCoeff: Double = 0.997
    private var niReleaseCoeff: Double = 0.9998
    private var peakAttackCoeff: Double = 0.95
    private var peakReleaseCoeff: Double = 0.9994
    private var niEnvelope: Double = 0.0
    private var niGainSmooth: Double = 1.0
    private var peakEnvelope: Double = 0.0

    // Lock-free pre-allocated 48-bar waveform snapshot
    private val waveformWork = FloatArray(WAVEFORM_BARS)
    private val waveformSnapshot = FloatArray(WAVEFORM_BARS)

    init {
        updateParams(SvaraParams(), bypassed = false)
    }

    fun activeEncodingLabel(): String = when (activeEncoding) {
        C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        C.ENCODING_PCM_16BIT -> "PCM_16BIT"
        else -> "ENC_$activeEncoding"
    }

    /**
     * Thread-safe parameter update from UI/background threads.
     * Computes all 12 biquad stages into staging arrays under a seqlock so
     * the real-time audio thread never blocks and never reads torn coefficients.
     */
    fun updateParams(params: SvaraParams, bypassed: Boolean) {
        synchronized(paramLock) {
            val normalized = params.normalized()
            lastParams = normalized
            lastBypassed = bypassed
            stageCoefficientsLocked(normalized, bypassed, activeSampleRate)
        }
    }

    /**
     * Lock-free copy of the latest normalized 48-bar waveform peaks (0f..1f).
     */
    fun readWaveform(dest: FloatArray) {
        val count = min(dest.size, WAVEFORM_BARS)
        var i = 0
        while (i < count) {
            dest[i] = waveformSnapshot[i]
            i++
        }
    }

    fun clearWaveform() {
        var i = 0
        while (i < WAVEFORM_BARS) {
            waveformWork[i] = 0f
            waveformSnapshot[i] = 0f
            i++
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (
            (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        activeSampleRate = sr
        activeChannelCount = inputAudioFormat.channelCount
        activeEncoding = enc

        // Pre-compute time-constant coefficients for current sample rate
        smoothCoeff = 1.0 - exp(-1.0 / (0.004 * sr))          // ~4 ms anti-zipper ramp
        niAttackCoeff = exp(-1.0 / (0.010 * sr))              // 10 ms attack
        niReleaseCoeff = exp(-1.0 / (0.180 * sr))             // 180 ms release
        peakAttackCoeff = exp(-1.0 / (0.0005 * sr))           // 0.5 ms fast limiter attack
        peakReleaseCoeff = exp(-1.0 / (0.055 * sr))           // 55 ms limiter release

        clearFilterStates()
        clearWaveform()

        synchronized(paramLock) {
            stageCoefficientsLocked(lastParams, lastBypassed, sr)
        }
        syncStagedToActiveIfReady()

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) {
            return
        }

        if (dirty) {
            syncStagedToActiveIfReady()
        }

        val enc = activeEncoding
        val channels = activeChannelCount
        val bytesPerSample = if (enc == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val dst = replaceOutputBuffer(frameCount * bytesPerFrame).order(ByteOrder.nativeOrder())

        var barIdx = 0
        while (barIdx < WAVEFORM_BARS) {
            waveformWork[barIdx] = 0f
            barIdx++
        }

        val isFloat = (enc == C.ENCODING_PCM_FLOAT)

        if (channels == 2) {
            processStereoBuffer(src, dst, frameCount, isFloat)
        } else if (channels == 1) {
            processMonoBuffer(src, dst, frameCount, isFloat)
        } else {
            processMultiChannelSafe(src, dst, frameCount, channels, isFloat)
        }

        // Publish smoothed peak snapshot lock-free once per buffer
        var w = 0
        while (w < WAVEFORM_BARS) {
            val peak = waveformWork[w]
            val prev = waveformSnapshot[w]
            val smoothed = if (peak >= prev) {
                prev + (peak - prev) * 0.72f
            } else {
                prev * 0.78f + peak * 0.22f
            }
            waveformSnapshot[w] = smoothed.coerceIn(0f, 1f)
            w++
        }

        inputBuffer.position(inputBuffer.limit())
        dst.flip()
    }

    override fun onFlush() {
        clearFilterStates()
        clearWaveform()
    }

    override fun onReset() {
        clearFilterStates()
        clearWaveform()
        activeEncoding = C.ENCODING_PCM_16BIT
        activeSampleRate = 48000
        activeChannelCount = 2
    }

    private fun processStereoBuffer(
        src: ByteBuffer,
        dst: ByteBuffer,
        frameCount: Int,
        isFloat: Boolean
    ) {
        val sm = smoothCoeff
        val niLevel = activeNightDialogue
        val arLevel = activeAntiCrackle
        val kneeThreshold = 0.96 - 0.28 * arLevel
        val kneeHeadroom = 1.0 - kneeThreshold
        val invKneeHeadroom = 1.0 / kneeHeadroom

        var frame = 0
        while (frame < frameCount) {
            val dryL: Double
            val dryR: Double
            if (isFloat) {
                dryL = src.float.toDouble()
                dryR = src.float.toDouble()
            } else {
                dryL = src.short.toDouble() * PCM16_INV_SCALE
                dryR = src.short.toDouble() * PCM16_INV_SCALE
            }

            // Smooth scalar parameters per sample to eliminate zipper noise
            currentPreAmpGain += (targetPreAmpGain - currentPreAmpGain) * sm
            currentStereoWidth += (targetStereoWidth - currentStereoWidth) * sm
            currentBalanceL += (targetBalanceL - currentBalanceL) * sm
            currentBalanceR += (targetBalanceR - currentBalanceR) * sm
            currentWetMix += (targetWetMix - currentWetMix) * sm

            var l = dryL * currentPreAmpGain
            var r = dryR * currentPreAmpGain

            // 12-stage TDF-II biquad cascade (Stages 0..9 peaking, 10 low-shelf, 11 high-shelf)
            var stage = 0
            while (stage < STAGE_COUNT) {
                val cb0 = b0[stage]
                val cb1 = b1[stage]
                val cb2 = b2[stage]
                val ca1 = a1[stage]
                val ca2 = a2[stage]

                val yl = cb0 * l + s1L[stage]
                var ns1L = cb1 * l - ca1 * yl + s2L[stage]
                var ns2L = cb2 * l - ca2 * yl
                if (!ns1L.isFinite() || abs(ns1L) < DENORMAL_EPS) ns1L = 0.0
                if (!ns2L.isFinite() || abs(ns2L) < DENORMAL_EPS) ns2L = 0.0
                s1L[stage] = ns1L
                s2L[stage] = ns2L
                l = if (yl.isFinite()) yl else 0.0

                val yr = cb0 * r + s1R[stage]
                var ns1R = cb1 * r - ca1 * yr + s2R[stage]
                var ns2R = cb2 * r - ca2 * yr
                if (!ns1R.isFinite() || abs(ns1R) < DENORMAL_EPS) ns1R = 0.0
                if (!ns2R.isFinite() || abs(ns2R) < DENORMAL_EPS) ns2R = 0.0
                s1R[stage] = ns1R
                s2R[stage] = ns2R
                r = if (yr.isFinite()) yr else 0.0

                stage++
            }

            // Mid-Side stereo width scaling (safe zero-alloc, unity when width == 1.0)
            if (abs(currentStereoWidth - 1.0) > 1e-5) {
                val mid = 0.5 * (l + r)
                val side = 0.5 * (l - r) * currentStereoWidth
                l = mid + side
                r = mid - side
            }

            // Night Dialogue (Ni): gentle upward boost for quiet dialogue + downward peak leveling
            if (niLevel > 1e-4) {
                val niGain = updateNightDialogueGain(max(abs(l), abs(r)), niLevel)
                l *= niGain
                r *= niGain
            } else {
                niEnvelope *= niReleaseCoeff
                niGainSmooth += (1.0 - niGainSmooth) * sm
            }

            // Smooth L/R stereo balance attenuation (Li)
            l *= currentBalanceL
            r *= currentBalanceR

            // Anti-Crackle (Ar): fast peak guard + C1-continuous knee tanh soft-clipper
            val peakIn = max(abs(l), abs(r))
            val limiterGain = updatePeakGuardGain(peakIn, arLevel)
            l = applyKneeTanh(l * limiterGain, kneeThreshold, kneeHeadroom, invKneeHeadroom)
            r = applyKneeTanh(r * limiterGain, kneeThreshold, kneeHeadroom, invKneeHeadroom)

            // Smooth wet/dry bypass crossfade
            val wet = currentWetMix
            val dry = 1.0 - wet
            var outL = dryL * dry + l * wet
            var outR = dryR * dry + r * wet

            // Absolute ceiling guard so output can never wrap PCM-16
            outL = outL.coerceIn(-0.999969, 0.999969)
            outR = outR.coerceIn(-0.999969, 0.999969)

            // Accumulate lock-free 48-bar peak magnitude for this buffer
            val bucket = ((frame * WAVEFORM_BARS) / frameCount).coerceIn(0, WAVEFORM_BARS - 1)
            val framePeak = max(abs(outL), abs(outR)).toFloat()
            if (framePeak > waveformWork[bucket]) {
                waveformWork[bucket] = framePeak
            }

            if (isFloat) {
                dst.putFloat(outL.toFloat())
                dst.putFloat(outR.toFloat())
            } else {
                val sL = (outL * PCM16_OUT_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                val sR = (outR * PCM16_OUT_SCALE).toInt().coerceIn(-32768, 32767).toShort()
                dst.putShort(sL)
                dst.putShort(sR)
            }

            frame++
        }
    }

    private fun processMonoBuffer(
        src: ByteBuffer,
        dst: ByteBuffer,
        frameCount: Int,
        isFloat: Boolean
    ) {
        val sm = smoothCoeff
        val niLevel = activeNightDialogue
        val arLevel = activeAntiCrackle
        val kneeThreshold = 0.96 - 0.28 * arLevel
        val kneeHeadroom = 1.0 - kneeThreshold
        val invKneeHeadroom = 1.0 / kneeHeadroom

        var frame = 0
        while (frame < frameCount) {
            val drySample = if (isFloat) {
                src.float.toDouble()
            } else {
                src.short.toDouble() * PCM16_INV_SCALE
            }

            currentPreAmpGain += (targetPreAmpGain - currentPreAmpGain) * sm
            currentWetMix += (targetWetMix - currentWetMix) * sm

            var x = drySample * currentPreAmpGain

            var stage = 0
            while (stage < STAGE_COUNT) {
                val y = b0[stage] * x + s1L[stage]
                var ns1 = b1[stage] * x - a1[stage] * y + s2L[stage]
                var ns2 = b2[stage] * x - a2[stage] * y
                if (!ns1.isFinite() || abs(ns1) < DENORMAL_EPS) ns1 = 0.0
                if (!ns2.isFinite() || abs(ns2) < DENORMAL_EPS) ns2 = 0.0
                s1L[stage] = ns1
                s2L[stage] = ns2
                x = if (y.isFinite()) y else 0.0
                stage++
            }

            if (niLevel > 1e-4) {
                x *= updateNightDialogueGain(abs(x), niLevel)
            } else {
                niEnvelope *= niReleaseCoeff
                niGainSmooth += (1.0 - niGainSmooth) * sm
            }

            val limiterGain = updatePeakGuardGain(abs(x), arLevel)
            x = applyKneeTanh(x * limiterGain, kneeThreshold, kneeHeadroom, invKneeHeadroom)

            val wet = currentWetMix
            val out = (drySample * (1.0 - wet) + x * wet).coerceIn(-0.999969, 0.999969)

            val bucket = ((frame * WAVEFORM_BARS) / frameCount).coerceIn(0, WAVEFORM_BARS - 1)
            val mag = abs(out).toFloat()
            if (mag > waveformWork[bucket]) {
                waveformWork[bucket] = mag
            }

            if (isFloat) {
                dst.putFloat(out.toFloat())
            } else {
                dst.putShort((out * PCM16_OUT_SCALE).toInt().coerceIn(-32768, 32767).toShort())
            }

            frame++
        }
    }

    private fun processMultiChannelSafe(
        src: ByteBuffer,
        dst: ByteBuffer,
        frameCount: Int,
        channels: Int,
        isFloat: Boolean
    ) {
        val sm = smoothCoeff
        val arLevel = activeAntiCrackle
        val kneeThreshold = 0.96 - 0.28 * arLevel
        val kneeHeadroom = 1.0 - kneeThreshold
        val invKneeHeadroom = 1.0 / kneeHeadroom

        var frame = 0
        while (frame < frameCount) {
            currentPreAmpGain += (targetPreAmpGain - currentPreAmpGain) * sm
            currentWetMix += (targetWetMix - currentWetMix) * sm

            var framePeak = 0f
            var ch = 0
            while (ch < channels) {
                val drySample = if (isFloat) {
                    src.float.toDouble()
                } else {
                    src.short.toDouble() * PCM16_INV_SCALE
                }
                val wetSample = applyKneeTanh(
                    drySample * currentPreAmpGain,
                    kneeThreshold,
                    kneeHeadroom,
                    invKneeHeadroom
                )
                val out = (drySample * (1.0 - currentWetMix) + wetSample * currentWetMix)
                    .coerceIn(-0.999969, 0.999969)
                val mag = abs(out).toFloat()
                if (mag > framePeak) framePeak = mag

                if (isFloat) {
                    dst.putFloat(out.toFloat())
                } else {
                    dst.putShort((out * PCM16_OUT_SCALE).toInt().coerceIn(-32768, 32767).toShort())
                }
                ch++
            }

            val bucket = ((frame * WAVEFORM_BARS) / frameCount).coerceIn(0, WAVEFORM_BARS - 1)
            if (framePeak > waveformWork[bucket]) {
                waveformWork[bucket] = framePeak
            }
            frame++
        }
    }

    private fun updateNightDialogueGain(peakAbs: Double, niLevel: Double): Double {
        val coeff = if (peakAbs > niEnvelope) niAttackCoeff else niReleaseCoeff
        niEnvelope = coeff * niEnvelope + (1.0 - coeff) * peakAbs

        val env = niEnvelope
        val desiredGain = when {
            // Below noise floor: do not amplify silence hiss
            env < 0.003 -> 1.0
            // Quiet dialogue zone: gentle upward boost toward target level (0.14)
            env < 0.14 -> {
                val ratio = (0.14 / env).coerceAtMost(2.6) // Max +8.3 dB upward lift
                val fadeIn = ((env - 0.003) / 0.015).coerceIn(0.0, 1.0)
                1.0 + (ratio - 1.0) * niLevel * 0.75 * fadeIn
            }
            // Loud peak zone: downward compression above 0.36
            env > 0.36 -> {
                val compressed = 0.36 + (env - 0.36) * (1.0 - 0.72 * niLevel)
                (compressed / env).coerceIn(0.25, 1.0)
            }
            else -> 1.0
        }

        niGainSmooth += (desiredGain - niGainSmooth) * smoothCoeff
        return niGainSmooth
    }

    private fun updatePeakGuardGain(peakAbs: Double, arLevel: Double): Double {
        val coeff = if (peakAbs > peakEnvelope) peakAttackCoeff else peakReleaseCoeff
        peakEnvelope = coeff * peakEnvelope + (1.0 - coeff) * peakAbs

        if (arLevel <= 1e-4 || peakEnvelope <= 0.88) {
            return 1.0
        }
        // Gently pull sustained overload back toward 0.88 before hitting the tanh knee
        val targetPeak = 0.88 + (peakEnvelope - 0.88) * (1.0 - 0.82 * arLevel)
        return (targetPeak / peakEnvelope).coerceIn(0.15, 1.0)
    }

    private fun applyKneeTanh(
        x: Double,
        threshold: Double,
        headroom: Double,
        invHeadroom: Double
    ): Double {
        val ax = abs(x)
        if (ax <= threshold) {
            return x
        }
        val shaped = threshold + headroom * tanh((ax - threshold) * invHeadroom)
        return if (x >= 0.0) shaped else -shaped
    }

    private fun syncStagedToActiveIfReady() {
        val v1 = stagingVersion.get()
        // Odd version means UI thread is mid-write; wait for next buffer without blocking
        if ((v1 and 1) != 0) {
            return
        }

        var i = 0
        while (i < STAGE_COUNT) {
            scratchB0[i] = stagingB0[i]
            scratchB1[i] = stagingB1[i]
            scratchB2[i] = stagingB2[i]
            scratchA1[i] = stagingA1[i]
            scratchA2[i] = stagingA2[i]
            i++
        }
        val tmpPreAmp = stagingPreAmpGain
        val tmpWidth = stagingStereoWidth
        val tmpNi = stagingNightDialogue
        val tmpAr = stagingAntiCrackle
        val tmpBalL = stagingBalanceL
        val tmpBalR = stagingBalanceR
        val tmpBypassed = stagingBypassed

        val v2 = stagingVersion.get()
        if (v1 != v2) {
            // Concurrent write happened during copy; discard scratch and retry on next buffer
            return
        }

        var j = 0
        while (j < STAGE_COUNT) {
            b0[j] = scratchB0[j]
            b1[j] = scratchB1[j]
            b2[j] = scratchB2[j]
            a1[j] = scratchA1[j]
            a2[j] = scratchA2[j]
            j++
        }
        targetPreAmpGain = tmpPreAmp
        targetStereoWidth = tmpWidth
        activeNightDialogue = tmpNi
        activeAntiCrackle = tmpAr
        targetBalanceL = tmpBalL
        targetBalanceR = tmpBalR
        targetWetMix = if (tmpBypassed) 0.0 else 1.0
        dirty = false
    }

    private fun stageCoefficientsLocked(params: SvaraParams, bypassed: Boolean, sr: Int) {
        val sampleRateD = sr.coerceAtLeast(8000).toDouble()
        val maxFreq = max(MIN_FREQ_HZ, sampleRateD * NYQUIST_SAFETY_RATIO)

        // Begin seqlock write (odd version)
        stagingVersion.incrementAndGet()

        // Stages 0..9: 10 parametric peaking filters + 5-zone contour additive offsets
        var idx = 0
        while (idx < 10) {
            val band = params.bands[idx]
            val zoneOffset = when (idx) {
                0, 1 -> params.su
                2, 3 -> params.mi
                4, 5 -> params.vo
                6, 7 -> params.tr
                else -> params.ai
            }
            val totalGainDb = (band.gainDb + zoneOffset * CONTOUR_WEIGHT).coerceIn(-18.0, 18.0)
            val fc = band.freqHz.toDouble().coerceIn(MIN_FREQ_HZ, maxFreq)
            val q = band.q.toDouble().coerceIn(MIN_Q, MAX_Q)
            computePeakingIntoStaging(idx, fc, q, totalGainDb, sampleRateD)
            idx++
        }

        // Stage 10: Low-Shelf driven by bassDepthY (gain) and bassDepthX (cutoff & Q)
        val lowShelfGainDb = (params.bassDepthY * 12.0).coerceIn(-12.0, 12.0)
        val lowShelfFreq = (85.0 + params.bassDepthX * 35.0).coerceIn(MIN_FREQ_HZ, maxFreq)
        val lowShelfQ = (0.707 + (params.bassDepthX + 1.0) * 0.15).coerceIn(MIN_Q, MAX_Q)
        computeLowShelfIntoStaging(10, lowShelfFreq, lowShelfQ, lowShelfGainDb, sampleRateD)

        // Stage 11: High-Shelf driven by airWidthY
        val highShelfGainDb = (params.airWidthY * 12.0).coerceIn(-12.0, 12.0)
        val highShelfFreq = 9000.0.coerceIn(MIN_FREQ_HZ, maxFreq)
        computeHighShelfIntoStaging(11, highShelfFreq, 0.707, highShelfGainDb, sampleRateD)

        // Pre-amp, stereo width, safety & L/R balance scalars
        stagingPreAmpGain = 10.0.pow(params.preAmpDb.toDouble().coerceIn(-12.0, 12.0) / 20.0)
        stagingStereoWidth = (1.0 + params.airWidthX * 0.45 + params.bassDepthX * 0.10)
            .coerceIn(0.45, 1.55)
        stagingNightDialogue = (params.nightDialogue / 100.0).coerceIn(0.0, 1.0)
        stagingAntiCrackle = (params.antiCrackle / 100.0).coerceIn(0.0, 1.0)

        val bal = (params.lrBalance / 100.0).coerceIn(-1.0, 1.0)
        stagingBalanceL = if (bal > 0.0) (1.0 - bal) else 1.0
        stagingBalanceR = if (bal < 0.0) (1.0 + bal) else 1.0
        stagingBypassed = bypassed

        // Complete seqlock write (even version) and signal audio thread
        stagingVersion.incrementAndGet()
        dirty = true
    }

    private fun computePeakingIntoStaging(
        stage: Int,
        fc: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double
    ) {
        if (abs(gainDb) < 0.001) {
            writeIdentityStaging(stage)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val a0 = 1.0 + alpha / a
        writeNormalizedStaging(
            stage = stage,
            rawB0 = 1.0 + alpha * a,
            rawB1 = -2.0 * cosW0,
            rawB2 = 1.0 - alpha * a,
            rawA0 = a0,
            rawA1 = -2.0 * cosW0,
            rawA2 = 1.0 - alpha / a
        )
    }

    private fun computeLowShelfIntoStaging(
        stage: Int,
        fc: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double
    ) {
        if (abs(gainDb) < 0.001) {
            writeIdentityStaging(stage)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val aPlus1 = a + 1.0
        val aMinus1 = a - 1.0

        writeNormalizedStaging(
            stage = stage,
            rawB0 = a * (aPlus1 - aMinus1 * cosW0 + twoSqrtAAlpha),
            rawB1 = 2.0 * a * (aMinus1 - aPlus1 * cosW0),
            rawB2 = a * (aPlus1 - aMinus1 * cosW0 - twoSqrtAAlpha),
            rawA0 = aPlus1 + aMinus1 * cosW0 + twoSqrtAAlpha,
            rawA1 = -2.0 * (aMinus1 + aPlus1 * cosW0),
            rawA2 = aPlus1 + aMinus1 * cosW0 - twoSqrtAAlpha
        )
    }

    private fun computeHighShelfIntoStaging(
        stage: Int,
        fc: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double
    ) {
        if (abs(gainDb) < 0.001) {
            writeIdentityStaging(stage)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val aPlus1 = a + 1.0
        val aMinus1 = a - 1.0

        writeNormalizedStaging(
            stage = stage,
            rawB0 = a * (aPlus1 + aMinus1 * cosW0 + twoSqrtAAlpha),
            rawB1 = -2.0 * a * (aMinus1 + aPlus1 * cosW0),
            rawB2 = a * (aPlus1 + aMinus1 * cosW0 - twoSqrtAAlpha),
            rawA0 = aPlus1 - aMinus1 * cosW0 + twoSqrtAAlpha,
            rawA1 = 2.0 * (aMinus1 - aPlus1 * cosW0),
            rawA2 = aPlus1 - aMinus1 * cosW0 - twoSqrtAAlpha
        )
    }

    private fun writeNormalizedStaging(
        stage: Int,
        rawB0: Double,
        rawB1: Double,
        rawB2: Double,
        rawA0: Double,
        rawA1: Double,
        rawA2: Double
    ) {
        if (!rawA0.isFinite() || abs(rawA0) < 1e-12) {
            writeIdentityStaging(stage)
            return
        }
        val invA0 = 1.0 / rawA0
        val nb0 = rawB0 * invA0
        val nb1 = rawB1 * invA0
        val nb2 = rawB2 * invA0
        val na1 = rawA1 * invA0
        val na2 = rawA2 * invA0

        if (!nb0.isFinite() || !nb1.isFinite() || !nb2.isFinite() || !na1.isFinite() || !na2.isFinite()) {
            writeIdentityStaging(stage)
            return
        }

        stagingB0[stage] = nb0
        stagingB1[stage] = nb1
        stagingB2[stage] = nb2
        stagingA1[stage] = na1
        stagingA2[stage] = na2
    }

    private fun writeIdentityStaging(stage: Int) {
        stagingB0[stage] = 1.0
        stagingB1[stage] = 0.0
        stagingB2[stage] = 0.0
        stagingA1[stage] = 0.0
        stagingA2[stage] = 0.0
    }

    private fun clearFilterStates() {
        var i = 0
        while (i < STAGE_COUNT) {
            s1L[i] = 0.0
            s2L[i] = 0.0
            s1R[i] = 0.0
            s2R[i] = 0.0
            i++
        }
        niEnvelope = 0.0
        niGainSmooth = 1.0
        peakEnvelope = 0.0
    }
}
