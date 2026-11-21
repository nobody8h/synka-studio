package com.synka.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SvaraGlobalService : Service() {

    companion object {
        private const val CHANNEL_ID = "synka_global_eq_channel"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.synka.studio.action.START_GLOBAL_EQ"
        private const val ACTION_STOP = "com.synka.studio.action.STOP_GLOBAL_EQ"

        private const val PREFS_NAME = "synka_system_bridge"
        private const val KEY_SVARA_JSON = "sys_svara_json"
        private const val KEY_MASTER = "sys_master_enabled"

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

        @Volatile
        private var instance: SvaraGlobalService? = null

        @Volatile
        private var latestParams: SvaraParams = SvaraParams()

        @Volatile
        private var latestMasterEnabled: Boolean = true

        @Volatile
        private var latestLocalPlayerPlaying: Boolean = false

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                statusSummary = "Requires Android 9+ (API 28+)"
                return
            }
            val intent = Intent(context.applicationContext, SvaraGlobalService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, SvaraGlobalService::class.java)
            )
            isRunning = false
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

            instance?.applyCurrentSettings()
        }
    }

    private var audioManager: AudioManager? = null
    private var session0Dp: DynamicsProcessing? = null
    private var session0Failed: Boolean = false
    private val perSessionEffects = LinkedHashMap<Int, DynamicsProcessing>()

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

            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (session0Dp == null) {
                        attachPerSessionEffect(sessionId)
                        applyCurrentSettings()
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    releasePerSessionEffect(sessionId)
                    updateStatusText()
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
        createNotificationChannelIfNeeded()
        registerCallAndPlaybackMonitors()
        registerSessionBroadcasts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            isRunning = false
            statusSummary = "Requires Android 9+ (API 28)"
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        ensureSession0Attached()
        refreshCallState()
        applyCurrentSettings()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterMonitors()
        runCatching { unregisterReceiver(sessionBroadcastReceiver) }
        releaseAllEffects()
        if (instance === this) {
            instance = null
        }
        isRunning = false
        statusSummary = "Off"
        super.onDestroy()
    }

    private fun loadPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        latestMasterEnabled = prefs.getBoolean(KEY_MASTER, true)
        val raw = prefs.getString(KEY_SVARA_JSON, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                latestParams = SvaraParams.fromJson(JSONObject(raw))
            }
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
    private fun ensureSession0Attached() {
        if (session0Dp != null) return
        if (session0Failed) return
        val attempt = runCatching {
            val cfg = createDpConfig()
            DynamicsProcessing(0, 0, cfg)
        }
        attempt.onSuccess { dp ->
            session0Dp = dp
        }.onFailure {
            session0Failed = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun attachPerSessionEffect(sessionId: Int) {
        if (perSessionEffects.containsKey(sessionId)) return
        runCatching {
            val dp = DynamicsProcessing(0, sessionId, createDpConfig())
            perSessionEffects[sessionId] = dp
        }
    }

    private fun releasePerSessionEffect(sessionId: Int) {
        perSessionEffects.remove(sessionId)?.let { dp ->
            runCatching { dp.setEnabled(false) }
            runCatching { dp.release() }
        }
    }

    private fun releaseAllEffects() {
        session0Dp?.let { dp ->
            runCatching { dp.setEnabled(false) }
            runCatching { dp.release() }
        }
        session0Dp = null
        perSessionEffects.values.forEach { dp ->
            runCatching { dp.setEnabled(false) }
            runCatching { dp.release() }
        }
        perSessionEffects.clear()
    }

    private fun applyCurrentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val shouldEnable = latestMasterEnabled && !isCallOrRingtoneActive && !latestLocalPlayerPlaying
        val bandGains = evaluateComposite16BandGains(latestParams)
        val preAmpDb = latestParams.preAmpDb.coerceIn(-12f, 12f)
        val limiter = buildLimiter(latestParams)

        session0Dp?.let { dp ->
            pushSettingsToDp(dp, bandGains, preAmpDb, limiter, shouldEnable)
        }

        if (session0Dp == null) {
            perSessionEffects.values.forEach { dp ->
                pushSettingsToDp(dp, bandGains, preAmpDb, limiter, shouldEnable)
            }
        }

        updateStatusText()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun pushSettingsToDp(
        dp: DynamicsProcessing,
        bandGains: FloatArray,
        preAmpDb: Float,
        limiter: DynamicsProcessing.Limiter,
        shouldEnable: Boolean
    ) {
        runCatching {
            dp.setInputGainAllChannelsTo(preAmpDb)
            for (i in BAND_CUTOFFS_HZ.indices) {
                dp.setPreEqBandAllChannelsTo(
                    i,
                    DynamicsProcessing.EqBand(true, BAND_CUTOFFS_HZ[i], bandGains[i])
                )
            }
            dp.setLimiterAllChannelsTo(limiter)
            dp.setEnabled(shouldEnable)
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
        statusSummary = when {
            !isRunning -> "Off"
            isCallOrRingtoneActive -> "Paused (Call / Ringtone Active)"
            latestLocalPlayerPlaying -> "Yielded to Local Svara DSP"
            !latestMasterEnabled -> "Bypassed (Master OFF)"
            session0Dp != null -> "Active on Global Mix (Session 0)"
            perSessionEffects.isNotEmpty() -> "Active on ${perSessionEffects.size} App Session(s)"
            session0Failed -> "Session 0 blocked by HAL — waiting for player broadcasts"
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
                refreshCallState()
                applyCurrentSettings()
            }
            modeListener = listener
            runCatching { am.addOnModeChangedListener(mainExecutor, listener) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                    refreshCallState(configs)
                    applyCurrentSettings()
                }
            }
            playbackCallback = cb
            runCatching { am.registerAudioPlaybackCallback(cb, null) }
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
            .setContentText("Active across device media (calls excluded)")
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

    private fun evaluateComposite16BandGains(params: SvaraParams): FloatArray {
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

        return FloatArray(BAND_EVAL_FREQS_HZ.size) { idx ->
            val f = BAND_EVAL_FREQS_HZ[idx].toDouble()
            val w = 2.0 * PI * f / sr
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
            dbSum.toFloat().coerceIn(-15f, 15f)
        }
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
