package com.synka.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

private enum class SynkaTab {
    HOME,
    DISPLAY_EQ,
    AUDIO_EQ,
    LOCAL_MEDIA
}

private data class PresetSheetStateModel(
    val domain: PresetDomain,
    val displayGenre: DisplayGenre,
    val audioGenre: AudioGenre
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val CRASH_FILE_NAME = "last_crash.txt"
    }

    private var synkaEngine: SynkaEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCatcher()
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, CRASH_FILE_NAME)
        val existingCrashLog = if (crashFile.exists()) {
            crashFile.readText().takeIf { it.isNotBlank() }
        } else {
            null
        }

        val engine = if (existingCrashLog == null) {
            SynkaEngine(applicationContext).also { synkaEngine = it }
        } else {
            null
        }

        setContent {
            val themeMode = engine?.themeMode ?: SynkaThemeMode.STUDIO_REFERENCE
            SynkaTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalSynkaColors.current.background
                ) {
                    if (existingCrashLog != null || engine == null) {
                        CrashCatcherScreen(
                            stackTrace = existingCrashLog ?: "Engine initialization aborted.",
                            onClearAndRelaunch = {
                                if (crashFile.exists()) {
                                    crashFile.delete()
                                }
                                relaunchActivity()
                            }
                        )
                    } else {
                        SynkaAppShell(engine = engine)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        synkaEngine?.release()
        synkaEngine = null
        super.onDestroy()
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

// -----------------------------------------------------------------------------
// Main Shell: Content Stage + Persistent Mini-Player + Bottom Dock + PresetSheet
// -----------------------------------------------------------------------------

@Composable
private fun SynkaAppShell(engine: SynkaEngine) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    var activeTab by remember { mutableStateOf(SynkaTab.HOME) }
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isDiscoverOpen by remember { mutableStateOf(false) }
    var activePresetSheet by remember { mutableStateOf<PresetSheetStateModel?>(null) }

    // Independent Display/Audio genre highlights and last-used preset memory per genre
    var activeDisplayGenre by remember { mutableStateOf<DisplayGenre?>(null) }
    var activeAudioGenre by remember { mutableStateOf<AudioGenre?>(null) }
    val lastUsedPresetByGenre = remember { mutableStateMapOf<String, String>() }

    fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val visualMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takeReadPermission(uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video/")
            engine.setVisualMedia(uri = uri, isVideo = isVideo)
        }
    }

    val audioMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takeReadPermission(uri)
            engine.setAudioMedia(uri = uri)
        }
    }

    fun applyDisplayGenreFromDeck(genre: DisplayGenre) {
        if (engine.isLinkedBoth) {
            engine.applyGenreQuick(genre)
            activeDisplayGenre = genre
            activeAudioGenre = SynkaBuiltInPresets.linkedAudioGenreFor(genre)
        } else {
            val rememberedId = lastUsedPresetByGenre["${PresetDomain.DISPLAY.name}:${genre.name}"]
            val rememberedPreset = engine.findPresetById(rememberedId)
            if (rememberedPreset != null && rememberedPreset.domain == PresetDomain.DISPLAY) {
                engine.applyPreset(rememberedPreset)
            } else {
                engine.applyGenreQuick(genre)
            }
            activeDisplayGenre = genre
        }
        engine.activePresetId?.let { id ->
            lastUsedPresetByGenre["${PresetDomain.DISPLAY.name}:${genre.name}"] = id
        }
    }

    fun applyAudioGenreFromDeck(genre: AudioGenre) {
        if (engine.isLinkedBoth) {
            engine.applyGenreQuick(genre)
            activeAudioGenre = genre
            activeDisplayGenre = SynkaBuiltInPresets.linkedDisplayGenreFor(genre)
        } else {
            val rememberedId = lastUsedPresetByGenre["${PresetDomain.AUDIO.name}:${genre.name}"]
            val rememberedPreset = engine.findPresetById(rememberedId)
            if (rememberedPreset != null && rememberedPreset.domain == PresetDomain.AUDIO) {
                engine.applyPreset(rememberedPreset)
            } else {
                engine.applyGenreQuick(genre)
            }
            activeAudioGenre = genre
        }
        engine.activePresetId?.let { id ->
            lastUsedPresetByGenre["${PresetDomain.AUDIO.name}:${genre.name}"] = id
        }
    }

    val isAudioLoaded = engine.audioUri != null || (engine.visualUri != null && engine.visualIsVideo)
    val isPresetSheetShowingVisual = activePresetSheet != null && activePresetSheet?.domain != PresetDomain.AUDIO

    // Blur Home backdrop safely on API 33+ while ModalBottomSheet is open
    val backdropBlurRadius by animateDpAsState(
        targetValue = if (activePresetSheet != null) 10.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "presetSheetBackdropBlur"
    )
    val backdropBlurModifier = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdropBlurRadius > 0.dp
    ) {
        Modifier.blur(backdropBlurRadius)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropBlurModifier)
                .statusBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (activeTab) {
                    SynkaTab.HOME -> {
                        HomeScreen(
                            engine = engine,
                            activeDisplayGenre = activeDisplayGenre,
                            activeAudioGenre = activeAudioGenre,
                            isPreviewActive = !isPresetSheetShowingVisual,
                            onDiscoverClick = { isDiscoverOpen = true },
                            onOpenDrawer = { isDrawerOpen = true },
                            onPickVisualMedia = {
                                visualMediaPicker.launch(arrayOf("image/*", "video/*"))
                            },
                            onPickAudioMedia = {
                                audioMediaPicker.launch(arrayOf("audio/*"))
                            },
                            onDisplayGenreTap = { genre -> applyDisplayGenreFromDeck(genre) },
                            onAudioGenreTap = { genre -> applyAudioGenreFromDeck(genre) },
                            onOpenPresetSheet = { domain, genreName ->
                                val resolvedDisplay = if (domain == PresetDomain.DISPLAY) {
                                    DisplayGenre.entries.firstOrNull { it.name == genreName }
                                        ?: activeDisplayGenre
                                        ?: DisplayGenre.CINEMA_GRADE
                                } else {
                                    activeDisplayGenre ?: DisplayGenre.CINEMA_GRADE
                                }
                                val resolvedAudio = if (domain == PresetDomain.AUDIO) {
                                    AudioGenre.entries.firstOrNull { it.name == genreName }
                                        ?: activeAudioGenre
                                        ?: AudioGenre.BASS
                                } else {
                                    activeAudioGenre ?: AudioGenre.BASS
                                }
                                activePresetSheet = PresetSheetStateModel(
                                    domain = domain,
                                    displayGenre = resolvedDisplay,
                                    audioGenre = resolvedAudio
                                )
                            }
                        )
                    }
                    SynkaTab.DISPLAY_EQ -> {
                        PlaceholderWorkspaceBox(
                            title = "Display EQ (Arka Studio)",
                            subtitle = "Placeholder for Chunks 9–10 • 8-Channel HSL, 5-Zone Tone EQ, Dual Globes, and Subzero Dimmer.",
                            statusLine = "Master: ${if (engine.isMasterEnabled) "ON" else "OFF"} • Active Preset: ${engine.findPresetById(engine.activePresetId)?.name ?: "Custom"}",
                            actionLabel = "Back to Home",
                            onAction = { activeTab = SynkaTab.HOME }
                        )
                    }
                    SynkaTab.AUDIO_EQ -> {
                        PlaceholderWorkspaceBox(
                            title = "Audio EQ (Svara Studio)",
                            subtitle = "Placeholder for Chunks 10–11 • 10-Band Parametric EQ, 5-Zone Sound Contour, Dual Globes, and Anti-Crackle Limiter.",
                            statusLine = "DSP: ${engine.svaraAudio.activeSampleRate} Hz • ${engine.svaraAudio.activeEncodingLabel()} • Pre ${String.format(java.util.Locale.US, "%+.1f", engine.activeSvara.preAmpDb)} dB",
                            actionLabel = "Back to Home",
                            onAction = { activeTab = SynkaTab.HOME }
                        )
                    }
                    SynkaTab.LOCAL_MEDIA -> {
                        LocalMediaPlaceholderBox(
                            engine = engine,
                            onPickVisual = { visualMediaPicker.launch(arrayOf("image/*", "video/*")) },
                            onPickAudio = { audioMediaPicker.launch(arrayOf("audio/*")) },
                            onJumpHome = { activeTab = SynkaTab.HOME }
                        )
                    }
                }
            }

            if (isAudioLoaded) {
                PersistentMiniPlayerBar(
                    engine = engine,
                    onTapJumpToEq = { activeTab = SynkaTab.AUDIO_EQ }
                )
            }

            PersistentBottomDock(
                activeTab = activeTab,
                onSelectTab = { activeTab = it }
            )
        }

        // Chunk 8: Material3 ModalBottomSheet PresetSheet
        activePresetSheet?.let { sheetModel ->
            PresetSheet(
                engine = engine,
                activeDomain = sheetModel.domain,
                selectedDisplayGenre = sheetModel.displayGenre,
                selectedAudioGenre = sheetModel.audioGenre,
                onSelectDisplayGenre = { genre ->
                    applyDisplayGenreFromDeck(genre)
                    activePresetSheet = sheetModel.copy(
                        domain = PresetDomain.DISPLAY,
                        displayGenre = genre,
                        audioGenre = activeAudioGenre ?: sheetModel.audioGenre
                    )
                },
                onSelectAudioGenre = { genre ->
                    applyAudioGenreFromDeck(genre)
                    activePresetSheet = sheetModel.copy(
                        domain = PresetDomain.AUDIO,
                        audioGenre = genre,
                        displayGenre = activeDisplayGenre ?: sheetModel.displayGenre
                    )
                },
                onSwitchDomain = { newDomain ->
                    activePresetSheet = sheetModel.copy(domain = newDomain)
                },
                onApplyPreset = { preset ->
                    engine.applyPreset(preset)
                    if (preset.id != SynkaBuiltInPresets.KORA.id) {
                        preset.displayGenre?.let { activeDisplayGenre = it }
                        preset.audioGenre?.let { activeAudioGenre = it }
                    }
                    val genreKey = if (sheetModel.domain == PresetDomain.AUDIO) {
                        "${PresetDomain.AUDIO.name}:${sheetModel.audioGenre.name}"
                    } else {
                        "${PresetDomain.DISPLAY.name}:${sheetModel.displayGenre.name}"
                    }
                    lastUsedPresetByGenre[genreKey] = preset.id
                },
                onOpenStudio = { preset, targetDomain ->
                    engine.applyPreset(preset)
                    if (preset.id != SynkaBuiltInPresets.KORA.id) {
                        preset.displayGenre?.let { activeDisplayGenre = it }
                        preset.audioGenre?.let { activeAudioGenre = it }
                    }
                    activePresetSheet = null
                    activeTab = if (targetDomain == PresetDomain.AUDIO) {
                        SynkaTab.AUDIO_EQ
                    } else {
                        SynkaTab.DISPLAY_EQ
                    }
                },
                onEditVisualMedia = {
                    visualMediaPicker.launch(arrayOf("image/*", "video/*"))
                },
                onEditAudioMedia = {
                    audioMediaPicker.launch(arrayOf("audio/*"))
                },
                onDismiss = {
                    activePresetSheet = null
                }
            )
        }

        if (isDiscoverOpen) {
            DiscoverPlaceholderOverlay(
                onDismiss = { isDiscoverOpen = false }
            )
        }

        if (isDrawerOpen) {
            StudioDrawerOverlay(
                engine = engine,
                onResetKora = {
                    engine.resetToKora()
                    activeDisplayGenre = null
                    activeAudioGenre = null
                    isDrawerOpen = false
                },
                onClose = { isDrawerOpen = false }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Persistent Mini-Player Bar (Directly above Bottom Dock when audio is loaded)
// -----------------------------------------------------------------------------

@Composable
private fun PersistentMiniPlayerBar(
    engine: SynkaEngine,
    onTapJumpToEq: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val bars = remember { FloatArray(SvaraAudio.WAVEFORM_BARS) }
    var pulseTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(engine.svaraAudio, engine.isAudioPlaying) {
        while (true) {
            if (engine.isAudioPlaying) {
                engine.svaraAudio.readWaveform(bars)
                pulseTick++
                delay(36L)
            } else {
                var anyActive = false
                for (i in bars.indices) {
                    val v = bars[i] * 0.72f
                    bars[i] = if (v < 0.01f) 0f else v
                    if (bars[i] > 0f) anyActive = true
                }
                if (anyActive) {
                    pulseTick++
                    delay(36L)
                } else {
                    delay(140L)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .clickable(onClick = onTapJumpToEq)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .width(34.dp)
                    .height(20.dp)
            ) {
                @Suppress("UNUSED_VARIABLE")
                val tick = pulseTick
                val count = 8
                val step = (bars.size / count).coerceAtLeast(1)
                val slot = size.width / count
                val barW = (slot * 0.56f).coerceAtLeast(2f)
                val midY = size.height * 0.5f
                val pulseColor = if (engine.isMasterEnabled) colors.activeAccent else colors.secondaryText

                for (i in 0 until count) {
                    val mag = bars[(i * step).coerceAtMost(bars.lastIndex)].coerceIn(0f, 1f)
                    val h = (mag * size.height).coerceAtLeast(3f)
                    drawRoundRect(
                        color = pulseColor,
                        topLeft = Offset(i * slot + (slot - barW) * 0.5f, midY - h * 0.5f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.audioTitle.ifBlank { "Loaded Audio Track" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Tap to open Svara EQ",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, CircleShape)
                    .clickable { engine.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                PlayPauseIcon(
                    isPlaying = engine.isAudioPlaying,
                    color = colors.primaryText,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Persistent Bottom Dock: [Home]   [ Display EQ | EQ ]   [Local Media]
// -----------------------------------------------------------------------------

@Composable
private fun PersistentBottomDock(
    activeTab: SynkaTab,
    onSelectTab: (SynkaTab) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val homeSelected = activeTab == SynkaTab.HOME
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (homeSelected) colors.elevatedSurface else colors.surface)
                    .border(
                        width = if (homeSelected) 1.4.dp else 1.dp,
                        color = if (homeSelected) colors.activeAccent else colors.hairline,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectTab(SynkaTab.HOME) },
                contentAlignment = Alignment.Center
            ) {
                HomeHouseIcon(
                    color = if (homeSelected) colors.activeAccentStrong else colors.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }

            val splitShape = RoundedCornerShape(50)
            val displaySelected = activeTab == SynkaTab.DISPLAY_EQ
            val audioSelected = activeTab == SynkaTab.AUDIO_EQ

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(splitShape)
                    .background(colors.surface)
                    .border(
                        width = 1.dp,
                        color = if (displaySelected || audioSelected) colors.activeAccent.copy(alpha = 0.7f) else colors.hairline,
                        shape = splitShape
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight()
                        .background(if (displaySelected) colors.activeAccent else Color.Transparent)
                        .clickable { onSelectTab(SynkaTab.DISPLAY_EQ) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Display EQ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (displaySelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (displaySelected) SynkaPalette.ObsidianCharcoal else colors.primaryText,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(colors.hairline)
                )

                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .background(if (audioSelected) colors.activeAccent else Color.Transparent)
                        .clickable { onSelectTab(SynkaTab.AUDIO_EQ) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "EQ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (audioSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (audioSelected) SynkaPalette.ObsidianCharcoal else colors.primaryText,
                        maxLines = 1
                    )
                }
            }

            val mediaSelected = activeTab == SynkaTab.LOCAL_MEDIA
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mediaSelected) colors.elevatedSurface else colors.surface)
                    .border(
                        width = if (mediaSelected) 1.4.dp else 1.dp,
                        color = if (mediaSelected) colors.activeAccent else colors.hairline,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectTab(SynkaTab.LOCAL_MEDIA) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LocalMediaIcon(
                    color = if (mediaSelected) colors.activeAccentStrong else colors.secondaryText,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Local\nMedia",
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 11.sp,
                    fontWeight = if (mediaSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (mediaSelected) colors.activeAccentStrong else colors.primaryText
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Placeholder Boxes for Chunks 9–13
// -----------------------------------------------------------------------------

@Composable
private fun PlaceholderWorkspaceBox(
    title: String,
    subtitle: String,
    statusLine: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelMedium,
                color = colors.activeAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primaryText
                )
            }
        }
    }
}

@Composable
private fun LocalMediaPlaceholderBox(
    engine: SynkaEngine,
    onPickVisual: () -> Unit,
    onPickAudio: () -> Unit,
    onJumpHome: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Local Media",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText
            )
            Text(
                text = "Placeholder for Chunk 11 • Media Library & Per-File Preset Bindings. Use the quick pickers below to test live Home preview and the persistent Mini-Player now.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )
            Text(
                text = "Visual: ${engine.visualUri?.lastPathSegment ?: "Built-in Calibration Chart"}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.activeAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Audio: ${engine.audioTitle.ifBlank { "None loaded" }}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .clickable {
                            onPickVisual()
                            onJumpHome()
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pick Photo / Video",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primaryText
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onPickAudio)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pick Audio",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverPlaceholderOverlay(onDismiss: () -> Unit) {
    val colors = LocalSynkaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .clickable(enabled = false, onClick = {})
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Discover Presets",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText
            )
            Text(
                text = "Search across built-in presets, favorites, and custom studio looks arrives in Chunks 12–13.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(50))
                    .background(colors.activeAccent)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = SynkaPalette.ObsidianCharcoal
                )
            }
        }
    }
}

@Composable
private fun StudioDrawerOverlay(
    engine: SynkaEngine,
    onResetKora: () -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .background(colors.surface)
                .border(1.dp, colors.hairline)
                .clickable(enabled = false, onClick = {})
                .systemBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Synka Studio",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.primaryText
                )
                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.clickable(onClick = onClose)
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Text(
                text = "APPEARANCE",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            val isArtisan = engine.themeMode == SynkaThemeMode.ARTISAN_EDITION
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerModeOption(
                    title = "Studio Reference",
                    subtitle = "Neutral steel accents, zero watermarks",
                    selected = !isArtisan,
                    onClick = { engine.setThemeMode(SynkaThemeMode.STUDIO_REFERENCE) }
                )
                DrawerModeOption(
                    title = "Artisan Edition",
                    subtitle = "Kansa Bronze + 5% Yantra banner linework",
                    selected = isArtisan,
                    onClick = { engine.setThemeMode(SynkaThemeMode.ARTISAN_EDITION) }
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .clickable(onClick = onResetKora)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(
                    text = "Reset All to Kora (Neutral)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.primaryText
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "v1 Sandbox • API ${Build.VERSION.SDK_INT} (${Build.MODEL})",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SynkaPalette.SindoorCrimson.copy(alpha = 0.16f))
                    .border(1.dp, SynkaPalette.SindoorCrimson.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable {
                        throw RuntimeException("Synka Test Gate 8: Simulated uncaught exception")
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Test Crash Catcher",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SynkaPalette.SindoorCrimson
                )
            }
        }
    }
}

@Composable
private fun DrawerModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.elevatedSurface else colors.background)
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = if (selected) colors.activeAccent else colors.hairline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.activeAccentStrong else colors.primaryText
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText
        )
    }
}

// -----------------------------------------------------------------------------
// Bottom Dock Vector Icons
// -----------------------------------------------------------------------------

@Composable
private fun HomeHouseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val roofAndWalls = Path().apply {
            moveTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.88f, h * 0.46f)
            moveTo(w * 0.22f, h * 0.44f)
            lineTo(w * 0.22f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.44f)
        }
        drawPath(path = roofAndWalls, color = color, style = stroke)
    }
}

@Composable
private fun LocalMediaIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val folder = Path().apply {
            moveTo(w * 0.10f, h * 0.24f)
            lineTo(w * 0.42f, h * 0.24f)
            lineTo(w * 0.52f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.82f)
            lineTo(w * 0.10f, h * 0.82f)
            close()
        }
        drawPath(path = folder, color = color, style = stroke)
    }
}

// -----------------------------------------------------------------------------
// Preserved Chunk 1 Crash Catcher Screen
// -----------------------------------------------------------------------------

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
            color = SynkaPalette.SindoorCrimson
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Uncaught exception captured and persisted to disk.",
            fontSize = 13.sp,
            color = SynkaPalette.MutedSilver
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
