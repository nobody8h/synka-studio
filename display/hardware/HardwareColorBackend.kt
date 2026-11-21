package com.synkastudio.display.hardware

import android.content.pm.PackageManager
import android.opengl.Matrix
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.synkastudio.display.model.DisplayEqState
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.sh[span_35](start_span)[span_35](end_span)[span_38](start_span)[span_38](end_span)izuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class HardwareApplyResult(
    val handledWarmTemp: Boolean = false,
    val handledDim: Boolean = false,
    val handledDesaturation: Boolean = false,
    val handledFullLinearMatrix: Boolean = false
)

class HardwareColorBackend {

    companion object {
        private const val TAG = "SynkaHwColor"
        private const val COLOR_DISPLAY_IFACE = "Landroid/hardware/display/IColorDisplayManager"
        private const val SF_INTERFACE_TOKEN = "android.ui.ISurfaceComposer"
        private const val SF_TRANSACTION_COLOR_MATRIX = 1015
        private const val NIGHT_KELVIN_MAX = 6500.0
        private const val NIGHT_KELVIN_MIN = 1500.0
    }

    private data class SavedSystemSnapshot(
        val nightActive: Boolean,
        val nightKelvin: Int,
        val extraDimActive: Boolean,
        val extraDimStrength: Int,
        val colorMode: Int
    )

    private var hiddenApiExempted = false
    private var snapshot: SavedSystemSnapshot? = null
    private var didWriteSurfaceFlingerMatrix = false
    private var usedDirectSuForMatrix = false

    fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isShizukuRoot(): Boolean = runCatching {
        isShizukuReady() && Shizuku.getUid() == 0
    }.getOrDefault(false)

    /**
     * Applies hardware compositor transforms. MUST be invoked on a background/IO thread.
     */
    fun applyHardware(state: DisplayEqState): HardwareApplyResult {
        if (!state.enabled || state.isNeutral()) {
            restoreHardware()
            return HardwareApplyResult()
        }

        // Priority 1: Root SurfaceFlinger 1015 (Shizuku uid=0 zero-fork IPC, or opt-in direct su)
        if (isShizukuRoot() || state.allowDirectRootSu) {
            val matrix = buildSurfaceFlingerMatrix(state)
            val sentViaShizukuRoot = if (isShizukuRoot()) {
                transactSurfaceFlinger1015ViaShizuku(matrix)
            } else {
                false
            }
            val sentViaDirectSu = if (!sentViaShizukuRoot && state.allowDirectRootSu) {
                sendSurfaceFlinger1015ViaSu(matrix)
            } else {
                false
            }

            if (sentViaShizukuRoot || sentViaDirectSu) {
                didWriteSurfaceFlingerMatrix = true
                usedDirectSuForMatrix = sentViaDirectSu
                if (state.hardwareColorMode != null && isShizukuReady()) {
                    getColorDisplayService()?.let { svc ->
                        runCatching {
                            svc.javaClass
                                .getMethod("setColorMode", java.lang.Integer.TYPE)
                                .invoke(svc, state.hardwareColorMode)
                        }
                    }
                }
                return HardwareApplyResult(
                    handledWarmTemp = true,
                    handledDim = true,
                    handledDesaturation = true,
                    handledFullLinearMatrix = true
                )
            }
        }

        // Priority 2: Non-Rooted Shizuku IColorDisplayManager Binder IPC (Android 12-16, OxygenOS 16+)
        if (isShizukuReady()) {
            val service = getColorDisplayService() ?: return HardwareApplyResult()
            captureSnapshotIfNeeded(service)

            var warmHandled = false
            var dimHandled = false
            var desatHandled = false

            // 1. Hardware Warm Color Temperature (1500 K .. 6500 K, strictly below 7985 K reversal)
            if (state.temperature > 0.02f) {
                val kelvin = (NIGHT_KELVIN_MAX * (NIGHT_KELVIN_MIN / NIGHT_KELVIN_MAX)
                    .pow(state.temperature.toDouble()))
                    .roundToInt()
                    .coerceIn(1500, 6500)
                val activated = invokeBoolean(service, "setNightDisplayActivated", java.lang.Boolean.TYPE, true)
                val tempSet = invokeBoolean(service, "setNightDisplayColorTemperature", java.lang.Integer.TYPE, kelvin)
                warmHandled = activated && tempSet
            } else {
                invokeBoolean(service, "setNightDisplayActivated", java.lang.Boolean.TYPE, false)
            }

            // 2. Hardware Extra Dim (ReduceBrightColors, API 31+, zero OLED black lift)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (state.dimStrength > 0.01f) {
                    val strength = (state.dimStrength / 0.72f * 100f).roundToInt().coerceIn(1, 100)
                    val act = invokeBoolean(service, "setReduceBrightColorsActivated", java.lang.Boolean.TYPE, true)
                    val str = invokeBoolean(service, "setReduceBrightColorsStrength", java.lang.Integer.TYPE, strength)
                    dimHandled = act && str
                } else {
                    invokeBoolean(service, "setReduceBrightColorsActivated", java.lang.Boolean.TYPE, false)
                }
            }

