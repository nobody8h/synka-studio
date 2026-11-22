package com.synka.studio.display.model

import kotlin.math.abs

enum class DisplayEqTier(val label: String) {
    INACTIVE("Off"),
    TIER1_STANDARD_OVERLAY("System Overlay (Zero-Setup)"),
    TIER1_TRUSTED_A11Y_OVERLAY("Full-Screen Trusted Overlay"),
    TIER2_SHIZUKU_HARDWARE("Hardware Compositor (Shizuku)"),
    TIER2_ROOT_SURFACEFLINGER("Full 4x4 Hardware Matrix (Root)")
}

enum class ControlScope {
    SYSTEM_WIDE,
    IN_APP_GPU_ONLY
}

data class DisplayEqState(
    val enabled: Boolean = false,
    val dimStrength: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val redGain: Float = 1f,
    val greenGain: Float = 1f,
    val blueGain: Float = 1f,
    val vignette: Float = 0f,
    val oledSafeTintCap: Boolean = true,
    val allowDirectRootSu: Boolean = false,
    val hardwareColorMode: Int? = null,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val vibrance: Float = 0f,
    val sharpen: Float = 0f,
    val hslHue: List<Float> = ZERO_8,
    val hslSat: List<Float> = ZERO_8,
    val hslLum: List<Float> = ZERO_8
) {
    fun sanitized(): DisplayEqState = copy(
        dimStrength = dimStrength.coerceIn(0f, 0.72f),
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        redGain = redGain.coerceIn(0.2f, 1.25f),
        greenGain = greenGain.coerceIn(0.2f, 1.25f),
        blueGain = blueGain.coerceIn(0.2f, 1.25f),
        vignette = vignette.coerceIn(0f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        exposure = exposure.coerceIn(-2f, 2f),
        contrast = contrast.coerceIn(-1f, 1f),
        gamma = gamma.coerceIn(0.4f, 2.5f),
        highlights = highlights.coerceIn(-1f, 1f),
        shadows = shadows.coerceIn(-1f, 1f),
        whites = whites.coerceIn(-1f, 1f),
        blacks = blacks.coerceIn(-1f, 1f),
        vibrance = vibrance.coerceIn(-1f, 1f),
        sharpen = sharpen.coerceIn(0f, 1f),
        hslHue = normalize8(hslHue),
        hslSat = normalize8(hslSat),
        hslLum = normalize8(hslLum)
    )

    fun isNeutral(): Boolean {
        if (!enabled) return true
        val eps = 1e-3f
        return dimStrength < eps &&
            abs(temperature) < eps &&
            abs(tint) < eps &&
            abs(redGain - 1f) < eps &&
            abs(greenGain - 1f) < eps &&
            abs(blueGain - 1f) < eps &&
            vignette < eps &&
            abs(saturation) < eps &&
            abs(exposure) < eps &&
            abs(contrast) < eps &&
            abs(gamma - 1f) < eps &&
            abs(highlights) < eps &&
            abs(shadows) < eps &&
            abs(whites) < eps &&
            abs(blacks) < eps &&
            abs(vibrance) < eps &&
            sharpen < eps &&
            hslHue.all { abs(it) < eps } &&
            hslSat.all { abs(it) < eps } &&
            hslLum.all { abs(it) < eps } &&
            hardwareColorMode == null
    }

    companion object {
        val ZERO_8: List<Float> = List(8) { 0f }

        private fun normalize8(src: List<Float>): List<Float> =
            List(8) { idx -> src.getOrElse(idx) { 0f }.coerceIn(-1f, 1f) }
    }
}
