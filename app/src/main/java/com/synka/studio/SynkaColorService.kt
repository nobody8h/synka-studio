package com.synka.studio

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

class SynkaColorService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "synka_system_bridge"
        private const val KEY_ENABLED = "sys_color_enabled"
        private const val KEY_MASTER = "sys_master_enabled"
        private const val KEY_ARKA_JSON = "sys_arka_json"

        var isServiceBound: Boolean by mutableStateOf(false)
            private set

        var isSystemColorEnabled: Boolean by mutableStateOf(false)
            private set

        var isCloaked: Boolean by mutableStateOf(false)
            private set

        var cloakedReason: String by mutableStateOf("")
            private set

        var statusSummary: String by mutableStateOf("Accessibility Off")
            private set

        var hasSecureSettingsGrant: Boolean by mutableStateOf(false)
            private set

        @Volatile
        private var instance: SynkaColorService? = null

        @Volatile
        private var latestParams: ArkaParams = ArkaParams()

        @Volatile
        private var latestMasterEnabled: Boolean = true

        fun refreshPermissions(context: Context) {
            hasSecureSettingsGrant = context.checkSelfPermission(
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            isSystemColorEnabled = enabled
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            instance?.syncOverlayState() ?: refreshDisconnectedStatus()
        }

        fun updateFromEngine(context: Context, params: ArkaParams, masterEnabled: Boolean) {
            val normalized = params.normalized()
            latestParams = normalized
            latestMasterEnabled = masterEnabled

            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MASTER, masterEnabled)
                .putString(KEY_ARKA_JSON, normalized.toJson().toString())
                .apply()

            instance?.syncOverlayState() ?: refreshDisconnectedStatus()
        }

        private fun refreshDisconnectedStatus() {
            statusSummary = if (isSystemColorEnabled) {
                "Enable 'Synka Screen Filter' in Accessibility"
            } else {
                "Off"
            }
        }

        // Built-in banking, UPI, wallet, and Android system security packages to auto-cloak
        private val CLOAK_EXACT_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp",                    // BHIM
            "com.dreamplug.androidapp",              // CRED
            "com.amazon.mShop.android.shopping",
            "com.paypal.android.p2pmobile",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vpndialogs"
        )

        private val CLOAK_PREFIXES = listOf(
            "com.sbi.",
            "com.icicibank.",
            "com.hdfc",
            "com.axis.",
            "com.kotak",
            "com.csam.icici",
            "com.snapwork.hdfc",
            "com.enstage.wibmo",
            "com.mobile.banking",
            "com.paypal.",
            "com.chase.",
            "com.bankofamerica."
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: ColorOverlayView? = null
    private var isOverlayAttached: Boolean = false
    private var currentForegroundPackage: String = ""
    private var daltonizerAppliedByUs: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceBound = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        loadPersistedState()
        refreshPermissions(this)

        // Auto-arm if user just returned from Accessibility settings after tapping enable
        if (!isSystemColorEnabled) {
            setEnabled(this, true)
        } else {
            syncOverlayState()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() |

| pkg == currentForegroundPackage) return

        // Ignore transient SystemUI volume/shade updates unless switching into actual app/installer
        if (pkg == "com.android.systemui") return

        currentForegroundPackage = pkg
        syncOverlayState()
    }

    override fun onInterrupt() {
        mainHandler.post {
            detachOverlaySafely()
            restoreDaltonizerIfNeeded()
        }
    }

    override fun onDestroy() {
        detachOverlaySafely()
        restoreDaltonizerIfNeeded()
        if (instance === this) {
            instance = null
        }
        isServiceBound = false
        isCloaked = false
        cloakedReason = ""
        refreshDisconnectedStatus()
        super.onDestroy()
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isSystemColorEnabled = prefs.getBoolean(KEY_ENABLED, false)
        latestMasterEnabled = prefs.getBoolean(KEY_MASTER, true)
        val rawJson = prefs.getString(KEY_ARKA_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            runCatching {
                latestParams = ArkaParams.fromJson(JSONObject(rawJson))
            }
        }
    }

    private fun syncOverlayState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncOverlayState() }
            return
        }

        refreshPermissions(this)

        val pkg = currentForegroundPackage
        val cloakHit = resolveCloakReason(pkg)

        if (!isSystemColorEnabled |

| !latestMasterEnabled |
| cloakHit != null) {
            detachOverlaySafely()
            restoreDaltonizerIfNeeded()
            isCloaked = cloakHit != null && isSystemColorEnabled && latestMasterEnabled
            cloakedReason = cloakHit.orEmpty()
            statusSummary = when {
                !isSystemColorEnabled -> "Off (Accessibility Ready)"
                !latestMasterEnabled -> "Bypassed (Master OFF)"
                cloakHit != null -> "Cloaked ($cloakHit)"
                else -> "Off"
            }
            return
        }

        isCloaked = false
        cloakedReason = ""

        val params = latestParams
        val tintArgb = computeTintOverlayArgb(params)
        val dimAlphaInt = computeDimmerAlphaInt(params)

        // Optional true hardware Monochrome via WRITE_SECURE_SETTINGS if user granted it
        val wantsMono = isMonochromeGrade(params)
        if (wantsMono && hasSecureSettingsGrant) {
            applyMonochromeDaltonizer(true)
        } else {
            restoreDaltonizerIfNeeded()
        }

        val hasVisibleOverlay = Color.alpha(tintArgb) > 0 |

