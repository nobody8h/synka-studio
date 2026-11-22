package com.synka.studio.media

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.Locale

@OptIn(UnstableApi::class)
class SynkaPlaybackService : MediaSessionService() {

    companion object {
        @Volatile
        private var serviceRef: WeakReference<SynkaPlaybackService>? = null

        private val _activePlayer = MutableStateFlow<ExoPlayer?>(null)
        val activePlayer: StateFlow<ExoPlayer?> = _activePlayer.asStateFlow()

        private val _telemetry = MutableStateFlow(AudioTelemetry())
        val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

        private val _outputMode = MutableStateFlow(AudioOutputMode.STUDIO_FLOAT_LOSSLESS)
        val outputMode: StateFlow<AudioOutputMode> = _outputMode.asStateFlow()

        private val _videoAspectRatio = MutableStateFlow(16f / 9f)
        val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

        private val _hasVideoTrack = MutableStateFlow(false)
        val hasVideoTrack: StateFlow<Boolean> = _hasVideoTrack.asStateFlow()

        fun setRequestedOutputMode(mode: AudioOutputMode) {
            if (_outputMode.value == mode) return
            _outputMode.value = mode
            serviceRef?.get()?.applyRoutingForCurrentTrack(allowSinkRebuild = true)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var currentFloatOutputEnabled: Boolean = true
    private var isRebuildingPlayer: Boolean = false
    private lateinit var audioRouter: LosslessAudioRouter

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _hasVideoTrack.value = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.length > 0
            }
            applyRoutingForCurrentTrack(allowSinkRebuild = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val tracks = exoPlayer?.currentTracks
            _hasVideoTrack.value = tracks?.groups?.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.length > 0
            } ?: false
            applyRoutingForCurrentTrack(allowSinkRebuild = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> applyRoutingForCurrentTrack(allowSinkRebuild = false)
                Player.STATE_IDLE -> {
                    _hasVideoTrack.value = false
                    audioRouter.clearBitPerfectRoute()
                }
                Player.STATE_ENDED -> audioRouter.clearBitPerfectRoute()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            refreshTelemetryOnly()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _hasVideoTrack.value = true
                _videoAspectRatio.value =
                    (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        audioRouter = LosslessAudioRouter(this) {
            applyRoutingForCurrentTrack(allowSinkRebuild = true)
        }
        audioRouter.startMonitoring()

        val initialPlayer = buildExoPlayer(enableFloatOutput = true)
        exoPlayer = initialPlayer
        _activePlayer.value = initialPlayer
        mediaSession = MediaSession.Builder(this, initialPlayer).build()
        refreshTelemetryOnly()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        serviceRef = null
        audioRouter.stopMonitoring()
        _activePlayer.value = null
        _hasVideoTrack.value = false
        exoPlayer?.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun buildExoPlayer(enableFloatOutput: Boolean): ExoPlayer {
        currentFloatOutputEnabled = enableFloatOutput

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(enableFloatOutput)

        val media3AudioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(media3AudioAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun extractAudioFormat(player: ExoPlayer?): Format? {
        if (player == null) return null
        player.audioFormat?.let { return it }
        var fallback: Format? = null
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                if (group.isTrackSelected(i)) return fmt
                if (fallback == null) fallback = fmt
            }
        }
        return fallback
    }

    private fun applyRoutingForCurrentTrack(allowSinkRebuild: Boolean) {
        val player = exoPlayer ?: return
        val fmt = extractAudioFormat(player)
        val sampleRate = fmt?.sampleRate?.takeIf { it != Format.NO_VALUE } ?: 0
        val channels = fmt?.channelCount?.takeIf { it != Format.NO_VALUE } ?: 2
        val is16Bit = fmt?.pcmEncoding == C.ENCODING_PCM_16BIT ||
            fmt?.pcmEncoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN

        val decision = audioRouter.configureRoute(
            requestedMode = _outputMode.value,
            trackSampleRateHz = sampleRate,
            trackChannelCount = channels,
            trackIs16BitSource = is16Bit
        )

        if (allowSinkRebuild && !isRebuildingPlayer &&
            decision.shouldUseFloatOutput != currentFloatOutputEnabled
        ) {
            isRebuildingPlayer = true
            val targetFloat = decision.shouldUseFloatOutput
            mainHandler.post {
                try {
                    if (serviceRef?.get() != null) {
                        rebuildPlayerPreservingQueue(targetFloat)
                    }
                } finally {
                    isRebuildingPlayer = false
                }
            }
        }

        publishTelemetry(exoPlayer, decision)
    }

    private fun refreshTelemetryOnly() {
        val player = exoPlayer ?: return
        val fmt = extractAudioFormat(player)
        val sampleRate = fmt?.sampleRate?.takeIf { it != Format.NO_VALUE } ?: 0
        val channels = fmt?.channelCount?.takeIf { it != Format.NO_VALUE } ?: 2
        val is16Bit = fmt?.pcmEncoding == C.ENCODING_PCM_16BIT ||
            fmt?.pcmEncoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN
        val decision = audioRouter.configureRoute(
            requestedMode = _outputMode.value,
            trackSampleRateHz = sampleRate,
            trackChannelCount = channels,
            trackIs16BitSource = is16Bit
        )
        publishTelemetry(player, decision)
    }

    private fun rebuildPlayerPreservingQueue(newFloatOutput: Boolean) {
        val oldPlayer = exoPlayer ?: return
        val count = oldPlayer.mediaItemCount
        val items = List(count) { idx -> oldPlayer.getMediaItemAt(idx) }
        val currentIdx = oldPlayer.currentMediaItemIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        val currentPos = oldPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = oldPlayer.playWhenReady

        oldPlayer.removeListener(playerListener)
        val newPlayer = buildExoPlayer(newFloatOutput)
        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(items, currentIdx, currentPos)
            newPlayer.prepare()
            newPlayer.playWhenReady = wasPlaying
        }

        mediaSession?.setPlayer(newPlayer)
        exoPlayer = newPlayer
        _activePlayer.value = newPlayer
        oldPlayer.release()
    }

    private fun publishTelemetry(player: ExoPlayer?, decision: UsbRouteDecision) {
        val fmt = extractAudioFormat(player)
        val mime = fmt?.sampleMimeType.orEmpty()
        val codec = when {
            mime.contains("flac", ignoreCase = true) -> "FLAC"
            mime.contains("alac", ignoreCase = true) -> "ALAC"
            mime.contains("raw", ignoreCase = true) || mime.contains("wav", ignoreCase = true) -> "PCM/WAV"
            mime.contains("opus", ignoreCase = true) -> "OPUS"
            mime.contains("vorbis", ignoreCase = true) -> "VORBIS"
            mime.contains("mpeg", ignoreCase = true) -> "MP3"
            mime.contains("mp4a", ignoreCase = true) -> "AAC"
            mime.isNotBlank() -> mime.substringAfter('/').uppercase(Locale.US)
            else -> "Idle"
        }

        val bitDepth = when (fmt?.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> "8-bit"
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit Int"
            C.ENCODING_PCM_FLOAT -> "32-bit Float"
            else -> if (codec == "FLAC" || codec == "ALAC" || codec == "PCM/WAV") "Lossless" else "--"
        }

        val isLossless = codec == "FLAC" || codec == "ALAC" || codec == "PCM/WAV" ||
            bitDepth == "24-bit" || bitDepth == "32-bit Int" || bitDepth == "32-bit Float"

        val sampleRate = fmt?.sampleRate?.takeIf { it != Format.NO_VALUE } ?: 0
        val channels = fmt?.channelCount?.takeIf { it != Format.NO_VALUE } ?: 0
        val bitrateKbps = fmt?.bitrate?.takeIf { it != Format.NO_VALUE && it > 0 }?.div(1000) ?: 0

        _telemetry.value = AudioTelemetry(
            codecLabel = codec,
            sampleRateHz = sampleRate,
            bitDepthLabel = bitDepth,
            channelCount = channels,
            bitrateKbps = bitrateKbps,
            isLosslessSource = isLossless,
            isBitPerfectActive = decision.bitPerfectActivated,
            outputRouteLabel = decision.routeLabel,
            usbDacName = decision.usbDacName,
            audioSessionId = player?.audioSessionId ?: 0,
            statusNote = decision.statusNote
        )
    }
}
