package com.synkastudio.display.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exact, zero-allocation SRC_OVER system overlay view.
 *
 * Algebra:
 *   Pure black (0,0,0, aD) composited OVER chromatic tint (C_t, aT) yields:
 *     combinedAlpha = aD + aT * (1 - aD)
 *     effectiveColor = C_t * (aT * (1 - aD) / combinedAlpha)
 *   Dividing combinedAlpha by windowAlphaScale ensures SurfaceFlinger's
 *   (windowAlpha * viewAlpha) hits the exact requested screen opacity while
 *   keeping LayoutParams.alpha safely below maximumObscuringOpacityForTouch.
 */
data class OverlayRenderSpec(
    val active: Boolean = false,
    val dimAlpha: Float = 0f,        // 0.0f .. 0.72f (Pure black; preserves OLED INF:1 contrast)
    val tintR: Float = 0f,           // 0.0f .. 1.0f
    val tintG: Float = 0f,           // 0.0f .. 1.0f
    val tintB: Float = 0f,           // 0.0f .. 1.0f
    val tintAlpha: Float = 0f,       // Strictly capped <= 0.18f (OLED-safe) or <= 0.32f
    val vignetteAlpha: Float = 0f    // 0.0f .. 0.55f (Pure black radial edge fade)
) {
    fun hasVisibleEffect(): Boolean =
        active && (dimAlpha > 0.002f || tintAlpha > 0.002f || vignetteAlpha > 0.002f)
}

class DisplayOverlayView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val vignettePaint = Paint(Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var spec: OverlayRenderSpec = OverlayRenderSpec()
    private var windowAlphaScale: Float = 0.79f
    private var cachedVignetteAlphaInt: Int = -1
    private var cachedW: Int = 0
    private var cachedH: Int = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun updateRenderSpec(newSpec: OverlayRenderSpec, windowAlpha: Float) {
        val safeWinAlpha = windowAlpha.coerceIn(0.05f, 1.0f)
        if (spec == newSpec && abs(windowAlphaScale - safeWinAlpha) < 1e-4f) return
        spec = newSpec
        windowAlphaScale = safeWinAlpha
        recomputePaints()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedW = w
        cachedH = h
        rebuildVignetteShader()
    }

    private fun recomputePaints() {
        val aD = spec.dimAlpha.coerceIn(0f, 0.72f)
        val aT = spec.tintAlpha.coerceIn(0f, 0.35f)

        val combinedAlpha = (aD + aT * (1f - aD)).coerceIn(0f, windowAlphaScale)
        if (combinedAlpha <= 0.001f) {
            fillPaint.color = Color.TRANSPARENT
        } else {
            val tintWeight = (aT * (1f - aD)) / combinedAlpha
            val effR = (spec.tintR.coerceIn(0f, 1f) * tintWeight * 255f).roundToInt().coerceIn(0, 255)
            val effG = (spec.tintG.coerceIn(0f, 1f) * tintWeight * 255f).roundToInt().coerceIn(0, 255)
            val effB = (spec.tintB.coerceIn(0f, 1f) * tintWeight * 255f).roundToInt().coerceIn(0, 255)
            val compensatedAlpha = ((combinedAlpha / windowAlphaScale).coerceIn(0f, 1f) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
            fillPaint.color = Color.argb(compensatedAlpha, effR, effG, effB)
        }

        rebuildVignetteShader()
    }

    private fun rebuildVignetteShader() {
        val vig = spec.vignetteAlpha.coerceIn(0f, 0.55f)
        val compensatedVigInt = ((vig / windowAlphaScale).coerceIn(0f, 1f) * 255f)
            .roundToInt()
            .coerceIn(0, 255)

        if (compensatedVigInt <= 1 || cachedW <= 0 || cachedH <= 0) {
            vignettePaint.shader = null
            cachedVignetteAlphaInt = 0
            return
        }
        if (cachedVignetteAlphaInt == compensatedVigInt && vignettePaint.shader != null) return
        cachedVignetteAlphaInt = compensatedVigInt

        val cx = cachedW * 0.5f
        val cy = cachedH * 0.5f
        val radius = max(1f, hypot(cx.toDouble(), cy.toDouble()).toFloat())
        val midAlpha = (compensatedVigInt * 0.25f).roundToInt().coerceIn(0, 255)

        vignettePaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(midAlpha, 0, 0, 0),
                Color.argb(compensatedVigInt, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.52f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!spec.hasVisibleEffect()) return
        if (fillPaint.color != Color.TRANSPARENT) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
        if (vignettePaint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
        }
    }
}
