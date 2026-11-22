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
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.synka.studio.display.engine.DisplayEqEngine
import com.synka.studio.display.ui.SynkaDisplayEqScreen
import com.synka.studio.media.SynkaMediaPlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

private enum class DisplayPaneMode {
    LIVE_3_TIER,
    CLASSIC_ARKA
}

private enum class LocalMediaPaneMode {
    LOSSLESS_PLAYER,
    CLASSIC_PICKER
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

    override fun onResume() {
        super.onResume()
        SynkaOverlayService.refreshPermissionState(this)
        SynkaOverlayService.setSynkaUiForeground(true)
        DisplayEqEngine.getInstance(this).reapplyNow()
    }

    override fun onPause() {
        SynkaOverlayService.setSynkaUiForeground(false)
        super.onPause()
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

@Composable
private fun SynkaAppShell(engine: SynkaEngine) {
    val context = LocalContext.current
    val displayEqState by DisplayEqEngine.getInstance(context).state.collectAsState()
    val colors = LocalSynkaColors.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(SynkaTab.HOME) }
    var displayPaneMode by remember { mutableStateOf(DisplayPaneMode.LIVE_3_TIER) }
    var localMediaPaneMode by remember { mutableStateOf(LocalMediaPaneMode.LOSSLESS_PLAYER) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var activePresetSheet by remember { mutableStateOf<PresetSheetStateModel?>(null) }
    var jumpToDisplayStudioAfterSaf by remember { mutableStateOf(false) }

    var activeDisplayGenre by remember { mutableStateOf<DisplayGenre?>(null) }
    var activeAudioGenre by remember { mutableStateOf<AudioGenre?>(null) }
    val lastUsedPresetByGenre = remember { mutableStateMapOf<String, String>() }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun syncGenreHighlightsForPreset(preset: SynkaPreset) {
        if (preset.id == SynkaBuiltInPresets.KORA.id) {
            activeDisplayGenre = null
            activeAudioGenre = null
            return
        }
        preset.displayGenre?.let { genre ->
            activeDisplayGenre = genre
            lastUsedPresetByGenre["${PresetDomain.DISPLAY.name}:${genre.name}"] = preset.id
        }
        preset.audioGenre?.let { genre ->
            activeAudioGenre = genre
            lastUsedPresetByGenre["${PresetDomain.AUDIO.name}:${genre.name}"] = preset.id
        }
    }

    fun syncGenreHighlightsForFileBinding(uri: Uri) {
        val binding = engine.perFileBindings[uri.toString()] ?: return
        binding.first?.let { id ->
            engine.findPresetById(id)?.let { syncGenreHighlightsForPreset(it) }
        }
        binding.second?.let { id ->
            engine.findPresetById(id)?.let { syncGenreHighlightsForPreset(it) }
        }
    }

    val visualMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val shouldJump = jumpToDisplayStudioAfterSaf
        jumpToDisplayStudioAfterSaf = false
        if (uri != null) {
            takeReadPermission(uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video/")
            engine.setVisualMedia(uri = uri, isVideo = isVideo)
            syncGenreHighlightsForFileBinding(uri)
            if (shouldJump) {
                displayPaneMode = DisplayPaneMode.CLASSIC_ARKA
                activeTab = SynkaTab.DISPLAY_EQ
            }
        }
    }

    val audioMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takeReadPermission(uri)
            engine.setAudioMedia(uri = uri)
            syncGenreHighlightsForFileBinding(uri)
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

    val isAudioLoaded = if (engine.audioUri != null) {
        true
    } else {
        engine.visualUri != null && engine.visualIsVideo
    }
    val isPresetSheetShowingVisual = activePresetSheet != null && activePresetSheet?.domain != PresetDomain.AUDIO

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

    val drawerGesturesEnabled = if (activeTab == SynkaTab.HOME) true else drawerState.isOpen

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
        drawerContent = {
            SettingsDrawerSheet(
                engine = engine,
                onResetKora = {
                    engine.resetToKora()
                    DisplayEqEngine.getInstance(context).updateState { it.copy(enabled = false) }
                    activeDisplayGenre = null
                    activeAudioGenre = null
                    scope.launch { drawerState.close() }
                },
                onPresetImported = { imported ->
                    syncGenreHighlightsForPreset(imported)
                },
                onCloseDrawer = {
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
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
                                onOpenDrawer = {
                                    scope.launch { drawerState.open() }
                                },
                                onPickVisualMedia = {
                                    jumpToDisplayStudioAfterSaf = false
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
                                },
                                onDiscoverPresetApplied = { preset ->
                                    syncGenreHighlightsForPreset(preset)
                                }
                            )
                        }
                        SynkaTab.DISPLAY_EQ -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                StudioModeSwitcherBar(
                                    leftLabel = "Live 3-Tier EQ",
                                    rightLabel = "Classic Arka",
                                    leftSelected = displayPaneMode == DisplayPaneMode.LIVE_3_TIER,
                                    onSelectLeft = { displayPaneMode = DisplayPaneMode.LIVE_3_TIER },
                                    onSelectRight = { displayPaneMode = DisplayPaneMode.CLASSIC_ARKA }
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    when (displayPaneMode) {
                                        DisplayPaneMode.LIVE_3_TIER -> {
                                            SynkaDisplayEqScreen(
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        DisplayPaneMode.CLASSIC_ARKA -> {
                                            ArkaStudio(
                                                engine = engine,
                                                activeDisplayGenre = activeDisplayGenre,
                                                isPreviewActive = !isPresetSheetShowingVisual,
                                                onEditVisualMedia = {
                                                    jumpToDisplayStudioAfterSaf = false
                                                    visualMediaPicker.launch(arrayOf("image/*", "video/*"))
                                                },
                                                onResetToKora = {
                                                    engine.resetToKora()
                                                    activeDisplayGenre = null
                                                    activeAudioGenre = null
                                                },
                                                onPresetSaved = { saved ->
                                                    saved.displayGenre?.let { genre ->
                                                        activeDisplayGenre = genre
                                                        lastUsedPresetByGenre["${PresetDomain.DISPLAY.name}:${genre.name}"] = saved.id
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        SynkaTab.AUDIO_EQ -> {
                            SvaraStudio(
                                engine = engine,
                                activeAudioGenre = activeAudioGenre,
                                onEditAudioMedia = {
                                    audioMediaPicker.launch(arrayOf("audio/*"))
                                },
                                onResetAudioToFlat = {
                                    engine.applyPreset(SynkaBuiltInPresets.AUDIO_STUDIO_FLAT)
                                    activeAudioGenre = AudioGenre.FLAT
                                },
                                onPresetSaved = { saved ->
                                    saved.audioGenre?.let { genre ->
                                        activeAudioGenre = genre
                                        lastUsedPresetByGenre["${PresetDomain.AUDIO.name}:${genre.name}"] = saved.id
                                    }
                                }
                            )
                        }
                        SynkaTab.LOCAL_MEDIA -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                StudioModeSwitcherBar(
                                    leftLabel = "Lossless & Video",
                                    rightLabel = "Classic Picker",
                                    leftSelected = localMediaPaneMode == LocalMediaPaneMode.LOSSLESS_PLAYER,
                                    onSelectLeft = { localMediaPaneMode = LocalMediaPaneMode.LOSSLESS_PLAYER },
                                    onSelectRight = { localMediaPaneMode = LocalMediaPaneMode.CLASSIC_PICKER }
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    when (localMediaPaneMode) {
                                        LocalMediaPaneMode.LOSSLESS_PLAYER -> {
                                            SynkaMediaPlayerScreen(
                                                displayEqState = displayEqState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        LocalMediaPaneMode.CLASSIC_PICKER -> {
                                            LocalMediaScreen(
                                                engine = engine,
                                                onVideoLoaded = { uri ->
                                                    syncGenreHighlightsForFileBinding(uri)
                                                    displayPaneMode = DisplayPaneMode.CLASSIC_ARKA
                                                    activeTab = SynkaTab.DISPLAY_EQ
                                                },
                                                onAudioLoaded = { uri, jumpToAudioStudio ->
                                                    syncGenreHighlightsForFileBinding(uri)
                                                    if (jumpToAudioStudio) {
                                                        activeTab = SynkaTab.AUDIO_EQ
                                                    }
                                                },
                                                onPickVisualSaf = {
                                                    jumpToDisplayStudioAfterSaf = true
                                                    visualMediaPicker.launch(arrayOf("video/*", "image/*"))
                                                },
                                                onPickAudioSaf = {
                                                    audioMediaPicker.launch(arrayOf("audio/*"))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAudioLoaded && activeTab != SynkaTab.AUDIO_EQ) {
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
                        if (targetDomain == PresetDomain.AUDIO) {
                            activeTab = SynkaTab.AUDIO_EQ
                        } else {
                            displayPaneMode = DisplayPaneMode.CLASSIC_ARKA
                            activeTab = SynkaTab.DISPLAY_EQ
                        }
                    },
                    onEditVisualMedia = {
                        jumpToDisplayStudioAfterSaf = false
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
        }
    }
}

@Composable
private fun StudioModeSwitcherBar(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onSelectLeft: () -> Unit,
    onSelectRight: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val pillShape = RoundedCornerShape(50)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(pillShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline, pillShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (leftSelected) colors.activeAccent else Color.Transparent)
                    .clickable(onClick = onSelectLeft),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = leftLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (leftSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (leftSelected) SynkaPalette.ObsidianCharcoal else colors.primaryText,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(colors.hairline)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (!leftSelected) colors.activeAccent else Color.Transparent)
                    .clickable(onClick = onSelectRight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rightLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (!leftSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (!leftSelected) SynkaPalette.ObsidianCharcoal else colors.primaryText,
                    maxLines = 1
                )
            }
        }
    }
}

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
            val splitHighlighted = if (displaySelected) true else audioSelected

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(splitShape)
                    .background(colors.surface)
                    .border(
                        width = 1.dp,
                        color = if (splitHighlighted) colors.activeAccent.copy(alpha = 0.7f) else colors.hairline,
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