            // 3. Hardware Desaturation (0..100; note: some AOSP/Pixel builds may reassert system owner)
            if (state.saturation < -0.01f) {
                val level = ((1f + state.saturation) * 100f).roundToInt().coerceIn(0, 100)
                desatHandled = invokeBoolean(service, "setSaturationLevel", java.lang.Integer.TYPE, level)
            } else {
                invokeBoolean(service, "setSaturationLevel", java.lang.Integer.TYPE, 100)
            }

            // 4. Optional Hardware Gamut Mode (0=Natural, 1=Boosted, 2=Saturated, 3=Auto)
            state.hardwareColorMode?.let { mode ->
                runCatching {
                    service.javaClass
                        .getMethod("setColorMode", java.lang.Integer.TYPE)
                        .invoke(service, mode)
                }.onFailure { Log.w(TAG, "setColorMode($mode) unsupported", it) }
            }

            return HardwareApplyResult(
                handledWarmTemp = warmHandled,
                handledDim = dimHandled,
                handledDesaturation = desatHandled,
                handledFullLinearMatrix = false
            )
        }

        return HardwareApplyResult()
    }

    fun restoreHardware() {
        if (didWriteSurfaceFlingerMatrix) {
            val resetViaShizuku = if (isShizukuRoot()) {
                transactSurfaceFlinger1015ViaShizuku(null)
            } else {
                false
            }
            if (!resetViaShizuku && usedDirectSuForMatrix) {
                runRootCommand("service call SurfaceFlinger 1015 i32 0")
            }
            didWriteSurfaceFlingerMatrix = false
            usedDirectSuForMatrix = false
        }

        val saved = snapshot
        if (saved != null && isShizukuReady()) {
            getColorDisplayService()?.let { svc ->
                invokeBoolean(svc, "setNightDisplayColorTemperature", java.lang.Integer.TYPE, saved.nightKelvin)
                invokeBoolean(svc, "setNightDisplayActivated", java.lang.Boolean.TYPE, saved.nightActive)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    invokeBoolean(svc, "setReduceBrightColorsStrength", java.lang.Integer.TYPE, saved.extraDimStrength)
                    invokeBoolean(svc, "setReduceBrightColorsActivated", java.lang.Boolean.TYPE, saved.extraDimActive)
                }
                invokeBoolean(svc, "setSaturationLevel", java.lang.Integer.TYPE, 100)
                runCatching {
                    svc.javaClass
                        .getMethod("setColorMode", java.lang.Integer.TYPE)
                        .invoke(svc, saved.colorMode)
                }
            }
            snapshot = null
        }
    }

    private fun getColorDisplayService(): Any? = runCatching {
        if (!hiddenApiExempted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions(COLOR_DISPLAY_IFACE)
            hiddenApiExempted = true
        }
        val rawBinder: IBinder = SystemServiceHelper.getSystemService("color_display") ?: return null
        val stubClass = Class.forName("android.hardware.display.IColorDisplayManager\$Stub")
        stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(rawBinder))
    }.onFailure { Log.e(TAG, "Failed to obtain IColorDisplayManager via Shizuku", it) }.getOrNull()

    private fun captureSnapshotIfNeeded(service: Any) {
        if (snapshot != null) return
        val nightActive = runCatching {
            service.javaClass.getMethod("isNightDisplayActivated").invoke(service) as? Boolean
        }.getOrNull() ?: false
        val nightKelvin = runCatching {
            service.javaClass.getMethod("getNightDisplayColorTemperature").invoke(service) as? Int
        }.getOrNull() ?: 2850
        val extraDimActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                service.javaClass.getMethod("isReduceBrightColorsActivated").invoke(service) as? Boolean
            }.getOrNull() ?: false
        } else false
        val extraDimStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                service.javaClass.getMethod("getReduceBrightColorsStrength").invoke(service) as? Int
            }.getOrNull() ?: 50
        } else 50
        val colorMode = runCatching {
            service.javaClass.getMethod("getColorMode").invoke(service) as? Int
        }.getOrNull() ?: 0

        snapshot = SavedSystemSnapshot(
            nightActive = nightActive,
            nightKelvin = nightKelvin.coerceIn(1500, 6500),
            extraDimActive = extraDimActive,
            extraDimStrength = extraDimStrength.coerceIn(0, 100),
            colorMode = colorMode
        )
    }

    private fun invokeBoolean(service: Any, methodName: String, paramType: Class<*>, arg: Any): Boolean =
        runCatching {
            service.javaClass.getMethod(methodName, paramType).invoke(service, arg) as? Boolean ?: false
        }.onFailure { Log.w(TAG, "$methodName($arg) failed: ${it.message}") }.getOrDefault(false)

    /**
     * Composes an AOSP column-major 4x4 affine color matrix for SurfaceFlinger transaction 1015.
     * Evaluation order on column vector v: mDim * mSat * mContrast * mGain * v
     * Applying dim LAST ensures dimming scales the graded output linearly without distorting the 0.5 contrast pivot.
     */
    private fun buildSurfaceFlingerMatrix(s: DisplayEqState): FloatArray {
        val wbR = (1f + 0.22f * s.temperature + 0.08f * s.tint).coerceIn(0.4f, 1.3f)
        val wbG = (1f - 0.12f * s.tint).coerceIn(0.4f, 1.3f)
        val wbB = (1f - 0.28f * s.temperature + 0.08f * s.tint).coerceIn(0.3f, 1.3f)
        val maxWb = maxOf(wbR, wbG, wbB, 1f)

        val expScale = 2.0.pow(s.exposure.toDouble()).toFloat().coerceIn(0.25f, 2.0f)
        val gammaScale = 2.0.pow((1.0 - s.gamma.toDouble())).toFloat().coerceIn(0.35f, 1.6f)
        val preScale = expScale * gammaScale

        val mGain = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
            this[0] = (wbR / maxWb) * s.redGain * preScale
            this[5] = (wbG / maxWb) * s.greenGain * preScale
            this[10] = (wbB / maxWb) * s.blueGain * preScale
        }

        // Contrast around 0.5 pivot (Col 3 holds translation offsets)
        val slope = (1f + s.contrast * 0.55f).coerceIn(0.45f, 1.55f)
        val offset = 0.5f * (1f - slope)
        val mContrast = floatArrayOf(
            slope, 0f, 0f, 0f,
            0f, slope, 0f, 0f,
            0f, 0f, slope, 0f,
            offset, offset, offset, 1f
        )
        val step1 = FloatArray(16)
        Matrix.multiplyMM(step1, 0, mContrast, 0, mGain, 0)

        // Saturation matrix (supports both desaturation < 1.0 and boost > 1.0)
        val sat = (1f + s.saturation).coerceIn(0f, 1.85f)
        val invSat = 1f - sat
        val wr = 0.2126f
        val wg = 0.7152f
        val wb = 0.0722f
        val mSat = floatArrayOf(
            invSat * wr + sat, invSat * wr, invSat * wr, 0f,
            invSat * wg, invSat * wg + sat, invSat * wg, 0f,
            invSat * wb, invSat * wb, invSat * wb + sat, 0f,
            0f, 0f, 0f, 1f
        )
        val step2 = FloatArray(16)
        Matrix.multiplyMM(step2, 0, mSat, 0, step1, 0)

        // Final OLED-safe linear dim applied outermost
        val dimScale = (1f - s.dimStrength).coerceIn(0.25f, 1.0f)
        val mDim = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
            this[0] = dimScale
            this[5] = dimScale
            this[10] = dimScale
        }
        val finalMat = FloatArray(16)
        Matrix.multiplyMM(finalMat, 0, mDim, 0, step2, 0)
        return finalMat
    }

    /** Zero-process-fork AOSP DisplayTransformManager-identical Parcel IPC (requires Shizuku uid 0) */
    private fun transactSurfaceFlinger1015ViaShizuku(m: FloatArray?): Boolean {
        val rawFlinger = SystemServiceHelper.getSystemService("SurfaceFlinger") ?: return false
        val flinger = ShizukuBinderWrapper(rawFlinger)
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SF_INTERFACE_TOKEN)
            if (m != null && m.size == 16) {
                data.writeInt(1)
                for (i in 0 until 16) {
                    data.writeFloat(if (abs(m[i]) < 1e-6f) 0f else m[i])
                }
            } else {
                data.writeInt(0)
            }
            flinger.transact(SF_TRANSACTION_COLOR_MATRIX, data, null, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Direct Shizuku SurfaceFlinger 1015 transact failed: ${t.message}")
            false
        } finally {
            data.recycle()
        }
    }

    private fun sendSurfaceFlinger1015ViaSu(m: FloatArray): Boolean {
        if (m.size != 16) return false
        val sb = StringBuilder("service call SurfaceFlinger 1015 i32 1")
        for (i in 0 until 16) {
            val clean = if (abs(m[i]) < 1e-6f) 0f else m[i]
            sb.append(" f ").append(String.format(Locale.US, "%.5f", clean))
        }
        return runRootCommand(sb.toString())
    }

    private fun runRootCommand(cmd: String): Boolean = runCatching {
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val ok = code == 0 && !output.contains("Operation not permitted", ignoreCase = true)
        if (!ok) Log.w(TAG, "Root command failed ($code): $output")
        ok
    }.onFailure { Log.e(TAG, "Root exec error", it) }.getOrDefault(false)
}
