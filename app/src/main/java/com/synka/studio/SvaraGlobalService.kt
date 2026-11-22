package com.synka.studio

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class SvaraGlobalService : Service() {

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Helper {
        fun isFgsStartNotAllowed(e: IllegalStateException): Boolean {
            return e is ForegroundServiceStartNotAllowedException
        }
    }

    companion object {
        private const val CHANNEL_ID = "synka_global_eq_channel"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.synka.studio.action.START_GLOBAL_EQ"
        private const val ACTION_STOP = "com.synka.studio.action.STOP_GLOBAL_EQ"

        private const val PREFS_NAME = "synka_system_bridge"
        private const val KEY_SVARA_JSON = "sys_svara_json"
        private const val KEY_MASTER = "sys_master_enabled"
        private const val KEY_SESSION0_FALLBACK = "sys_session0_fallback"

        private const val EFFECT_PRIORITY = 100
        private const val PLAYER_STATE_STARTED = 2

        private val BAND_CUTOFFS_HZ = floatArrayOf(
            40f, 62f, 95f, 140f,
            210f, 320f, 480f, 720f,
            1100f, 1700f, 2600f, 4000f,
            6200f, 9500f, 14000f, 20000f
        )
        private val BAND_EVAL_FREQS_HZ = floatArrayOf(
            30f, 50f, 78f, 118f,
            175f, 265f, 400f, 600f,
            900f, 1400f, 2150f, 3300f,
            5100f, 7800f, 11800f, 16500f
        )

        var isRunning: Boolean by mutableStateOf(false)
            private set

        var statusSummary: String by mutableStateOf("Off")
            private set

        var activeEngineLabel: String by mutableStateOf("Idle")
            private set

        var hasControlOverSession: Boolean by mutableStateOf(true)
            private set

        var session0FallbackEnabled: Boolean by mutableStateOf(true)
            private set

        @Volatile
        private var instance: SvaraGlobalService? = null

        @Volatile
        private var latestParams: SvaraParams = SvaraParams()

        @Volatile
        private var latestMasterEnabled: Boolean = true

        @Volatile
        private var latestLocalPlayerPlaying: Boolean = false

        @Volatile
        private var hiddenApiExempted: Boolean = false

        fun hasDumpPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.DUMP
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun setSession0FallbackEnabled(context: Context, enabled: Boolean) {
            session0FallbackEnabled = enabled
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION0_FALLBACK, enabled)
                .apply()
            instance?.postApplyAndSync()
        }

        fun onNotificationSessionHint(@Suppress("UNUSED_PARAMETER") context: Context) {
            instance?.postDiscoverAndSync()
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                statusSummary = "Requires Android 9+ (API 28+)"
                return
            }
            val intent = Intent(appCtx, SvaraGlobalService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: IllegalStateException) {
                val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    Api31Helper.isFgsStartNotAllowed(e)
                statusSummary = if (isFgsBlocked) {
                    "Open Synka to start System EQ"
                } else {
                    "Service start blocked"
                }
            } catch (_: SecurityException) {
                statusSummary = "Missing mediaPlayback FGS permission"
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, SvaraGlobalService::class.java))
            }
            isRunning = false
            activeEngineLabel = "Idle"
            hasControlOverSession = true
            statusSummary = "Off"
        }

        fun updateFromEngine(
            context: Context,
            params: SvaraParams,
            masterEnabled: Boolean,
            localPlayerPlaying: Boolean
        ) {
            val normalized = params.normalized()
            latestParams = normalized
            latestMasterEnabled = masterEnabled
            latestLocalPlayerPlaying = localPlayerPlaying

            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MASTER, masterEnabled)
                .putString(KEY_SVARA_JSON, normalized.toJson().toString())
                .apply()

            instance?.postApplyAndSync()
        }
    }

    private sealed class SessionEffectHolder(
        val sessionId: Int,
        val packageNameHint: String
    ) {
        abstract fun hasControl(): Boolean
        abstract fun release()

        class DpHolder(
            sessionId: Int,
            packageNameHint: String,
            val dp: DynamicsProcessing,
            var controlGranted: Boolean = true
        ) : SessionEffectHolder(sessionId, packageNameHint) {
            override fun hasControl(): Boolean =
                controlGranted && runCatching { dp.hasControl() }.getOrDefault(false)

            override fun release() {
                runCatching { dp.setControlStatusListener(null) }
                runCatching { dp.setEnabled(false) }
                runCatching { dp.release() }
            }
        }

        class LegacyEqHolder(
            sessionId: Int,
            packageNameHint: String,
            val eq: Equalizer,
            var controlGranted: Boolean = true
        ) : SessionEffectHolder(sessionId, packageNameHint) {
            override fun hasControl(): Boolean =
                controlGranted && runCatching { eq.hasControl() }.getOrDefault(false)

            override fun release() {
                runCatching { eq.setControlStatusListener(null) }
                runCatching { eq.setEnabled(false) }
                runCatching { eq.release() }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var session0Holder: SessionEffectHolder? = null
    private var session0Failed: Boolean = false
    private val perSessionHolders = LinkedHashMap<Int, SessionEffectHolder>()
    private var lastNotifiedSummary: String? = null

    @Volatile
    private var isCallOrRingtoneActive: Boolean = false

    private val sessionBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
            if (sessionId <= 0) return
            if (pkg == packageName) return

            mainHandler.post {
                when (action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                        attachPerSessionEffect(sessionId, pkg)
                        releaseSession0Holder()
                        applyCurrentSettings()
                    }
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                        releasePerSessionEffect(sessionId)
                        syncSession0FallbackState()
                        applyCurrentSettings()
                    }
                }
            }
        }
    }

    private var modeListener: Any? = null
    private var playbackCallback: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        loadPersistedState()
        ensureHiddenApiExempted()
        createNotificationChannelIfNeeded()
        registerCallAndPlaybackMonitors()
        registerSessionBroadcasts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCleanly()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForegroundSafely()) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            isRunning = false
            statusSummary = "Requires Android 9+ (API 28)"
            stopForegroundCleanly()
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        session0Failed = false
        refreshCallState()
        discoverActiveSessionsIfPermitted()
        syncSession0FallbackState()
        applyCurrentSettings()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        unregisterMonitors()
        runCatching { unregisterReceiver(sessionBroadcastReceiver) }
        releaseAllEffects()
        stopForegroundCleanly()
        if (instance === this) {
            instance = null
        }
        isRunning = false
        activeEngineLabel = "Idle"
        hasControlOverSession = true
        statusSummary = "Off"
        lastNotifiedSummary = null
        super.onDestroy()
    }

    private fun postApplyAndSync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isRunning) return
            syncSession0FallbackState()
            applyCurrentSettings()
        } else {
            mainHandler.post {
                if (!isRunning) return@post
                syncSession0FallbackState()
                applyCurrentSettings()
            }
        }
    }

    private fun postDiscoverAndSync() {
        mainHandler.post {
            if (!isRunning) return@post
            discoverActiveSessionsIfPermitted()
            syncSession0FallbackState()
            applyCurrentSettings()
        }
    }

    private fun promoteToForegroundSafely(): Boolean {
        val notification = buildNotification()
        return try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        } catch (_: IllegalStateException) {
            statusSummary = "Foreground start restricted"
            false
        } catch (_: SecurityException) {
            statusSummary = "Missing mediaPlayback FGS permission"
            false
        }
    }

    private fun stopForegroundCleanly() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        latestMasterEnabled = prefs.getBoolean(KEY_MASTER, true)
        session0FallbackEnabled = prefs.getBoolean(KEY_SESSION0_FALLBACK, true)
        val raw = prefs.getString(KEY_SVARA_JSON, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                latestParams = SvaraParams.fromJson(JSONObject(raw))
            }
        }
    }

    private fun ensureHiddenApiExempted() {
        if (hiddenApiExempted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/media/AudioPlaybackConfiguration;"
                )
                hiddenApiExempted = true
            }
        }
    }

    private fun discoverActiveSessionsIfPermitted(
        configsOverride: List<AudioPlaybackConfiguration>? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!hasDumpPermission(this)) return

        ensureHiddenApiExempted()
        val configs = configsOverride ?: runCatching {
            audioManager?.activePlaybackConfigurations
        }.getOrNull().orEmpty()

        val activeIds = LinkedHashSet<Int>()
        for (cfg in configs) {
            val usage = cfg.audioAttributes.usage
            val isMediaUsage = usage == AudioAttributes.USAGE_MEDIA ||
                usage == AudioAttributes.USAGE_GAME ||
                usage == AudioAttributes.USAGE_UNKNOWN
            if (!isMediaUsage) continue

            val state = runCatching {
                HiddenApiBypass.invoke(
                    AudioPlaybackConfiguration::class.java,
                    cfg,
                    "getPlayerState"
                ) as? Int
            }.getOrNull() ?: continue
            if (state != PLAYER_STATE_STARTED) continue

            val clientUid = runCatching {
                HiddenApiBypass.invoke(
                    AudioPlaybackConfiguration::class.java,
                    cfg,
                    "getClientUid"
                ) as? Int
            }.getOrNull()
            if (clientUid == Process.myUid()) continue

            val sessionId = runCatching {
                HiddenApiBypass.invoke(
                    AudioPlaybackConfiguration::class.java,
                    cfg,
                    "getSessionId"
                ) as? Int
            }.getOrNull() ?: 0

            if (sessionId > 0) {
                activeIds.add(sessionId)
                val pkgHint = SvaraNotificationListenerService.activeMediaPackages
                    .firstOrNull()
                    .orEmpty()
                attachPerSessionEffect(sessionId, pkgHint)
            }
        }

        if (activeIds.isNotEmpty()) {
            releaseSession0Holder()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDpConfig(): DynamicsProcessing.Config {
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            BAND_CUTOFFS_HZ.size,
            false,
            0,
            false,
            0,
            true
        )
            .setPreferredFrameDuration(10f)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createSessionHolder(sessionId: Int, pkgHint: String): SessionEffectHolder? {
        val dpAttempt = try {
            val dp = DynamicsProcessing(EFFECT_PRIORITY, sessionId, createDpConfig())
            val holder = SessionEffectHolder.DpHolder(sessionId, pkgHint, dp, dp.hasControl())
            dp.setControlStatusListener { _, controlGranted ->
                mainHandler.post {
                    holder.controlGranted = controlGranted
                    if (!controlGranted && perSessionHolders[sessionId] === holder) {
                        val fallback = createLegacyEqHolder(sessionId, pkgHint)
                        if (fallback != null) {
                            holder.release()
                            perSessionHolders[sessionId] = fallback
                            applyCurrentSettings()
                        } else {
                            updateStatusText()
                            updateOngoingNotificationIfChanged()
                        }
                    } else if (!controlGranted && session0Holder === holder) {
                        updateStatusText()
                        updateOngoingNotificationIfChanged()
                    } else {
                        applyCurrentSettings()
                    }
                }
            }
            if (!holder.hasControl()) {
                holder.release()
                null
            } else {
                holder
            }
        } catch (_: Throwable) {
            null
        }

        if (dpAttempt != null) return dpAttempt
        return createLegacyEqHolder(sessionId, pkgHint)
    }

    private fun createLegacyEqHolder(sessionId: Int, pkgHint: String): SessionEffectHolder.LegacyEqHolder? {
        return try {
            val eq = Equalizer(EFFECT_PRIORITY, sessionId)
            val holder = SessionEffectHolder.LegacyEqHolder(sessionId, pkgHint, eq, eq.hasControl())
            eq.setControlStatusListener { _, controlGranted ->
                mainHandler.post {
                    holder.controlGranted = controlGranted
                    updateStatusText()
                    updateOngoingNotificationIfChanged()
                }
            }
            if (!holder.hasControl()) {
                holder.release()
                null
            } else {
                holder
            }
        } catch (_: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun attachPerSessionEffect(sessionId: Int, pkgHint: String) {
        val existing = perSessionHolders[sessionId]
        if (existing != null) {
            if (existing.hasControl()) return
            existing.release()
            perSessionHolders.remove(sessionId)
        }
        val created = createSessionHolder(sessionId, pkgHint) ?: return
        perSessionHolders[sessionId] = created
    }

    private fun releasePerSessionEffect(sessionId: Int) {
        perSessionHolders.remove(sessionId)?.release()
    }

    private fun syncSession0FallbackState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!isRunning) return

        val shouldHoldSession0 = perSessionHolders.isEmpty() && session0FallbackEnabled
        if (!shouldHoldSession0) {
            releaseSession0Holder()
            return
        }

        val current = session0Holder
        if (current != null && current.hasControl()) return
        current?.release()
        session0Holder = null

        if (session0Failed) return
        val created = createSessionHolder(0, "Global Mix")
        if (created != null) {
            session0Holder = created
        } else {
            session0Failed = true
        }
    }

    private fun releaseSession0Holder() {
        session0Holder?.release()
        session0Holder = null
    }

    private fun releaseAllEffects() {
        releaseSession0Holder()
        perSessionHolders.values.forEach { it.release() }
        perSessionHolders.clear()
    }

    private fun applyCurrentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val shouldEnable = latestMasterEnabled && !isCallOrRingtoneActive && !latestLocalPlayerPlaying
        val stages = buildBiquadStages(latestParams)
        val bandGains = evaluateComposite16BandGains(stages)
        val preAmpDb = latestParams.preAmpDb.coerceIn(-12f, 12f)
        val limiter = buildLimiter(latestParams)

        val brokenSessions = ArrayList<Int>()
        for ((sessionId, holder) in perSessionHolders) {
            val ok = pushSettingsToHolder(holder, stages, bandGains, preAmpDb, limiter, shouldEnable)
            if (!ok) {
                brokenSessions.add(sessionId)
            }
        }
        for (sessionId in brokenSessions) {
            val old = perSessionHolders.remove(sessionId)
            val pkgHint = old?.packageNameHint.orEmpty()
            old?.release()
            val fallback = createLegacyEqHolder(sessionId, pkgHint)
            if (fallback != null) {
                perSessionHolders[sessionId] = fallback
                pushSettingsToHolder(fallback, stages, bandGains, preAmpDb, limiter, shouldEnable)
            }
        }

        if (perSessionHolders.isEmpty()) {
            session0Holder?.let { holder ->
                val ok = pushSettingsToHolder(holder, stages, bandGains, preAmpDb, limiter, shouldEnable)
                if (!ok && holder is SessionEffectHolder.DpHolder) {
                    holder.release()
                    val fallback = createLegacyEqHolder(0, "Global Mix")
                    session0Holder = fallback
                    if (fallback != null) {
                        pushSettingsToHolder(fallback, stages, bandGains, preAmpDb, limiter, shouldEnable)
                    } else {
                        session0Failed = true
                    }
                }
            }
        }

        updateStatusText()
        updateOngoingNotificationIfChanged()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun pushSettingsToHolder(
        holder: SessionEffectHolder,
        stages: List<Coeffs>,
        bandGains: FloatArray,
        preAmpDb: Float,
        limiter: DynamicsProcessing.Limiter,
        shouldEnable: Boolean
    ): Boolean {
        return when (holder) {
            is SessionEffectHolder.DpHolder -> {
                val dp = holder.dp
                try {
                    if (!dp.hasControl()) {
                        holder.controlGranted = false
                        false
                    } else {
                        dp.setInputGainAllChannelsTo(preAmpDb)
                        val eqStage = DynamicsProcessing.Eq(true, true, BAND_CUTOFFS_HZ.size)
                        for (i in BAND_CUTOFFS_HZ.indices) {
                            eqStage.setBand(
                                i,
                                DynamicsProcessing.EqBand(true, BAND_CUTOFFS_HZ[i], bandGains[i])
                            )
                        }
                        dp.setPreEqAllChannelsTo(eqStage)
                        dp.setLimiterAllChannelsTo(limiter)
                        dp.setEnabled(shouldEnable) == AudioEffect.SUCCESS
                    }
                } catch (_: Throwable) {
                    false
                }
            }
            is SessionEffectHolder.LegacyEqHolder -> {
                val eq = holder.eq
                try {
                    if (!eq.hasControl()) {
                        holder.controlGranted = false
                        false
                    } else {
                        val bandCount = eq.numberOfBands.toInt()
                        val range = eq.bandLevelRange
                        val minMb = range[0].toInt()
                        val maxMb = range[1].toInt()
                        for (b in 0 until bandCount) {
                            val bandIdx = b.toShort()
                            val centerHz = (eq.getCenterFreq(bandIdx) / 1000.0).coerceAtLeast(20.0)
                            val totalDb = (evaluateGainAtHz(stages, centerHz) + preAmpDb)
                                .coerceIn(-15f, 15f)
                            val targetMb = (totalDb * 100f).roundToInt().coerceIn(minMb, maxMb).toShort()
                            eq.setBandLevel(bandIdx, targetMb)
                        }
                        eq.setEnabled(shouldEnable) == AudioEffect.SUCCESS
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildLimiter(params: SvaraParams): DynamicsProcessing.Limiter {
        val normAntiCrackle = (params.antiCrackle / 100f).coerceIn(0f, 1f)
        val thresholdDb = if (normAntiCrackle <= 0.01f) 0f else -(normAntiCrackle * 6.0f)
        return DynamicsProcessing.Limiter(
            true,
            normAntiCrackle > 0.01f,
            0,
            2f,
            80f,
            10f,
            thresholdDb,
            0f
        )
    }

    private fun updateStatusText() {
        val activePerSession = perSessionHolders.values
        val anyPerSession = activePerSession.isNotEmpty()
        val controlledPerSession = activePerSession.any { it.hasControl() }
        val controlledSession0 = session0Holder?.hasControl() == true

        hasControlOverSession = when {
            anyPerSession -> controlledPerSession
            session0Holder != null -> controlledSession0
            else -> true
        }

        activeEngineLabel = when {
            anyPerSession && activePerSession.any { it is SessionEffectHolder.DpHolder } -> "DynamicsProcessing (Per-App)"
            anyPerSession -> "Legacy Equalizer (Per-App)"
            session0Holder is SessionEffectHolder.DpHolder -> "DynamicsProcessing (Session 0)"
            session0Holder is SessionEffectHolder.LegacyEqHolder -> "Legacy Equalizer (Session 0)"
            else -> "Waiting"
        }

        val notifMediaPkg = SvaraNotificationListenerService.activeMediaPackages.firstOrNull()

        statusSummary = when {
            !isRunning -> "Off"
            isCallOrRingtoneActive -> "Paused (Call / Ringtone Active)"
            latestLocalPlayerPlaying -> "Yielded to Local Svara DSP"
            !latestMasterEnabled -> "Bypassed (Master OFF)"
            anyPerSession && !controlledPerSession -> "Control blocked by OEM / another EQ"
            anyPerSession -> {
                val hint = activePerSession.firstOrNull { it.packageNameHint.isNotBlank() }?.packageNameHint
                if (!hint.isNullOrBlank()) {
                    "Active on $hint (${perSessionHolders.size} session)"
                } else {
                    "Active on ${perSessionHolders.size} App Session(s)"
                }
            }
            session0Holder != null && !controlledSession0 -> "Session 0 control taken by OEM EQ"
            session0Holder != null -> {
                if (!notifMediaPkg.isNullOrBlank()) {
                    "Compatibility Mix active ($notifMediaPkg)"
                } else {
                    "Active on Compatibility Mix (Session 0)"
                }
            }
            session0Failed && !notifMediaPkg.isNullOrBlank() ->
                "Detected $notifMediaPkg — needs session broadcast or ADB DUMP"
            session0Failed -> "Session 0 blocked by HAL — waiting for music player"
            else -> "Waiting for Media Playback"
        }
    }

    private fun registerSessionBroadcasts() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionBroadcastReceiver, filter)
        }
    }

    private fun registerCallAndPlaybackMonitors() {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener {
                mainHandler.post {
                    refreshCallState()
                    applyCurrentSettings()
                }
            }
            modeListener = listener
            runCatching { am.addOnModeChangedListener(mainExecutor, listener) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                    mainHandler.post {
                        refreshCallState(configs)
                        discoverActiveSessionsIfPermitted(configs)
                        syncSession0FallbackState()
                        applyCurrentSettings()
                    }
                }
            }
            playbackCallback = cb
            runCatching { am.registerAudioPlaybackCallback(cb, mainHandler) }
        }
    }

    private fun unregisterMonitors() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let {
                runCatching { am.removeOnModeChangedListener(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (playbackCallback as? AudioManager.AudioPlaybackCallback)?.let {
                runCatching { am.unregisterAudioPlaybackCallback(it) }
            }
        }
    }

    private fun refreshCallState(configs: List<AudioPlaybackConfiguration>? = null) {
        val am = audioManager
        val mode = am?.mode ?: AudioManager.MODE_NORMAL
        val modeBusy = when {
            mode == AudioManager.MODE_IN_CALL -> true
            mode == AudioManager.MODE_IN_COMMUNICATION -> true
            mode == AudioManager.MODE_RINGTONE -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                mode == AudioManager.MODE_CALL_SCREENING -> true
            else -> false
        }

        val playbackBusy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeConfigs = configs ?: runCatching { am?.activePlaybackConfigurations }.getOrNull().orEmpty()
            activeConfigs.any { cfg ->
                when (cfg.audioAttributes.usage) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION -> true
                    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> true
                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> true
                    else -> false
                }
            }
        } else {
            false
        }

        isCallOrRingtoneActive = if (modeBusy) true else playbackBusy
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Synka Global Audio EQ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Synka System-Wide Audio EQ active across apps"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateOngoingNotificationIfChanged() {
        if (!isRunning) return
        val current = statusSummary
        if (current == lastNotifiedSummary) return
        lastNotifiedSummary = current
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
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SvaraGlobalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Turn Off",
            stopIntent
        ).build()

        return builder
            .setContentTitle("Synka System Audio EQ")
            .setContentText(statusSummary.ifBlank { "Active across device media" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(launchIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private data class Coeffs(
        val b0: Double = 1.0,
        val b1: Double = 0.0,
        val b2: Double = 0.0,
        val a1: Double = 0.0,
        val a2: Double = 0.0
    )

    private fun buildBiquadStages(params: SvaraParams): List<Coeffs> {
        val sr = 48_000.0
        val nyquistMax = sr * 0.46
        val stages = ArrayList<Coeffs>(12)

        val lowShelfFreq = (75.0 + params.bassDepthX * 35.0).coerceIn(35.0, 140.0)
        val lowShelfGain = (params.su + params.bassDepthY * 5.0f).toDouble().coerceIn(-18.0, 18.0)
        stages += designLowShelf(lowShelfFreq, lowShelfGain, sr)

        val nightSpeechDb = (params.nightDialogue / 100f) * 4.0f
        val defaultFreqs = SvaraParams.DEFAULT_FREQUENCIES_HZ
        for (i in 0 until 10) {
            val band = params.bands.getOrElse(i) { EqBand(freqHz = defaultFreqs[i]) }.clamped()
            val contourOffset = when (i) {
                2 -> params.mi * 0.55f
                3 -> params.mi * 0.45f
                5 -> params.vo * 0.55f + nightSpeechDb * 0.6f
                6 -> params.vo * 0.55f + nightSpeechDb * 0.8f
                7 -> params.tr * 0.55f + nightSpeechDb * 0.3f
                8 -> params.tr * 0.55f
                else -> 0f
            }
            val totalDb = (band.gainDb + contourOffset).toDouble().coerceIn(-20.0, 20.0)
            stages += designPeakingEq(
                band.freqHz.toDouble().coerceIn(20.0, nyquistMax),
                band.q.toDouble().coerceIn(0.1, 20.0),
                totalDb,
                sr
            )
        }

        val highShelfGain = (params.ai + params.airWidthY * 5.0f).toDouble().coerceIn(-18.0, 18.0)
        stages += designHighShelf(10_500.0, highShelfGain, sr)
        return stages
    }

    private fun evaluateComposite16BandGains(stages: List<Coeffs>): FloatArray {
        return FloatArray(BAND_EVAL_FREQS_HZ.size) { idx ->
            evaluateGainAtHz(stages, BAND_EVAL_FREQS_HZ[idx].toDouble())
        }
    }

    private fun evaluateGainAtHz(stages: List<Coeffs>, freqHz: Double): Float {
        val sr = 48_000.0
        val w = 2.0 * PI * freqHz / sr
        val c1 = cos(w)
        val s1 = -sin(w)
        val c2 = cos(2.0 * w)
        val s2 = -sin(2.0 * w)

        var dbSum = 0.0
        for (c in stages) {
            if (c.b0 == 1.0 && c.b1 == 0.0 && c.b2 == 0.0 && c.a1 == 0.0 && c.a2 == 0.0) {
                continue
            }
            val numRe = c.b0 + c.b1 * c1 + c.b2 * c2
            val numIm = c.b1 * s1 + c.b2 * s2
            val denRe = 1.0 + c.a1 * c1 + c.a2 * c2
            val denIm = c.a1 * s1 + c.a2 * s2
            val mag = hypot(numRe, numIm) / hypot(denRe, denIm).coerceAtLeast(1e-12)
            dbSum += 20.0 * log10(mag.coerceAtLeast(1e-6))
        }
        return dbSum.toFloat().coerceIn(-15f, 15f)
    }

    private fun designPeakingEq(freqHz: Double, q: Double, gainDb: Double, sampleRate: Double): Coeffs {
        if (abs(gainDb) < 0.01) return Coeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        return normalize(1.0 + alpha * a, -2.0 * cosW0, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cosW0, 1.0 - alpha / a)
    }

    private fun designLowShelf(freqHz: Double, gainDb: Double, sampleRate: Double): Coeffs {
        if (abs(gainDb) < 0.01) return Coeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val t = 2.0 * sqrt(a) * alpha
        return normalize(
            a * ((a + 1.0) - (a - 1.0) * cosW0 + t),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
            a * ((a + 1.0) - (a - 1.0) * cosW0 - t),
            (a + 1.0) + (a - 1.0) * cosW0 + t,
            -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
            (a + 1.0) + (a - 1.0) * cosW0 - t
        )
    }

    private fun designHighShelf(freqHz: Double, gainDb: Double, sampleRate: Double): Coeffs {
        if (abs(gainDb) < 0.01) return Coeffs()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt(2.0)
        val t = 2.0 * sqrt(a) * alpha
        return normalize(
            a * ((a + 1.0) + (a - 1.0) * cosW0 + t),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
            a * ((a + 1.0) + (a - 1.0) * cosW0 - t),
            (a + 1.0) - (a - 1.0) * cosW0 + t,
            2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
            (a + 1.0) - (a - 1.0) * cosW0 - t
        )
    }

    private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): Coeffs {
        if (abs(a0) < 1e-12) return Coeffs()
        if (!a0.isFinite()) return Coeffs()
        val inv = 1.0 / a0
        return Coeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv)
    }
}
