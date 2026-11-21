package com.synka.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.delay
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import kotlin.system.exitProcess

private enum class MediaMode {
    NONE,
    VIDEO,
    PHOTO,
    AUDIO
}

private data class QuickPresetChip(
    val chipLabel: String,
    val preset: SynkaPreset
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val CRASH_FILE_NAME = "last_crash.txt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCatcher()
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, CRASH_FILE_NAME)
        val existingCrashLog = if (crashFile.exists()) {
            crashFile.readText().takeIf { it.isNotBlank() }
        } else {
            null
        }

        val synkaDarkColors = darkColorScheme(
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            primary = Color(0xFFD49A45),
            onPrimary = Color(0xFF121212),
            error = Color(0xFFCF6679),
            onError = Color(0xFF000000),
            onBackground = Color(0xFFE3E3E3),
            onSurface = Color(0xFFE3E3E3)
        )

        setContent {
            MaterialTheme(colorScheme = synkaDarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (existingCrashLog != null) {
                        CrashCatcherScreen(
                            stackTrace = existingCrashLog,
                            onClearAndRelaunch = {
                                if (crashFile.exists()) {
                                    crashFile.delete()
                                }
                                relaunchActivity()
                            }
                        )
                    } else {
                        SynkaSpikeScreen()
                    }
                }
            }
        }
    }

    private fun installCrashCatcher() {
        val appContext = applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("Thread: ${thread.name} (id=${thread.id})")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} | API ${Build.VERSION.SDK_INT}")
                pw.println("----------------------------------------")
                throwable.printStackTrace(pw)
                pw.flush()

                val file = File(appContext.filesDir, CRASH_FILE_NAME)
                file.writeText(sw.toString())

                val restartIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                appContext.startActivity(restartIntent)
            } catch (_: Exception) {
                previousHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun relaunchActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SynkaSpikeScreen() {
    val context = LocalContext.current

    val quickPresets = remember {
        listOf(
            QuickPresetChip("Kora", SynkaBuiltInPresets.KORA),
            QuickPresetChip("Cinema Grade", SynkaBuiltInPresets.CINEMA_TEAL_GOLD),
            QuickPresetChip("Red Iso", SynkaBuiltInPresets.RED_ISO_CRIMSON_LOCK),
            QuickPresetChip("B&W", SynkaBuiltInPresets.BW_SILVER_GELATIN),
            QuickPresetChip("Warm", SynkaBuiltInPresets.WARM_GOLDEN_HOUR)
        )
    }

    val svaraAudio = remember { SvaraAudio() }
    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(svaraAudio))
                    .build()
            }
        }
        ExoPlayer.Builder(context, renderersFactory).build()
    }

    var mediaMode by remember { mutableStateOf(MediaMode.NONE) }
    var mediaLabel by remember { mutableStateOf("No media loaded") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }

    var selectedPreset by remember { mutableStateOf(quickPresets.first().preset) }
    var subzeroDimmer by remember { mutableFloatStateOf(selectedPreset.arka.subzeroDimmer) }
    var bassDb by remember { mutableFloatStateOf(0.0f) }
    var bypass by remember { mutableStateOf(false) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var imageViewRef by remember { mutableStateOf<ImageView?>(null) }

    val activeArkaParams = remember(selectedPreset, subzeroDimmer) {
        selectedPreset.arka.copy(subzeroDimmer = subzeroDimmer).normalized()
    }

    fun persistUri(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            photoUri = null
            mediaMode = MediaMode.VIDEO
            mediaLabel = uri.lastPathSegment ?: uri.toString()
            textureViewRef?.let { tv ->
                tv.visibility = android.view.View.VISIBLE
                imageViewRef?.visibility = android.view.View.GONE
                exoPlayer.setVideoTextureView(tv)
            }
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            exoPlayer.pause()
            mediaMode = MediaMode.PHOTO
            photoUri = uri
            mediaLabel = uri.lastPathSegment ?: uri.toString()
            textureViewRef?.let { tv ->
                exoPlayer.clearVideoTextureView(tv)
                tv.visibility = android.view.View.GONE
            }
            imageViewRef?.let { iv ->
                iv.visibility = android.view.View.VISIBLE
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        iv.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            photoUri = null
            mediaMode = MediaMode.AUDIO
            mediaLabel = uri.lastPathSegment ?: uri.toString()
            textureViewRef?.let { tv ->
                exoPlayer.clearVideoTextureView(tv)
                tv.visibility = android.view.View.GONE
            }
            imageViewRef?.visibility = android.view.View.GONE
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer.duration
                durationMs = if (dur > 0L) dur else 0L
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            textureViewRef?.let { exoPlayer.clearVideoTextureView(it) }
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isScrubbing) {
        while (true) {
            if (!isScrubbing) {
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                currentPositionMs = if (pos > 0L) pos else 0L
                durationMs = if (dur > 0L) dur else 0L
            }
            delay(200L)
        }
    }

    // Re-create RenderEffect and attach to active View on every parameter, mode, or view update
    LaunchedEffect(activeArkaParams, bypass, mediaMode, photoUri, textureViewRef, imageViewRef) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activeView = when (mediaMode) {
                MediaMode.VIDEO -> textureViewRef
                MediaMode.PHOTO -> imageViewRef
                else -> null
            }
            val inactiveView = when (mediaMode) {
                MediaMode.VIDEO -> imageViewRef
                MediaMode.PHOTO -> textureViewRef
                else -> null
            }

            Handler(Looper.getMainLooper()).post {
                inactiveView?.let { ArkaShader.clearFromView(it) }
                if (activeView != null) {
                    ArkaShader.applyToView(
                        targetView = activeView,
                        params = activeArkaParams,
                        bypass = bypass
                    )
                }
            }
        }
    }

    LaunchedEffect(bassDb) {
        svaraAudio.bassGainDb = bassDb
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Synka Studio • Arka GPU",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "API ${Build.VERSION.SDK_INT} (${Build.MODEL}) • Chunk 4 Gate",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9E9E9E)
                )
            }

            Button(
                onClick = {
                    throw RuntimeException("Synka Test Gate 4: Simulated uncaught exception on API ${Build.VERSION.SDK_INT}")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFCF6679),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Simulate Crash", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Media viewport container: FrameLayout with TextureView (video) + ImageView (photo)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color(0xFF0A0A0A), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        val tv = TextureView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER
                            )
                            visibility = android.view.View.GONE
                        }

                        val iv = ImageView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER
                            )
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            visibility = android.view.View.GONE
                        }

                        addView(tv)
                        addView(iv)

                        textureViewRef = tv
                        imageViewRef = iv
                    }
                },
                onRelease = { container ->
                    val tv = textureViewRef
                    if (tv != null) {
                        exoPlayer.clearVideoTextureView(tv)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ArkaShader.clearFromView(tv)
                        }
                    }
                    imageViewRef?.let { iv ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ArkaShader.clearFromView(iv)
                        }
                    }
                    container.removeAllViews()
                    textureViewRef = null
                    imageViewRef = null
                }
            )

            if (mediaMode == MediaMode.NONE || mediaMode == MediaMode.AUDIO) {
                Text(
                    text = if (mediaMode == MediaMode.AUDIO) "Audio Active: $mediaLabel" else "Pick Video, Photo, or Audio",
                    color = Color(0xFFBDBDBD),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
        }

        Text(
            text = mediaLabel,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD49A45),
            maxLines = 1
        )

        // Pickers row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { videoPicker.launch(arrayOf("video/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Video", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { photoPicker.launch(arrayOf("image/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Photo", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Audio", fontSize = 13.sp)
            }
        }

        // Play / Pause + SeekBar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isPlaying) "Pause" else "Play")
            }

            val safeDuration = durationMs.coerceAtLeast(1L).toFloat()
            val sliderPos = currentPositionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()

            Slider(
                value = sliderPos,
                onValueChange = { newVal ->
                    isScrubbing = true
                    currentPositionMs = newVal.toLong()
                },
                onValueChangeFinished = {
                    exoPlayer.seekTo(currentPositionMs)
                    isScrubbing = false
                },
                valueRange = 0f..safeDuration,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = formatMillis(currentPositionMs),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFB0B0B0)
            )
        }

        // Quick-tap Arka Preset Test Bar
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val soloLabels = ColorBand.entries
                .filterIndexed { idx, _ -> activeArkaParams.bands[idx].solo }
                .joinToString(",") { it.shortLabel }
            val soloSuffix = if (soloLabels.isNotEmpty()) " • SOLO: $soloLabels" else ""

            Text(
                text = "Arka Grade: ${selectedPreset.name}$soloSuffix",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE3E3E3)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPresets.forEach { item ->
                    val isSelected = selectedPreset.id == item.preset.id
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedPreset = item.preset
                            subzeroDimmer = item.preset.arka.subzeroDimmer
                        },
                        label = {
                            Text(
                                text = item.chipLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFD49A45),
                            selectedLabelColor = Color(0xFF121212),
                            containerColor = Color(0xFF1E1E1E),
                            labelColor = Color(0xFFE3E3E3)
                        )
                    )
                }
            }
        }

        // Subzero Dimmer Test Slider (0 .. 100%)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = String.format(Locale.US, "Subzero Dimmer: %.0f%%", subzeroDimmer),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE3E3E3)
            )
            Slider(
                value = subzeroDimmer,
                onValueChange = { subzeroDimmer = it },
                valueRange = 0f..100f
            )
        }

        // Bass Low-Shelf Slider (-12.0 .. +12.0 dB at 100 Hz)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = String.format(Locale.US, "Bass (100 Hz Shelf): %+.1f dB", bassDb),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE3E3E3)
            )
            Slider(
                value = bassDb,
                onValueChange = {
                    bassDb = it
                    svaraAudio.bassGainDb = it
                },
                valueRange = -12f..12f
            )
        }

        // Bypass Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bypass Shader",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE3E3E3)
            )
            Switch(
                checked = bypass,
                onCheckedChange = { bypass = it }
            )
        }
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
private fun CrashCatcherScreen(
    stackTrace: String,
    onClearAndRelaunch: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Synka Crash Catcher",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFCF6679)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Uncaught exception captured and persisted to disk.",
            fontSize = 13.sp,
            color = Color(0xFFB0B0B0)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = stackTrace,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFFE0E0E0)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Synka Crash Log", stackTrace)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Copy Crash Log")
            }

            OutlinedButton(
                onClick = onClearAndRelaunch,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Clear & Relaunch")
            }
        }
    }
}
