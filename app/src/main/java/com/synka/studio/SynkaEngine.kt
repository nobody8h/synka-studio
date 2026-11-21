package com.synka.studio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class SynkaEngine(context: Context) {

    companion object {
        private const val FILE_USER_PRESETS = "synka_user_presets.json"
        private const val FILE_FAVORITES = "synka_favorites.json"
        private const val FILE_PREFS = "synka_prefs.json"
        private const val FILE_BINDINGS = "synka_file_bindings.json"

        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_IMAGE_EDGE_PX = 2048
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SynkaEngine-IO").apply { isDaemon = true }
    }
    private val imageLoadGeneration = AtomicLong(0L)

    @Volatile
    private var isReleased: Boolean = false

    val svaraAudio: SvaraAudio = SvaraAudio()
    val arkaShader: ArkaShader = ArkaShader
    val exoPlayer: ExoPlayer

    private val _isMasterEnabled = mutableStateOf(true)
    private val _isLinkedBoth = mutableStateOf(true)
    private val _activeArka = mutableStateOf(ArkaParams())
    private val _activeSvara = mutableStateOf(SvaraParams())
    private val _themeMode = mutableStateOf(SynkaThemeMode.STUDIO_REFERENCE)

    var isMasterEnabled: Boolean
        get() = _isMasterEnabled.value
        @JvmName("assignMasterEnabled")
        set(value) = setMasterEnabled(value)

    var isLinkedBoth: Boolean
        get() = _isLinkedBoth.value
        @JvmName("assignLinkedBoth")
        set(value) = setLinkedBoth(value)

    var activeArka: ArkaParams
        get() = _activeArka.value
        set(value) = updateArka(value)

    var activeSvara: SvaraParams
        get() = _activeSvara.value
        set(value) = updateSvara(value)

    var themeMode: SynkaThemeMode
        get() = _themeMode.value
        @JvmName("assignThemeMode")
        set(value) = setThemeMode(value)

    var activePresetId: String? by mutableStateOf(SynkaBuiltInPresets.KORA.id)
        private set

    var userPresets: List<SynkaPreset> by mutableStateOf(emptyList())
        private set

    var favoriteIds: Set<String> by mutableStateOf(defaultFavoriteIds())
        private set

    var visualUri: Uri? by mutableStateOf(null)
        private set

    var visualIsVideo: Boolean by mutableStateOf(false)
        private set

    var audioUri: Uri? by mutableStateOf(null)
        private set

    var audioTitle: String by mutableStateOf("")
        private set

    var isAudioPlaying: Boolean by mutableStateOf(false)
        private set

    var audioPositionMs: Long by mutableStateOf(0L)
        private set

    var audioDurationMs: Long by mutableStateOf(0L)
        private set

    var perFileBindings: Map<String, Pair<String?, String?>> by mutableStateOf(emptyMap())
        private set

    private var boundTextureViewRef: WeakReference<TextureView>? = null
    private var boundImageViewRef: WeakReference<ImageView>? = null
    private var loadedPlayerUri: Uri? = null
    private var playerCurrentlyVideo: Boolean = false
    private var announcedAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private val positionPoller = object : Runnable {
        override fun run() {
            if (isReleased) return
            syncPlaybackTelemetry()
            if (exoPlayer.isPlaying) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isReleased) return
            isAudioPlaying = isPlaying
            syncPlaybackTelemetry()
            if (isPlaying) {
                announceAudioEffectSessionIfNeeded()
            }
            pushSystemBridgeUpdates()
            mainHandler.removeCallbacks(positionPoller)
            if (isPlaying) {
                mainHandler.postDelayed(positionPoller, POLL_INTERVAL_MS)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isReleased) return
            syncPlaybackTelemetry()
            if (playbackState == Player.STATE_ENDED) {
                isAudioPlaying = false
                pushSystemBridgeUpdates()
                mainHandler.removeCallbacks(positionPoller)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isReleased) return
            syncPlaybackTelemetry()
        }
    }

    init {
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(svaraAudio))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableAudioFloatOutput(false)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
            }

        loadPersistedState()
        pushSvaraToDsp()
        pushSystemBridgeUpdates()
    }

    fun setMasterEnabled(enabled: Boolean) {
        if (_isMasterEnabled.value == enabled) return
        _isMasterEnabled.value = enabled
        pushSvaraToDsp()
        applyVisualShader()
        pushSystemBridgeUpdates()
    }

    fun toggleMasterEnabled() {
        setMasterEnabled(!_isMasterEnabled.value)
    }

    fun setLinkedBoth(linked: Boolean) {
        if (_isLinkedBoth.value == linked) return
        _isLinkedBoth.value = linked
        persistPrefsAsync()
    }

    fun setThemeMode(mode: SynkaThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        persistPrefsAsync()
    }

    fun updateArka(params: ArkaParams, clearPresetSelection: Boolean = true) {
        val normalized = params.normalized()
        _activeArka.value = normalized
        if (clearPresetSelection) {
            activePresetId = null
        }
        applyVisualShader()
        SynkaOverlayService.updateFromEngine(appContext, normalized, _isMasterEnabled.value)
    }

    fun updateSvara(params: SvaraParams, clearPresetSelection: Boolean = true) {
        val normalized = params.normalized()
        _activeSvara.value = normalized
        if (clearPresetSelection) {
            activePresetId = null
        }
        pushSvaraToDsp()
        SvaraGlobalService.updateFromEngine(
            appContext,
            normalized,
            _isMasterEnabled.value,
            isAudioPlaying
        )
    }

    fun resetToKora() {
        applyPreset(SynkaBuiltInPresets.KORA)
    }

    fun bindVisualTarget(textureView: TextureView?, imageView: ImageView?) {
        runOnMain {
            if (isReleased) return@runOnMain

            val previousTexture = boundTextureViewRef?.get()
            if (previousTexture != null && previousTexture !== textureView) {
                exoPlayer.clearVideoTextureView(previousTexture)
                clearShaderSafely(previousTexture)
            }

            val previousImage = boundImageViewRef?.get()
            if (previousImage != null && previousImage !== imageView) {
                clearShaderSafely(previousImage)
            }

            boundTextureViewRef = textureView?.let { WeakReference(it) }
            boundImageViewRef = imageView?.let { WeakReference(it) }

            if (visualIsVideo) {
                if (textureView != null && playerCurrentlyVideo) {
                    exoPlayer.setVideoTextureView(textureView)
                }
                if (imageView != null) {
                    clearShaderSafely(imageView)
                }
            } else {
                if (textureView != null) {
                    exoPlayer.clearVideoTextureView(textureView)
                    clearShaderSafely(textureView)
                }
                if (imageView != null && visualUri != null) {
                    loadImageIntoView(visualUri, imageView)
                }
            }

            applyVisualShader()
        }
    }

    fun unbindVisualTarget(textureView: TextureView?, imageView: ImageView?) {
        runOnMain {
            if (isReleased) return@runOnMain

            if (textureView != null && boundTextureViewRef?.get() === textureView) {
                exoPlayer.clearVideoTextureView(textureView)
                clearShaderSafely(textureView)
                boundTextureViewRef = null
            }
            if (imageView != null && boundImageViewRef?.get() === imageView) {
                clearShaderSafely(imageView)
                boundImageViewRef = null
            }
        }
    }

    fun setVisualMedia(uri: Uri?, isVideo: Boolean) {
        runOnMain {
            if (isReleased) return@runOnMain

            visualUri = uri
            visualIsVideo = isVideo

            val textureView = boundTextureViewRef?.get()
            val imageView = boundImageViewRef?.get()

            if (uri == null) {
                imageLoadGeneration.incrementAndGet()
                imageView?.setImageDrawable(null)
                if (playerCurrentlyVideo) {
                    textureView?.let { exoPlayer.clearVideoTextureView(it) }
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    loadedPlayerUri = null
                    playerCurrentlyVideo = false
                    syncPlaybackTelemetry()
                }
                applyVisualShader()
                return@runOnMain
            }

            applyBoundPresetsForUri(uri)

            if (isVideo) {
                imageLoadGeneration.incrementAndGet()
                imageView?.let {
                    it.setImageDrawable(null)
                    clearShaderSafely(it)
                }

                playerCurrentlyVideo = true
                if (textureView != null) {
                    exoPlayer.setVideoTextureView(textureView)
                }

                if (loadedPlayerUri != uri) {
                    loadedPlayerUri = uri
                    audioTitle = resolveDisplayName(uri).ifBlank { "Video" }
                    exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                    exoPlayer.prepare()
                }
                exoPlayer.playWhenReady = true
                syncPlaybackTelemetry()
            } else {
                if (textureView != null && !playerCurrentlyVideo) {
                    clearShaderSafely(textureView)
                }
                if (imageView != null) {
                    loadImageIntoView(uri, imageView)
                }
            }

            applyVisualShader()
        }
    }

    fun setAudioMedia(uri: Uri?, title: String = "") {
        runOnMain {
            if (isReleased) return@runOnMain

            audioUri = uri
            if (uri == null) {
                audioTitle = ""
                if (!playerCurrentlyVideo) {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    loadedPlayerUri = null
                    isAudioPlaying = false
                    audioPositionMs = 0L
                    audioDurationMs = 0L
                    svaraAudio.clearWaveform()
                    pushSystemBridgeUpdates()
                }
                return@runOnMain
            }

            val resolvedTitle = title.ifBlank { resolveDisplayName(uri).ifBlank { "Audio Track" } }
            audioTitle = resolvedTitle

            applyBoundPresetsForUri(uri)

            val needsVideoDetach = if (playerCurrentlyVideo) true else visualIsVideo
            if (needsVideoDetach) {
                boundTextureViewRef?.get()?.let {
                    exoPlayer.clearVideoTextureView(it)
                    clearShaderSafely(it)
                }
                playerCurrentlyVideo = false
                if (visualIsVideo) {
                    visualIsVideo = false
                    visualUri = null
                }
                applyVisualShader()
            }

            if (loadedPlayerUri != uri) {
                loadedPlayerUri = uri
                exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
            syncPlaybackTelemetry()
        }
    }

    fun playAudio() {
        runOnMain {
            if (!isReleased && loadedPlayerUri != null) {
                exoPlayer.play()
            }
        }
    }

    fun pauseAudio() {
        runOnMain {
            if (!isReleased) {
                exoPlayer.pause()
            }
        }
    }

    fun togglePlayPause() {
        runOnMain {
            if (isReleased) return@runOnMain
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else if (loadedPlayerUri != null) {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                exoPlayer.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        runOnMain {
            if (isReleased) return@runOnMain
            if (loadedPlayerUri == null) return@runOnMain
            val maxDuration = if (audioDurationMs > 0L) audioDurationMs else Long.MAX_VALUE
            val target = positionMs.coerceIn(0L, maxDuration)
            exoPlayer.seekTo(target)
            audioPositionMs = target
        }
    }

    fun allPresets(): List<SynkaPreset> {
        val favs = favoriteIds
        val builtIns = SynkaBuiltInPresets.ALL_PRESETS.map { preset ->
            preset.copy(isFavorite = favs.contains(preset.id))
        }
        val users = userPresets.map { preset ->
            preset.copy(isFavorite = favs.contains(preset.id), isUserCreated = true)
        }
        return builtIns + users
    }

    fun findPresetById(id: String?): SynkaPreset? {
        if (id.isNullOrBlank()) return null
        return allPresets().firstOrNull { it.id == id }
    }

    fun applyPreset(preset: SynkaPreset) {
        when (preset.domain) {
            PresetDomain.DISPLAY -> {
                _activeArka.value = preset.arka.normalized()
                applyVisualShader()
            }
            PresetDomain.AUDIO -> {
                _activeSvara.value = preset.svara.normalized()
                pushSvaraToDsp()
            }
            PresetDomain.BOTH -> {
                _activeArka.value = preset.arka.normalized()
                _activeSvara.value = preset.svara.normalized()
                applyVisualShader()
                pushSvaraToDsp()
            }
        }
        activePresetId = preset.id
        pushSystemBridgeUpdates()
    }

    fun applyGenreQuick(displayGenre: DisplayGenre) {
        if (_isLinkedBoth.value) {
            val linkedAudio = SynkaBuiltInPresets.linkedAudioGenreFor(displayGenre)
            val paired = SynkaBuiltInPresets.pairedPresetFor(displayGenre, linkedAudio)
            applyPreset(paired)
        } else {
            val preset = allPresets().firstOrNull {
                it.domain == PresetDomain.DISPLAY && it.displayGenre == displayGenre
            } ?: SynkaBuiltInPresets.byDisplayGenre(displayGenre).firstOrNull()
            if (preset != null) {
                _activeArka.value = preset.arka.normalized()
                activePresetId = preset.id
                applyVisualShader()
                pushSystemBridgeUpdates()
            }
        }
    }

    fun applyGenreQuick(audioGenre: AudioGenre) {
        if (_isLinkedBoth.value) {
            val linkedDisplay = SynkaBuiltInPresets.linkedDisplayGenreFor(audioGenre)
            val paired = SynkaBuiltInPresets.pairedPresetFor(linkedDisplay, audioGenre)
            applyPreset(paired)
        } else {
            val preset = allPresets().firstOrNull {
                it.domain == PresetDomain.AUDIO && it.audioGenre == audioGenre
            } ?: SynkaBuiltInPresets.byAudioGenre(audioGenre).firstOrNull()
            if (preset != null) {
                _activeSvara.value = preset.svara.normalized()
                activePresetId = preset.id
                pushSvaraToDsp()
                pushSystemBridgeUpdates()
            }
        }
    }

    fun saveUserPreset(
        name: String,
        domain: PresetDomain = PresetDomain.BOTH,
        displayGenre: DisplayGenre? = null,
        audioGenre: AudioGenre? = null,
        arka: ArkaParams = activeArka,
        svara: SvaraParams = activeSvara,
        id: String? = null
    ): SynkaPreset {
        val cleanName = name.trim().ifBlank { "Custom Preset ${userPresets.size + 1}" }
        val resolvedId = id?.takeIf { it.isNotBlank() } ?: "user_${UUID.randomUUID()}"
        val isFav = favoriteIds.contains(resolvedId)

        val newPreset = SynkaPreset(
            id = resolvedId,
            name = cleanName,
            domain = domain,
            displayGenre = displayGenre,
            audioGenre = audioGenre,
            arka = arka.normalized(),
            svara = svara.normalized(),
            isFavorite = isFav,
            isUserCreated = true
        )

        val existingIndex = userPresets.indexOfFirst { it.id == resolvedId }
        userPresets = if (existingIndex >= 0) {
            userPresets.toMutableList().apply { set(existingIndex, newPreset) }
        } else {
            userPresets + newPreset
        }

        activePresetId = newPreset.id
        persistUserPresetsAsync()
        return newPreset
    }

    fun deleteUserPreset(id: String) {
        val updated = userPresets.filterNot { it.id == id }
        if (updated.size == userPresets.size) return

        userPresets = updated
        if (activePresetId == id) {
            activePresetId = null
        }

        if (favoriteIds.contains(id)) {
            favoriteIds = favoriteIds - id
            persistFavoritesAsync()
        }

        var bindingsChanged = false
        val cleanedBindings = buildMap<String, Pair<String?, String?>> {
            perFileBindings.forEach { (uriKey, pair) ->
                val disp = if (pair.first == id) {
                    bindingsChanged = true
                    null
                } else {
                    pair.first
                }
                val aud = if (pair.second == id) {
                    bindingsChanged = true
                    null
                } else {
                    pair.second
                }
                val keepBinding = if (disp != null) true else aud != null
                val hadBinding = if (pair.first != null) true else pair.second != null
                if (keepBinding) {
                    put(uriKey, disp to aud)
                } else if (hadBinding) {
                    bindingsChanged = true
                }
            }
        }
        if (bindingsChanged) {
            perFileBindings = cleanedBindings
            persistBindingsAsync()
        }

        persistUserPresetsAsync()
    }

    fun toggleFavorite(id: String) {
        if (id.isBlank()) return
        val nowFavorite = !favoriteIds.contains(id)
        favoriteIds = if (nowFavorite) favoriteIds + id else favoriteIds - id

        val userIndex = userPresets.indexOfFirst { it.id == id }
        if (userIndex >= 0) {
            userPresets = userPresets.toMutableList().apply {
                set(userIndex, get(userIndex).copy(isFavorite = nowFavorite))
            }
            persistUserPresetsAsync()
        }

        persistFavoritesAsync()
    }

    fun bindPresetsToFile(
        mediaUri: String,
        displayPresetId: String?,
        audioPresetId: String?
    ) {
        val key = mediaUri.trim()
        if (key.isBlank()) return

        val cleanDisplay = displayPresetId?.takeIf { it.isNotBlank() }
        val cleanAudio = audioPresetId?.takeIf { it.isNotBlank() }

        perFileBindings = perFileBindings.toMutableMap().apply {
            if (cleanDisplay == null && cleanAudio == null) {
                remove(key)
            } else {
                put(key, cleanDisplay to cleanAudio)
            }
        }
        persistBindingsAsync()
    }

    fun bindPresetsToFile(
        mediaUri: Uri,
        displayPresetId: String?,
        audioPresetId: String?
    ) {
        bindPresetsToFile(mediaUri.toString(), displayPresetId, audioPresetId)
    }

    fun applyBoundPresetsForUri(uri: Uri?) {
        val key = uri?.toString() ?: return
        val binding = perFileBindings[key] ?: return
        val (displayPresetId, audioPresetId) = binding

        if (displayPresetId != null && displayPresetId == audioPresetId) {
            findPresetById(displayPresetId)?.let { applyPreset(it) }
            return
        }

        var lastAppliedId: String? = null
        if (displayPresetId != null) {
            findPresetById(displayPresetId)?.let { preset ->
                _activeArka.value = preset.arka.normalized()
                applyVisualShader()
                lastAppliedId = preset.id
            }
        }
        if (audioPresetId != null) {
            findPresetById(audioPresetId)?.let { preset ->
                _activeSvara.value = preset.svara.normalized()
                pushSvaraToDsp()
                lastAppliedId = preset.id
            }
        }
        if (lastAppliedId != null) {
            activePresetId = lastAppliedId
            pushSystemBridgeUpdates()
        }
    }

    fun release() {
        if (isReleased) return
        isReleased = true

        imageLoadGeneration.incrementAndGet()
        mainHandler.removeCallbacks(positionPoller)

        val cleanupAction = {
            boundTextureViewRef?.get()?.let { tv ->
                exoPlayer.clearVideoTextureView(tv)
                clearShaderSafely(tv)
            }
            boundImageViewRef?.get()?.let { iv ->
                clearShaderSafely(iv)
            }
            boundTextureViewRef = null
            boundImageViewRef = null

            closeAnnouncedAudioEffectSession()
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
            svaraAudio.clearWaveform()

            SvaraGlobalService.updateFromEngine(
                appContext,
                _activeSvara.value,
                _isMasterEnabled.value,
                false
            )
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanupAction()
        } else {
            mainHandler.post(cleanupAction)
        }

        ioExecutor.shutdown()
    }

    private fun pushSvaraToDsp() {
        svaraAudio.updateParams(
            params = _activeSvara.value,
            bypassed = !_isMasterEnabled.value
        )
    }

    private fun pushSystemBridgeUpdates() {
        SynkaOverlayService.updateFromEngine(
            appContext,
            _activeArka.value,
            _isMasterEnabled.value
        )
        SvaraGlobalService.updateFromEngine(
            appContext,
            _activeSvara.value,
            _isMasterEnabled.value,
            isAudioPlaying
        )
    }

    private fun announceAudioEffectSessionIfNeeded() {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (sessionId == 0) return
        if (sessionId == announcedAudioSessionId) return

        closeAnnouncedAudioEffectSession()
        announcedAudioSessionId = sessionId
        runCatching {
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            appContext.sendBroadcast(intent)
        }
    }

    private fun closeAnnouncedAudioEffectSession() {
        val sessionId = announcedAudioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (sessionId == 0) return
        announcedAudioSessionId = C.AUDIO_SESSION_ID_UNSET
        runCatching {
            val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
            }
            appContext.sendBroadcast(intent)
        }
    }

    private fun applyVisualShader() {
        runOnMain {
            if (isReleased) return@runOnMain
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@runOnMain

            val textureView = boundTextureViewRef?.get()
            val imageView = boundImageViewRef?.get()
            val bypass = !_isMasterEnabled.value
            val params = _activeArka.value

            if (visualIsVideo) {
                textureView?.let { arkaShader.applyToView(it, params, bypass = bypass) }
                imageView?.let { arkaShader.clearFromView(it) }
            } else {
                imageView?.let { arkaShader.applyToView(it, params, bypass = bypass) }
                textureView?.let { arkaShader.clearFromView(it) }
            }
        }
    }

    private fun clearShaderSafely(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arkaShader.clearFromView(view)
        }
    }

    private fun syncPlaybackTelemetry() {
        val duration = exoPlayer.duration
        audioDurationMs = when {
            duration == C.TIME_UNSET -> 0L
            duration < 0L -> 0L
            else -> duration
        }
        audioPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        isAudioPlaying = exoPlayer.isPlaying
    }

    private fun loadImageIntoView(uri: Uri?, imageView: ImageView) {
        val generation = imageLoadGeneration.incrementAndGet()
        if (uri == null) {
            imageView.setImageDrawable(null)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            imageView.setImageURI(uri)
            return
        }

        val targetRef = WeakReference(imageView)
        if (ioExecutor.isShutdown) return

        ioExecutor.execute {
            val bitmap: Bitmap? = runCatching {
                val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > MAX_IMAGE_EDGE_PX) {
                        val scale = MAX_IMAGE_EDGE_PX.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()

            mainHandler.post {
                if (isReleased) return@post
                if (imageLoadGeneration.get() != generation) return@post
                val iv = targetRef.get() ?: return@post
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap)
                } else {
                    iv.setImageURI(uri)
                }
                applyVisualShader()
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()

        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: ""
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun defaultFavoriteIds(): Set<String> =
        SynkaBuiltInPresets.ALL_PRESETS
            .filter { it.isFavorite }
            .map { it.id }
            .toSet()

    private fun loadPersistedState() {
        runCatching {
            val file = File(appContext.filesDir, FILE_USER_PRESETS)
            if (file.exists()) {
                val parsed = SynkaPreset.listFromJsonArray(JSONArray(file.readText()))
                    .map { it.copy(isUserCreated = true) }
                userPresets = parsed
            }
        }

        runCatching {
            val file = File(appContext.filesDir, FILE_FAVORITES)
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                val set = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank()) set.add(id)
                }
                favoriteIds = set
            }
        }

        runCatching {
            val file = File(appContext.filesDir, FILE_PREFS)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val modeName = json.optString("themeMode", SynkaThemeMode.STUDIO_REFERENCE.name)
                _themeMode.value = SynkaThemeMode.entries.firstOrNull {
                    it.name.equals(modeName, ignoreCase = true)
                } ?: SynkaThemeMode.STUDIO_REFERENCE
                _isLinkedBoth.value = json.optBoolean("isLinkedBoth", true)
            }
        }

        runCatching {
            val file = File(appContext.filesDir, FILE_BINDINGS)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val map = LinkedHashMap<String, Pair<String?, String?>>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val uriKey = keys.next()
                    val obj = json.optJSONObject(uriKey) ?: continue
                    val disp = if (obj.isNull("displayPresetId")) {
                        null
                    } else {
                        obj.optString("displayPresetId").takeIf { it.isNotBlank() }
                    }
                    val aud = if (obj.isNull("audioPresetId")) {
                        null
                    } else {
                        obj.optString("audioPresetId").takeIf { it.isNotBlank() }
                    }
                    if (disp != null) {
                        map[uriKey] = disp to aud
                    } else if (aud != null) {
                        map[uriKey] = disp to aud
                    }
                }
                perFileBindings = map
            }
        }
    }

    private fun persistUserPresetsAsync() {
        val snapshot = userPresets
        val payload = SynkaPreset.listToJsonArray(snapshot).toString()
        writeJsonAtomicAsync(FILE_USER_PRESETS, payload)
    }

    private fun persistFavoritesAsync() {
        val snapshot = favoriteIds
        val arr = JSONArray().apply {
            snapshot.forEach { put(it) }
        }
        writeJsonAtomicAsync(FILE_FAVORITES, arr.toString())
    }

    private fun persistPrefsAsync() {
        val payload = JSONObject().apply {
            put("themeMode", _themeMode.value.name)
            put("isLinkedBoth", _isLinkedBoth.value)
        }.toString()
        writeJsonAtomicAsync(FILE_PREFS, payload)
    }

    private fun persistBindingsAsync() {
        val snapshot = perFileBindings
        val root = JSONObject()
        snapshot.forEach { (uriKey, pair) ->
            val entry = JSONObject().apply {
                put("displayPresetId", pair.first ?: JSONObject.NULL)
                put("audioPresetId", pair.second ?: JSONObject.NULL)
            }
            root.put(uriKey, entry)
        }
        writeJsonAtomicAsync(FILE_BINDINGS, root.toString())
    }

    private fun writeJsonAtomicAsync(fileName: String, content: String) {
        if (isReleased) return
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            runCatching {
                val target = File(appContext.filesDir, fileName)
                val tmp = File(appContext.filesDir, "$fileName.tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(target)) {
                    target.writeText(content)
                    tmp.delete()
                }
            }
        }
    }
}
