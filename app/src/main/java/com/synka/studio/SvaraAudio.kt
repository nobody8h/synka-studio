package com.synka.studio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

@OptIn(UnstableApi::class)
class SvaraAudio : BaseAudioProcessor() {

    @Volatile
    var bassGainDb: Float = 0f

    private var activeGainDb: Float = Float.NaN
    private var sampleRate: Int = 48000
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT

    // Low-shelf biquad normalized coefficients (b0, b1, b2, a1, a2)
    private var b0: Double = 1.0
    private var b1: Double = 0.0
    private var b2: Double = 0.0
    private var a1: Double = 0.0
    private var a2: Double = 0.0

    // Transposed Direct Form II per-channel delay states (pre-allocated on configure)
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioProcessor.UnhandledAudioFormatException? {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = enc

        z1 = DoubleArray(channelCount)
        z2 = DoubleArray(channelCount)
        activeGainDb = Float.NaN
        updateLowShelfCoefficients(bassGainDb.coerceIn(-12f, 12f).toDouble())

        return null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            return
        }

        val targetGainDb = bassGainDb.coerceIn(-12f, 12f)
        if (targetGainDb != activeGainDb) {
            updateLowShelfCoefficients(targetGainDb.toDouble())
        }

        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val dst = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sampleCount = remaining / 2
                var ch = 0
                var i = 0
                while (i < sampleCount) {
                    val rawShort = src.short.toInt()
                    val x = rawShort / 32768.0

                    val y = processSampleTdf2(x, ch)
                    val softClipped = tanh(y).coerceIn(-1.0, 1.0)
                    val outShort = (softClipped * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    dst.putShort(outShort)

                    ch++
                    if (ch >= channelCount) {
                        ch = 0
                    }
                    i++
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val sampleCount = remaining / 4
                var ch = 0
                var i = 0
                while (i < sampleCount) {
                    val x = src.float.toDouble()

                    val y = processSampleTdf2(x, ch)
                    val softClipped = tanh(y).coerceIn(-1.0, 1.0)
                    dst.putFloat(softClipped.toFloat())

                    ch++
                    if (ch >= channelCount) {
                        ch = 0
                    }
                    i++
                }
            }
        }

        src.position(src.limit())
        dst.flip()
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        clearState()
        encoding = C.ENCODING_PCM_16BIT
        sampleRate = 48000
        channelCount = 2
        activeGainDb = Float.NaN
    }

    private fun clearState() {
        var i = 0
        while (i < z1.size) {
            z1[i] = 0.0
            z2[i] = 0.0
            i++
        }
    }

    private fun processSampleTdf2(x: Double, ch: Int): Double {
        val s1 = z1[ch]
        val s2 = z2[ch]
        val y = b0 * x + s1
        z1[ch] = b1 * x - a1 * y + s2
        z2[ch] = b2 * x - a2 * y
        return y
    }

    private fun updateLowShelfCoefficients(gainDb: Double) {
        val fc = 100.0
        val slope = 1.0 // Shelf slope parameter S = 1.0

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate.toDouble()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val rawB0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val rawB1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val rawB2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val rawA1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val rawA2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha

        b0 = rawB0 / a0
        b1 = rawB1 / a0
        b2 = rawB2 / a0
        a1 = rawA1 / a0
        a2 = rawA2 / a0

        activeGainDb = gainDb.toFloat()
    }
}
