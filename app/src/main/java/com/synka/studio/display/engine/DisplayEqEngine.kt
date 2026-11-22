package com.synka.studio.display.engine

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.synka.studio.display.hardware.HardwareApplyResult
import com.synka.studio.display.hardware.HardwareColorBackend
import com.synka.studio.display.model.ControlScope
import com.synka.studio.display.model.DisplayEqState
import com.synka.studio.display.model.DisplayEqTier
import com.synka.studio.display.overlay.OverlayRenderSpec
import com.synka.studio.display.overlay.OverlayWindowController
import com.synka.studio.display.service.DisplayEqForegroundService
import com.synka.studio.display.service.DisplayOverlayAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayEqEngine private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SynkaDisplayEngine"

        @Volatile
        private var instance: DisplayEqEngine? = null

        fun getInstance(context: Context): DisplayEqEngine =
            instance ?: synchronized(this) {
                instance ?: DisplayEqEngine(context.applicationContext).also { instance = it }
            }
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val overlayController = OverlayWindowController(appContext)
    private val hardwareBackend = HardwareColorBackend()

    private val _state = MutableStateFlow(DisplayEqState())
    val state: StateFlow<DisplayEqState> = _state.asStateFlow()

    private val _activeTier = MutableStateFlow(DisplayEqTier.INACTIVE)
    val activeTier: StateFlow<DisplayEqTier> = _activeTier.asStateFlow()

    private val applyTrigger = MutableStateFlow(0L)

    init {
        engineScope.launch {
            applyTrigger.collect { tick ->
                if (tick != 0L) {
                    applyCurrentStateInternal(_state.value)
                }
            }
        }
    }

    fun updateState(transform: (DisplayEqState) -> DisplayEqState) {
        val next = transform(_state.value).sanitized()
        _state.value = next
        syncServiceLifecycle(next)
        applyTrigger.value = System.nanoTime()
    }

    fun setState(newState: DisplayEqState) {
        val clean = newState.sanitized()
        _state.value = clean
        syncServiceLifecycle(clean)
        applyTrigger.value = System.nanoTime()
    }

    fun reapplyNow() {
        applyTrigger.value = System.nanoTime()
    }

    fun hasOverlayPermission(): Boolean =
        DisplayOverlayAccessibilityService.currentInstance() != null ||
            Settings.canDrawOverlays(appContext)

    fun scopeForControl(controlKey: String): ControlScope {
        val tier = _activeTier.value
        return when (controlKey) {
            "dim", "temperature", "tint", "rgb", "vignette" -> ControlScope.SYSTEM_WIDE
            "saturation" -> when (tier) {
                DisplayEqTier.TIER2_ROOT_SURFACEFLINGER,
                DisplayEqTier.TIER2_SHIZUKU_HARDWARE -> ControlScope.SYSTEM_WIDE
                else -> ControlScope.IN_APP_GPU_ONLY
            }
            "contrast", "exposure", "gamma" -> when (tier) {
                DisplayEqTier.TIER2_ROOT_SURFACEFLINGER -> ControlScope.SYSTEM_WIDE
                else -> ControlScope.IN_APP_GPU_ONLY
            }
            else -> ControlScope.IN_APP_GPU_ONLY
        }
    }

    private fun syncServiceLifecycle(state: DisplayEqState) {
        val serviceIntent = Intent(appContext, DisplayEqForegroundService::class.java)
        if (state.enabled && !state.isNeutral()) {
            val a11y = DisplayOverlayAccessibilityService.currentInstance()
            overlayController.ensureVisibleWindowAndRun(a11y) {
                runCatching {
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                }.onFailure { err ->
                    Log.w(TAG, "FGS start deferred (overlay remains active): ${err.message}")
                }
            }
        } else {
            runCatching { appContext.stopService(serviceIntent) }
        }
    }

    private fun applyCurrentStateInternal(state: DisplayEqState) {
        if (!state.enabled || state.isNeutral()) {
            hardwareBackend.restoreHardware()
            overlayController.detach()
            _activeTier.value = DisplayEqTier.INACTIVE
            return
        }

        val hwResult = hardwareBackend.applyHardware(state)
        val overlaySpec = computeResidualOverlaySpec(state, hwResult)
        val a11yService = DisplayOverlayAccessibilityService.currentInstance()
        overlayController.apply(overlaySpec, a11yService)

        _activeTier.value = when {
            hwResult.handledFullLinearMatrix -> DisplayEqTier.TIER2_ROOT_SURFACEFLINGER
            hwResult.handledWarmTemp || hwResult.handledDim || hwResult.handledDesaturation ->
                DisplayEqTier.TIER2_SHIZUKU_HARDWARE
            a11yService != null -> DisplayEqTier.TIER1_TRUSTED_A11Y_OVERLAY
            Settings.canDrawOverlays(appContext) -> DisplayEqTier.TIER1_STANDARD_OVERLAY
            else -> DisplayEqTier.INACTIVE
        }
    }

    private fun computeResidualOverlaySpec(
        s: DisplayEqState,
        hw: HardwareApplyResult
    ): OverlayRenderSpec {
        val dimAlpha = if (hw.handledFullLinearMatrix || hw.handledDim) 0f else s.dimStrength
        val vignetteAlpha = (s.vignette * 0.55f).coerceIn(0f, 0.55f)

        if (hw.handledFullLinearMatrix) {
            return OverlayRenderSpec(
                active = true,
                dimAlpha = 0f,
                tintAlpha = 0f,
                vignetteAlpha = vignetteAlpha
            )
        }

        val effTemp = if (hw.handledWarmTemp && s.temperature > 0f) 0f else s.temperature
        val effTint = s.tint
        val rDeficit = (1f - s.redGain.coerceAtMost(1f)).coerceAtLeast(0f)
        val gDeficit = (1f - s.greenGain.coerceAtMost(1f)).coerceAtLeast(0f)
        val bDeficit = (1f - s.blueGain.coerceAtMost(1f)).coerceAtLeast(0f)

        var targetR = 0f
        var targetG = 0f
        var targetB = 0f
        var strength = 0f

        if (effTemp > 0.01f) {
            targetR += 1.00f * effTemp
            targetG += 0.54f * effTemp
            targetB += 0.04f * effTemp
            strength = max(strength, effTemp)
        } else if (effTemp < -0.01f) {
            val cool = -effTemp
            targetR += 0.05f * cool
            targetG += 0.55f * cool
            targetB += 1.00f * cool
            strength = max(strength, cool)
        }

        if (effTint > 0.01f) {
            targetR += 0.90f * effTint
            targetB += 0.80f * effTint
            strength = max(strength, effTint * 0.75f)
        } else if (effTint < -0.01f) {
            val grn = -effTint
            targetG += 0.90f * grn
            strength = max(strength, grn * 0.75f)
        }

        val rgbCutMag = maxOf(rDeficit, gDeficit, bDeficit)
        if (rgbCutMag > 0.01f) {
            targetR += s.redGain.coerceIn(0f, 1f) * rgbCutMag
            targetG += s.greenGain.coerceIn(0f, 1f) * rgbCutMag
            targetB += s.blueGain.coerceIn(0f, 1f) * rgbCutMag
            strength = max(strength, rgbCutMag * 0.65f)
        }

        val maxComp = maxOf(targetR, targetG, targetB)
        val normR = if (maxComp > 1e-4f) (targetR / maxComp).coerceIn(0f, 1f) else 0f
        val normG = if (maxComp > 1e-4f) (targetG / maxComp).coerceIn(0f, 1f) else 0f
        val normB = if (maxComp > 1e-4f) (targetB / maxComp).coerceIn(0f, 1f) else 0f

        val maxTintCap = if (s.oledSafeTintCap) 0.18f else 0.32f
        val tintAlpha = (strength * maxTintCap).coerceIn(0f, maxTintCap)

        val extraDarkDim = if (s.exposure < -0.05f) (-s.exposure / 2f) * 0.35f else 0f
        val totalDim = (dimAlpha + extraDarkDim).coerceIn(0f, 0.72f)

        return OverlayRenderSpec(
            active = true,
            dimAlpha = totalDim,
            tintR = normR,
            tintG = normG,
            tintB = normB,
            tintAlpha = tintAlpha,
            vignetteAlpha = vignetteAlpha
        )
    }
}
