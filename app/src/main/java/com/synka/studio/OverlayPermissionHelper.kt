package com.synka.studio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

enum class InstallChannel {
    TRUSTED_STORE,
    SIDELOADED_FILE,
    ADB_OR_SHELL,
    UNKNOWN_INSTALLER
}

data class InstallSourceReport(
    val channel: InstallChannel,
    val installerPackage: String?,
    val initiatingPackage: String?,
    val packageSourceCode: Int,
    val packageSourceLabel: String,
    val isEcmLikelyForOverlay: Boolean
)

object OverlayPermissionHelper {

    const val ANDROID_15_API: Int = 35
    private const val DEFAULT_MAX_TOUCH_OPACITY: Float = 0.80f

    private val KNOWN_ALLOWLISTED_STORES = setOf(
        "com.android.vending",
        "com.amazon.venezia",
        "com.sec.android.app.samsungapps",
        "com.heytap.market",
        "com.oppo.market",
        "com.oneplus.market",
        "com.xiaomi.mipicks",
        "com.huawei.appmarket"
    )

    private val SIDELOAD_INSTALLER_PACKAGES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.oplus.appdetail",
        "com.coloros.appdetail",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    fun canDrawOverlays(context: Context): Boolean {
        return runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }

    /**
     * Queries Android 12+ (API 31+) InputManager.getMaximumObscuringOpacityForTouch().
     * Falls back safely to 0.80f on API 26-30 or if an OEM supplies an out-of-range value.
     */
    fun queryMaxTouchOpacity(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return DEFAULT_MAX_TOUCH_OPACITY
        }
        val raw = runCatching {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            inputManager?.maximumObscuringOpacityForTouch
        }.getOrNull()

        return if (raw != null && raw.isFinite() && raw in 0.05f..1.0f) {
            raw
        } else {
            DEFAULT_MAX_TOUCH_OPACITY
        }
    }

    fun isOnePlusOrOplusDevice(): Boolean {
        val mfr = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)
        return mfr.contains("oneplus") ||
            brand.contains("oneplus") ||
            mfr.contains("oppo") ||
            brand.contains("realme")
    }

    fun isPhoneCallActive(context: Context): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode = am?.mode ?: AudioManager.MODE_NORMAL
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    mode == AudioManager.MODE_CALL_SCREENING)
        }.getOrDefault(false)
    }

    /**
     * Inspects install provenance across API 26-36.
     * EnhancedConfirmationManager is @SystemApi and inaccessible to regular apps, so
     * isEcmLikelyForOverlay combines API >= 35 with installer and PACKAGE_SOURCE_* heuristics.
     */
    fun inspectInstallSource(context: Context): InstallSourceReport {
        val pm = context.packageManager
        val pkg = context.packageName

        var installerPkg: String? = null
        var initiatingPkg: String? = null
        var packageSourceCode: Int = -1

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(pkg)
                installerPkg = info.installingPackageName
                initiatingPkg = info.initiatingPackageName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageSourceCode = info.packageSource
                }
            } else {
                @Suppress("DEPRECATION")
                installerPkg = pm.getInstallerPackageName(pkg)
            }
        }

        val sourceLabel = resolvePackageSourceLabel(packageSourceCode)

        val isDangerousSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageSourceCode == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                packageSourceCode == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        } else {
            false
        }

        val effectiveInstaller = installerPkg ?: initiatingPkg
        val channel = when {
            isDangerousSource -> InstallChannel.SIDELOADED_FILE
            effectiveInstaller == "com.android.shell" -> InstallChannel.ADB_OR_SHELL
            effectiveInstaller != null && KNOWN_ALLOWLISTED_STORES.contains(effectiveInstaller) ->
                InstallChannel.TRUSTED_STORE
            effectiveInstaller != null && SIDELOAD_INSTALLER_PACKAGES.contains(effectiveInstaller) ->
                InstallChannel.SIDELOADED_FILE
            effectiveInstaller == null -> InstallChannel.ADB_OR_SHELL
            else -> InstallChannel.UNKNOWN_INSTALLER
        }

        val isAndroid15Plus = Build.VERSION.SDK_INT >= ANDROID_15_API
        val isEcmLikely = isAndroid15Plus && channel != InstallChannel.TRUSTED_STORE

        return InstallSourceReport(
            channel = channel,
            installerPackage = installerPkg,
            initiatingPackage = initiatingPkg,
            packageSourceCode = packageSourceCode,
            packageSourceLabel = sourceLabel,
            isEcmLikelyForOverlay = isEcmLikely
        )
    }

    private fun resolvePackageSourceLabel(code: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "N/A (API < 33)"
        }
        return when (code) {
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED -> "UNSPECIFIED (0)"
            PackageInstaller.PACKAGE_SOURCE_OTHER -> "OTHER (1)"
            PackageInstaller.PACKAGE_SOURCE_STORE -> "STORE (2)"
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "LOCAL_FILE (3)"
            PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "DOWNLOADED_FILE (4)"
            else -> "UNKNOWN ($code)"
        }
    }

    /**
     * Launches 'Display over other apps'.
     * On API 30+, Android ignores package: URIs and opens the top-level list; on API 26-29,
     * attaching package: opens Synka Studio's permission screen directly.
     */
    fun openOverlayPermissionSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                data = Uri.parse("package:${context.packageName}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return if (launched) true else openAppInfoSettings(context)
    }

    /**
     * Opens Synka Studio's App Info screen directly so the user can tap
     * top-right three-dot menu (⋮) -> Allow restricted settings.
     */
    fun openAppInfoSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Play-policy-safe link to system battery optimization list (does not require
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
     */
    fun openBatterySettings(context: Context): Boolean {
        val ignoreOptIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(ignoreOptIntent)
            true
        }.getOrDefault(false)
        return if (launched) true else openAppInfoSettings(context)
    }
}
