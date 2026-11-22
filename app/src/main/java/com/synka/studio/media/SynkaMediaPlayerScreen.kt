package com.synka.studio.media

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.synka.studio.display.model.DisplayEqState
import com.synka.studio.display.shader.synkaDisplayEqGpu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val ObsidianBg = Color(0xFF121212)
private val ObsidianSurface = Color(0xFF1C1C1E)
private val KansaBronze = Color(0xFFD49A45)
private val LosslessMint = Color(0xFF81C995)
private val MutedText = Color(0xFF9E9EA6)

private enum class LibraryTab(val label: String) {
    LOSSLESS("Lossless"),
    ALL_AUDIO("All Audio"),
    VIDEO("Videos")
}

@OptIn(UnstableApi::class)
@Composable
fun SynkaMediaPlayerScreen(
    displayEqState: DisplayEqState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeExoPlayer by SynkaPlaybackService.activePlayer.collectAsState()
    val telemetry by SynkaPlaybackService.telemetry.collectAsState()
    val outputMode by SynkaPlaybackService.outputMode.collectAsState()
    val hasVideoTrack by SynkaPlaybackService.hasVideoTrack.collectAsState()
    val videoAspect by SynkaPlaybackService.videoAspectRatio.collectAsState()

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var nowPlayingTitle by remember { mutableStateOf("Select a lossless track or video") }
    var nowPlayingSubtitle by remember { mutableStateOf("FLAC • WAV • 24-bit / 192 kHz • Local Video") }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(LibraryTab.LOSSLESS) }
    var library by remember { mutableStateOf(LocalMediaLibrary()) }
    var isScanning by remember { mutableStateOf(false) }
    var activeTrackIndex by remember { mutableIntStateOf(-1) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            requiredPermissions.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val refreshLibrary: () -> Unit = {
        scope.launch {
            isScanning = true
            library = LocalMediaScanner.scanLibrary(context)
            isScanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasStoragePermission = grants.values.any { it }
        if (hasStoragePermission) refreshLibrary()
    }

    // Zero-permission fallback: lets users open any local FLAC/WAV/MP4/MKV immediately
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        val uri = pickedUri ?: return@rememberLauncherForActivityResult
        val ctrl = controller ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Local Media"
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist("Picked Local File")
                    .build()
            )
            .build()
        ctrl.setMediaItem(item)
        ctrl.prepare()
        ctrl.play()
        nowPlayingTitle = name
        nowPlayingSubtitle = "Direct Local File"
    }

    // Connect MediaController to SynkaPlaybackService so lock-screen & background audio stay alive
    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SynkaPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        var attachedListener: Player.Listener? = null

        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mc ->
                    controller = mc
                    isPlaying = mc.isPlaying
                    durationMs = mc.duration.coerceAtLeast(0L)
                    positionMs = mc.currentPosition.coerceAtLeast(0L)
                    mc.mediaMetadata.title?.toString()?.let { nowPlayingTitle = it }
                    mc.mediaMetadata.artist?.toString()?.let { nowPlayingSubtitle = it }

                    val listener = object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                            mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.let {
                                nowPlayingTitle = it
                            }
                            mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let {
                                nowPlayingSubtitle = it
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            durationMs = mc.duration.coerceAtLeast(0L)
                        }
                    }
                    attachedListener = listener
                    mc.addListener(listener)
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            attachedListener?.let { controller?.removeListener(it) }
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    LaunchedEffect(hasStoragePermission) {
        if (hasStoragePermission) refreshLibrary()
    }

    LaunchedEffect(controller, isPlaying, isScrubbing) {
        while (controller != null && isPlaying) {
            if (!isScrubbing) {
                val ctrl = controller
                if (ctrl != null) {
                    positionMs = ctrl.currentPosition.coerceAtLeast(0L)
                    durationMs = ctrl.duration.coerceAtLeast(0L)
                }
            }
            delay(250L)
        }
    }

    val displayedTracks = remember(selectedTab, library) {
        when (selectedTab) {
            LibraryTab.LOSSLESS -> library.losslessAudioTracks
            LibraryTab.ALL_AUDIO -> library.audioTracks
            LibraryTab.VIDEO -> library.videoTracks
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(14.dp)
    ) {
        // 1. LIVE VIDEO STAGE (Uses SURFACE_TYPE_TEXTURE_VIEW so Tier 3 AGSL Display EQ grades video!)
        if (hasVideoTrack) {
            val videoPlayer: Player? = activeExoPlayer ?: controller
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspect.coerceIn(0.6f, 2.4f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, KansaBronze.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                PlayerSurface(
                    player = videoPlayer,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    modifier = Modifier
                        .fillMaxSize()
                        .synkaDisplayEqGpu(displayEqState)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // 2. NOW PLAYING + HONEST LOSSLESS SIGNAL TELEMETRY CARD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianSurface)
                .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlayingTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = nowPlayingSubtitle,
                        color = MutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) }
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Open Local File",
                        tint = KansaBronze
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Honest signal path badge
            val badgeColor = when {
                telemetry.isBitPerfectActive -> LosslessMint
                telemetry.isLosslessSource -> KansaBronze
                else -> MutedText
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141416))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (telemetry.usbDacName != null) Icons.Default.Usb else Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = telemetry.formattedSpecLine(),
                        color = badgeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${telemetry.outputRouteLabel} • ${telemetry.statusNote}",
                        color = MutedText,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Output mode selector (Studio 32-bit Float vs USB Bit-Perfect Direct)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AudioOutputMode.entries.forEach { mode ->
                    val selected = outputMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { SynkaPlaybackService.setRequestedOutputMode(mode) },
                        label = {
                            Text(
                                text = if (mode == AudioOutputMode.STUDIO_FLOAT_LOSSLESS) {
                                    "32-bit Float (EQ)"
                                } else {
                                    "USB Bit-Perfect"
                                },
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KansaBronze.copy(alpha = 0.22f),
                            selectedLabelColor = KansaBronze
                        )
                    )
                }
            }

            // Scrubber
            val maxDur = durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                onValueChange = {
                    isScrubbing = true
                    positionMs = it.toLong()
                },
                onValueChangeFinished = {
                    controller?.seekTo(positionMs)
                    isScrubbing = false
                },
                valueRange = 0f..maxDur,
                colors = SliderDefaults.colors(
                    thumbColor = KansaBronze,
                    activeTrackColor = KansaBronze,
                    inactiveTrackColor = Color(0xFF3A3A3C)
                )
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(formatDuration(positionMs), color = MutedText, fontSize = 11.sp)
                Text(formatDuration(durationMs), color = MutedText, fontSize = 11.sp)
            }

            // Transport buttons
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = {
                        val ctrl = controller ?: return@IconButton
                        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(KansaBronze)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = ObsidianBg
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 3. LIBRARY FILTER TABS
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KansaBronze.copy(alpha = 0.25f),
                            selectedLabelColor = KansaBronze
                        )
                    )
                }
            }
            IconButton(onClick = { if (hasStoragePermission) refreshLibrary() else permissionLauncher.launch(requiredPermissions) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan Library", tint = MutedText)
            }
        }

        Spacer(Modifier.height(6.dp))

        // 4. LOCAL LOSSLESS MUSIC & VIDEO LIST
        if (!hasStoragePermission) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = "Grant local media access to auto-scan FLAC, WAV & video files—or tap the folder icon above to open any file directly.",
                    color = MutedText,
                    fontSize = 12.5.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    colors = ButtonDefaults.buttonColors(containerColor = KansaBronze, contentColor = ObsidianBg)
                ) {
                    Text("Scan Local Lossless & Video Library", fontWeight = FontWeight.SemiBold)
                }
            }
        } else if (isScanning) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text("Scanning local media...", color = MutedText, fontSize = 13.sp)
            }
        } else if (displayedTracks.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = if (selectedTab == LibraryTab.LOSSLESS) {
                        "No local FLAC/WAV files found yet. Tap the folder icon to pick any file."
                    } else {
                        "No local files found in this category."
                    },
                    color = MutedText,
                    fontSize = 12.5.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(displayedTracks, key = { _, t -> "${t.kind}_${t.id}" }) { index, track ->
                    val isCurrent = index == activeTrackIndex && nowPlayingTitle == track.title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) KansaBronze.copy(alpha = 0.16f) else ObsidianSurface)
                            .clickable {
                                val ctrl = controller ?: return@clickable
                                val queue = displayedTracks.map { item ->
                                    MediaItem.Builder()
                                        .setUri(item.uri)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(item.title)
                                                .setArtist(item.artist)
                                                .setAlbumTitle(item.album)
                                                .build()
                                        )
                                        .build()
                                }
                                activeTrackIndex = index
                                ctrl.setMediaItems(queue, index, 0L)
                                ctrl.prepare()
                                ctrl.play()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (track.kind == MediaKind.VIDEO) Icons.Default.Movie else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (track.isLosslessContainer) LosslessMint else KansaBronze,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.artist} • ${formatDuration(track.durationMs)}",
                                color = MutedText,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = track.formatTag,
                            color = if (track.isLosslessContainer) LosslessMint else MutedText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