| dimAlphaInt > 0
        if (hasVisibleOverlay) {
            ensureOverlayAttached()
            overlayView?.updateColors(tintArgb, dimAlphaInt)
        } else {
            detachOverlaySafely()
        }

        statusSummary = when {
            wantsMono && daltonizerAppliedByUs -> "Active (Hardware B&W + Filter)"
            wantsMono && !hasVisibleOverlay -> "B&W needs 1-time ADB grant (Dim/Warmth work now)"
            hasVisibleOverlay -> "Active over Apps (Warmth/Tint/Dim)"
            else -> "Armed (Move Warmth, Paper, Trim, or Dimmer)"
        }
    }

    private fun resolveCloakReason(pkg: String): String? {
        if (pkg.isBlank()) return null
        if (pkg == packageName) return "Synka Preview"
        if (CLOAK_EXACT_PACKAGES.contains(pkg)) {
            return if (pkg.contains("permission") |

| pkg.contains("installer")) {
                "System Dialog"
            } else {
                "Banking / Protected"
            }
        }
        if (CLOAK_PREFIXES.any { pkg.startsWith(it) }) {
            return "Banking App"
        }
        val lower = pkg.lowercase()
        if (lower.contains("bank") |

| lower.contains("upi") |
| lower.contains("wallet")) {
            return "Banking App"
        }
        return null
    }

    private fun ensureOverlayAttached() {
        if (isOverlayAttached && overlayView != null) return
        val wm = windowManager ?: return

        val view = overlayView ?: ColorOverlayView(this).also { overlayView = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        runCatching {
            wm.addView(view, params)
            isOverlayAttached = true
        }
    }

    private fun detachOverlaySafely() {
        val wm = windowManager
        val view = overlayView
        if (isOverlayAttached && wm != null && view != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        isOverlayAttached = false
    }

    private fun applyMonochromeDaltonizer(enable: Boolean) {
        if (!hasSecureSettingsGrant) return
        runCatching {
            if (enable) {
                Settings.Secure.putString(contentResolver, "accessibility_display_daltonizer_enabled", "1")
                Settings.Secure.putString(contentResolver, "accessibility_display_daltonizer", "0")
                daltonizerAppliedByUs = true
            } else {
                Settings.Secure.putString(contentResolver, "accessibility_display_daltonizer_enabled", "0")
                Settings.Secure.putString(contentResolver, "accessibility_display_daltonizer", "-1")
                daltonizerAppliedByUs = false
            }
        }
    }

    private fun restoreDaltonizerIfNeeded() {
        if (daltonizerAppliedByUs) {
            applyMonochromeDaltonizer(false)
        }
    }

    private fun isMonochromeGrade(params: ArkaParams): Boolean {
        val anySolo = params.bands.any { it.solo }
        if (anySolo) return false
        return params.bands.all { it.mute |

| it.sat <= -92f }
    }

    private fun computeDimmerAlphaInt(params: ArkaParams): Int {
        val normDim = (params.subzeroDimmer / 100f).coerceIn(0f, 1f)
        if (normDim <= 0.005f) return 0
        return (normDim * 0.82f * 255f).roundToInt().coerceIn(0, 210)
    }

    private fun computeTintOverlayArgb(params: ArkaParams): Int {
        val warm = (params.warmth / 100f).coerceIn(-1f, 1f)
        val tint = (params.tint / 100f).coerceIn(-1f, 1f)
        val paper = (params.paper / 100f).coerceIn(0f, 1f)
        val trimR = (params.trimR / 50f).coerceIn(-1f, 1f)
        val trimG = (params.trimG / 50f).coerceIn(-1f, 1f)
        val trimB = (params.trimB / 50f).coerceIn(-1f, 1f)

        // Include gentle contribution from shadow/highlight tint globes so Cinema presets tint cleanly
        val globeWarm = ((params.shadowTintX + params.highlightTintX) * 0.25f).coerceIn(-0.5f, 0.5f)
        val globeTint = ((params.shadowTintY + params.highlightTintY) * 0.25f).coerceIn(-0.5f, 0.5f)

        val totalWarm = (warm + globeWarm).coerceIn(-1f, 1f)
        val totalTint = (tint + globeTint).coerceIn(-1f, 1f)

        var rAcc = 0f
        var gAcc = 0f
        var bAcc = 0f
        var alphaAcc = 0f

        if (totalWarm > 0.01f) {
            val wAlpha = totalWarm * 0.34f
            rAcc += 255f * wAlpha
            gAcc += 165f * wAlpha
            bAcc += 52f * wAlpha
            alphaAcc += wAlpha
        } else if (totalWarm < -0.01f) {
            val cAlpha = abs(totalWarm) * 0.28f
            rAcc += 90f * cAlpha
            gAcc += 172f * cAlpha
            bAcc += 255f * cAlpha
            alphaAcc += cAlpha
        }

        if (totalTint > 0.01f) {
            val tAlpha = totalTint * 0.22f
            rAcc += 235f * tAlpha
            gAcc += 95f * tAlpha
            bAcc += 215f * tAlpha
            alphaAcc += tAlpha
        } else if (totalTint < -0.01f) {
            val gAlpha = abs(totalTint) * 0.22f
            rAcc += 105f * gAlpha
            gAcc += 235f * gAlpha
            bAcc += 125f * gAlpha
            alphaAcc += gAlpha
        }

        if (paper > 0.01f) {
            val pAlpha = paper * 0.22f
            rAcc += 238f * pAlpha
            gAcc += 222f * pAlpha
            bAcc += 188f * pAlpha
            alphaAcc += pAlpha
        }

        val trimStrength = maxOf(abs(trimR), abs(trimG), abs(trimB))
        if (trimStrength > 0.02f) {
            val trAlpha = trimStrength * 0.18f
            rAcc += ((0.5f + trimR * 0.5f) * 255f) * trAlpha
            gAcc += ((0.5f + trimG * 0.5f) * 255f) * trAlpha
            bAcc += ((0.5f + trimB * 0.5f) * 255f) * trAlpha
            alphaAcc += trAlpha
        }

        if (alphaAcc <= 0.004f) return 0
        val finalAlpha = (alphaAcc.coerceIn(0f, 0.48f) * 255f).roundToInt().coerceIn(0, 125)
        val r = (rAcc / alphaAcc).roundToInt().coerceIn(0, 255)
        val g = (gAcc / alphaAcc).roundToInt().coerceIn(0, 255)
        val b = (bAcc / alphaAcc).roundToInt().coerceIn(0, 255)
        return Color.argb(finalAlpha, r, g, b)
    }

    private class ColorOverlayView(context: Context) : View(context) {
        private var tintColor: Int = 0
        private var dimAlpha: Int = 0

        init {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        fun updateColors(newTint: Int, newDimAlpha: Int) {
            if (tintColor != newTint |

| dimAlpha != newDimAlpha) {
                tintColor = newTint
                dimAlpha = newDimAlpha
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (Color.alpha(tintColor) > 0) {
                canvas.drawColor(tintColor)
            }
            if (dimAlpha > 0) {
                canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
            }
        }
    }
}
