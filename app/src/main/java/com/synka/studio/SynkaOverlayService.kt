package com.synka.studio

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

class SynkaOverlayService : Service() {

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Helper {
        fun isFgsStartNotAllowed(e: IllegalStateException): Boolean {
            return e is ForegroundServiceStartNotAllowedException
        }
    }

    companion object {
        private const val CHANNEL_ID = "synka_screen_filter_channel"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.synka.studio.action.START_OVERLAY"
        private const val ACTION_TOGGLE_PAUSE = "com.synka.studio.action.TOGGLE_PAUSE_OVERLAY"
        private const val ACTION_STOP = "com.synka.studio.action.STOP_OVERLAY"

        private const val PREFS_NAME = "synka_system_bridge"
        private const val KEY_ARKA_JSON = "sys_arka_json"
        private const val KEY_MASTER = "sys_master_enabled"

        // Defensive margin below maxTouchOpacity against fp16 quantization jitter
        private const val TOUCH_ALPHA_SAFETY_EPSILON = 0.005f

        var isRunning: Boolean by mutableStateOf(false)
            private set

        var isPausedByUser: Boolean by mutableStateOf(false)
            private set

        var hasOverlayPermission: Boolean by mutableStateOf(false)
            private set

        var hasSecureSettingsGrant: Boolean by mutableStateOf(false)
            private set

        var permissionCheckGeneration: Int by mutableIntStateOf(0)
            private set

        var activeMaxTouchOpacity: Float by mutableFloatStateOf(0.80f)
            private set

        var activeAppliedAlpha: Float by mutableFloatStateOf(0f)
            private set

        var statusSummary: String by mutableStateOf("Tap to Enable")
            private set

        @Volatile
        private var instance: SynkaOverlayService? = null

        @Volatile
        private var latestParams: ArkaParams = ArkaParams()

        @Volatile
        private var latestMasterEnabled: Boolean = true

        @Volatile
        private var isSynkaUiForeground: Boolean = false

        fun refreshPermissionState(context: Context) {
            hasOverlayPermission = OverlayPermissionHelper.canDrawOverlays(context)
            hasSecureSettingsGrant = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
            activeMaxTouchOpacity = OverlayPermissionHelper.queryMaxTouchOpacity(context)
            permissionCheckGeneration++

            if (isRunning && !hasOverlayPermission) {
                stop(context)
            } else {
                instance?.syncOverlayState() ?: refreshStoppedStatus()
            }
        }

        fun setSynkaUiForeground(inForeground: Boolean) {
            isSynkaUiForeground = inForeground
            instance?.syncOverlayState()
        }

        /**
         * Launches the specialUse foreground service from foreground UI.
         * Returns true when start succeeds; catches FGS background-start restrictions cleanly.
         */
        fun start(context: Context): Boolean {
            val appCtx = context.applicationContext
            refreshPermissionState(appCtx)
            if (!hasOverlayPermission) {
                statusSummary = "Grant 'Display over other apps'"
                return false
            }

            val intent = Intent(appCtx, SynkaOverlayService::class.java).apply {
                action = ACTION_START
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
                true
            } catch (e: IllegalStateException) {
                val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    Api31Helper.isFgsStartNotAllowed(e)
                statusSummary = if (isFgsBlocked) {
                    "Open Synka to start Screen Filter"
                } else {
                    "Service start blocked"
                }
                false
            } catch (_: SecurityException) {
                statusSummary = "Overlay or FGS permission missing"
                false
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, SynkaOverlayService::class.java))
            }
            isRunning = false
            isPausedByUser = false
            activeAppliedAlpha = 0f
            refreshStoppedStatus()
        }

        fun togglePause(context: Context) {
            if (!isRunning) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, SynkaOverlayService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            runCatching { appCtx.startService(intent) }
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

            instance?.syncOverlayState()
        }

        private fun refreshStoppedStatus() {
            statusSummary = if (hasOverlayPermission) "Off" else "Tap to Enable"
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var overlayView: SinglePassOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached: Boolean = false
    private var daltonizerAppliedByUs: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        loadPersistedState()
        createNotificationChannelIfNeeded()
        initDisplayWindowManager()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCleanly()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                if (isRunning) {
                    isPausedByUser = !isPausedByUser
                    syncOverlayState()
                    updateOngoingNotification()
                    return START_STICKY
                }
            }
        }

        // Promote to foreground immediately on every startForegroundService() invocation
        if (!promoteToForegroundSafely()) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        hasOverlayPermission = OverlayPermissionHelper.canDrawOverlays(this)
        if (!hasOverlayPermission) {
            isRunning = false
            statusSummary = "Grant 'Display over other apps'"
            stopForegroundCleanly()
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        isPausedByUser = false
        syncOverlayState()
        updateOngoingNotification()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isOverlayAttached) {
            val wm = windowManager
            val view = overlayView
            val params = overlayParams
            if (wm != null && view != null && params != null) {
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    override fun onDestroy() {
        detachOverlaySafely()
        restoreDaltonizerIfNeeded()
        stopForegroundCleanly()
        if (instance === this) {
            instance = null
        }
        isRunning = false
        isPausedByUser = false
        activeAppliedAlpha = 0f
        refreshStoppedStatus()
        super.onDestroy()
    }

    private fun promoteToForegroundSafely(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: IllegalStateException) {
            statusSummary = "Foreground start restricted"
            false
        } catch (_: SecurityException) {
            statusSummary = "Missing FGS specialUse permission"
            false
        }
    }

    private fun stopForegroundCleanly() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun initDisplayWindowManager() {
        val uiCtx = createOverlayWindowContext()
        windowContext = uiCtx
        windowManager = uiCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    /**
     * Uses proper UI WindowContext on API 30+ (and Display overload on API 31+) to avoid
     * non-UI Context warnings and multi-display attachment faults.
     */
    private fun createOverlayWindowContext(): Context {
        return runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && defaultDisplay != null -> {
                    applicationContext.createWindowContext(
                        defaultDisplay,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && defaultDisplay != null -> {
                    applicationContext
                        .createDisplayContext(defaultDisplay)
                        .createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            null
                        )
                }
                else -> applicationContext
            }
        }.getOrDefault(applicationContext)
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        hasOverlayPermission = OverlayPermissionHelper.canDrawOverlays(this)
        hasSecureSettingsGrant = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        val maxTouchOpacity = OverlayPermissionHelper.queryMaxTouchOpacity(this)
        activeMaxTouchOpacity = maxTouchOpacity

        if (!isRunning) {
            detachOverlaySafely()
            restoreDaltonizerIfNeeded()
            activeAppliedAlpha = 0f
            return
        }

        if (!hasOverlayPermission) {
            detachOverlaySafely()
            restoreDaltonizerIfNeeded()
            activeAppliedAlpha = 0f
            statusSummary = "Overlay permission revoked"
            stopSelf()
            return
        }

        if (isPausedByUser || !latestMasterEnabled || isSynkaUiForeground) {
            detachOverlaySafely()
            restoreDaltonizerIfNeeded()
            activeAppliedAlpha = 0f
            statusSummary = when {
                isPausedByUser -> "Paused (Tap Notification to Resume)"
                !latestMasterEnabled -> "Bypassed (Master OFF)"
                isSynkaUiForeground -> "Armed (Active outside Synka)"
                else -> "Off"
            }
            return
        }

        val params = latestParams
        val wantsMono = isMonochromeGrade(params)
        if (wantsMono && hasSecureSettingsGrant) {
            applyMonochromeDaltonizer(true)
        } else {
            restoreDaltonizerIfNeeded()
        }

        val safeAlphaCeiling = (maxTouchOpacity - TOUCH_ALPHA_SAFETY_EPSILON)
            .coerceIn(0.05f, maxTouchOpacity)
        val composed = composeSingleWindowOverlay(params, safeAlphaCeiling)

        if (composed.windowAlpha <= 0.004f) {
            detachOverlaySafely()
            activeAppliedAlpha = 0f
        } else {
            val attached = attachOrUpdateOverlay(composed.opaqueRgbColor, composed.windowAlpha)
            activeAppliedAlpha = if (attached) composed.windowAlpha else 0f
        }

        statusSummary = when {
            wantsMono && daltonizerAppliedByUs -> "Active (Hardware B&W + Filter)"
            wantsMono && activeAppliedAlpha <= 0f -> "B&W needs ADB grant (Warmth/Dim active)"
            activeAppliedAlpha > 0f -> {
                val pct = (activeAppliedAlpha * 100f).roundToInt()
                val capPct = (maxTouchOpacity * 100f).roundToInt()
                "Active ($pct% / max $capPct% touch-safe)"
            }
            else -> "Armed (Move Warmth, Paper, Trim, or Dimmer)"
        }
    }

    private fun attachOrUpdateOverlay(opaqueRgbColor: Int, windowAlpha: Float): Boolean {
        val wm = windowManager ?: run {
            initDisplayWindowManager()
            windowManager
        } ?: return false

        val ctx = windowContext ?: applicationContext
        val view = overlayView ?: SinglePassOverlayView(ctx).also { overlayView = it }
        view.setOpaqueColor(opaqueRgbColor)

        val lp = overlayParams ?: createBaseLayoutParams().also { overlayParams = it }
        lp.alpha = windowAlpha

        return try {
            if (!isOverlayAttached) {
                wm.addView(view, lp)
                isOverlayAttached = true
            } else {
                wm.updateViewLayout(view, lp)
            }
            true
        } catch (_: WindowManager.BadTokenException) {
            isOverlayAttached = false
            statusSummary = "Overlay token denied"
            false
        } catch (_: WindowManager.InvalidDisplayException) {
            isOverlayAttached = false
            statusSummary = "Display unavailable"
            false
        } catch (_: SecurityException) {
            isOverlayAttached = false
            statusSummary = "Display over other apps denied"
            false
        } catch (_: Exception) {
            isOverlayAttached = false
            false
        }
    }

    private fun createBaseLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Never set FLAG_DIM_BEHIND (triggers untrusted touch blocking on API 31+)
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
        if (params.bands.any { it.solo }) return false
        return params.bands.all { it.mute || it.sat <= -92f }
    }

    private data class ComposedOverlay(
        val opaqueRgbColor: Int,
        val windowAlpha: Float
    )

    /**
     * Collapses tint (alphaT, rgbT) + black dimmer (alphaD) into one Porter-Duff SRC_OVER
     * straight-alpha layer whose LayoutParams.alpha is strictly <= maxAllowedAlpha.
     */
    private fun composeSingleWindowOverlay(
        params: ArkaParams,
        maxAllowedAlpha: Float
    ): ComposedOverlay {
        val warm = (params.warmth / 100f).coerceIn(-1f, 1f)
        val tint = (params.tint / 100f).coerceIn(-1f, 1f)
        val paper = (params.paper / 100f).coerceIn(0f, 1f)
        val trimR = (params.trimR / 50f).coerceIn(-1f, 1f)
        val trimG = (params.trimG / 50f).coerceIn(-1f, 1f)
        val trimB = (params.trimB / 50f).coerceIn(-1f, 1f)

        val globeWarm = ((params.shadowTintX + params.highlightTintX) * 0.25f).coerceIn(-0.5f, 0.5f)
        val globeTint = ((params.shadowTintY + params.highlightTintY) * 0.25f).coerceIn(-0.5f, 0.5f)

        val totalWarm = (warm + globeWarm).coerceIn(-1f, 1f)
        val totalTint = (tint + globeTint).coerceIn(-1f, 1f)

        var rAcc = 0f
        var gAcc = 0f
        var bAcc = 0f
        var tintAlphaAcc = 0f

        if (totalWarm > 0.01f) {
            val wAlpha = totalWarm * 0.34f
            rAcc += 255f * wAlpha
            gAcc += 165f * wAlpha
            bAcc += 52f * wAlpha
            tintAlphaAcc += wAlpha
        } else if (totalWarm < -0.01f) {
            val cAlpha = abs(totalWarm) * 0.28f
            rAcc += 90f * cAlpha
            gAcc += 172f * cAlpha
            bAcc += 255f * cAlpha
            tintAlphaAcc += cAlpha
        }

        if (totalTint > 0.01f) {
            val tAlpha = totalTint * 0.22f
            rAcc += 235f * tAlpha
            gAcc += 95f * tAlpha
            bAcc += 215f * tAlpha
            tintAlphaAcc += tAlpha
        } else if (totalTint < -0.01f) {
            val gAlpha = abs(totalTint) * 0.22f
            rAcc += 105f * gAlpha
            gAcc += 235f * gAlpha
            bAcc += 125f * gAlpha
            tintAlphaAcc += gAlpha
        }

        if (paper > 0.01f) {
            val pAlpha = paper * 0.22f
            rAcc += 238f * pAlpha
            gAcc += 222f * pAlpha
            bAcc += 188f * pAlpha
            tintAlphaAcc += pAlpha
        }

        val trimStrength = maxOf(abs(trimR), abs(trimG), abs(trimB))
        if (trimStrength > 0.02f) {
            val trAlpha = trimStrength * 0.18f
            rAcc += ((0.5f + trimR * 0.5f) * 255f) * trAlpha
            gAcc += ((0.5f + trimG * 0.5f) * 255f) * trAlpha
            bAcc += ((0.5f + trimB * 0.5f) * 255f) * trAlpha
            tintAlphaAcc += trAlpha
        }

        val alphaT = tintAlphaAcc.coerceIn(0f, 0.48f)
        val tintR = if (tintAlphaAcc > 0.0001f) (rAcc / tintAlphaAcc).coerceIn(0f, 255f) else 0f
        val tintG = if (tintAlphaAcc > 0.0001f) (gAcc / tintAlphaAcc).coerceIn(0f, 255f) else 0f
        val tintB = if (tintAlphaAcc > 0.0001f) (bAcc / tintAlphaAcc).coerceIn(0f, 255f) else 0f

        val normDim = (params.subzeroDimmer / 100f).coerceIn(0f, 1f)
        val alphaD = if (normDim <= 0.005f) 0f else (normDim * maxAllowedAlpha).coerceIn(0f, maxAllowedAlpha)

        val alphaEq = alphaD + alphaT * (1f - alphaD)
        if (alphaEq <= 0.004f) {
            return ComposedOverlay(Color.BLACK, 0f)
        }

        val tintWeight = (alphaT * (1f - alphaD)) / alphaEq
        val eqR = (tintR * tintWeight).roundToInt().coerceIn(0, 255)
        val eqG = (tintG * tintWeight).roundToInt().coerceIn(0, 255)
        val eqB = (tintB * tintWeight).roundToInt().coerceIn(0, 255)

        val safeWindowAlpha = alphaEq.coerceIn(0f, maxAllowedAlpha)
        return ComposedOverlay(Color.rgb(eqR, eqG, eqB), safeWindowAlpha)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Synka Screen Filter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Synka Screen Filter active with one-tap Pause and Off controls"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateOngoingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SynkaOverlayService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SynkaOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val pauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isPausedByUser) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            ),
            if (isPausedByUser) "Resume" else "Pause",
            pauseIntent
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Turn Off",
            stopIntent
        ).build()

        val subtitle = if (isPausedByUser) {
            "Filter paused — tap Resume to restore"
        } else {
            "Touch-safe overlay active across apps"
        }

        return builder
            .setContentTitle("Synka Screen Filter")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(launchIntent)
            .addAction(pauseAction)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private class SinglePassOverlayView(context: Context) : View(context) {
        private var opaqueColor: Int = Color.BLACK

        init {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        fun setOpaqueColor(color: Int) {
            if (opaqueColor != color) {
                opaqueColor = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(opaqueColor)
        }
    }
}
