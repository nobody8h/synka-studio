package com.synka.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    engine: SynkaEngine,
    activeDisplayGenre: DisplayGenre?,
    activeAudioGenre: AudioGenre?,
    isPreviewActive: Boolean = true,
    onOpenDrawer: () -> Unit,
    onPickVisualMedia: () -> Unit,
    onPickAudioMedia: () -> Unit,
    onDisplayGenreTap: (DisplayGenre) -> Unit,
    onAudioGenreTap: (AudioGenre) -> Unit,
    onOpenPresetSheet: (domain: PresetDomain, genre: String) -> Unit,
    onDiscoverPresetApplied: (SynkaPreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalSynkaColors.current
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showDiscoverModal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HomeTopBar(
            isMasterEnabled = engine.isMasterEnabled,
            onToggleMaster = { engine.toggleMasterEnabled() },
            onDiscoverClick = { showDiscoverModal = true },
            onOpenDrawer = onOpenDrawer
        )

        UnlockPotentialBanner(
            isArtisanMode = colors.mode == SynkaThemeMode.ARTISAN_EDITION,
            onClick = { showUnlockDialog = true }
        )

        LivePreviewCard(
            engine = engine,
            isPreviewActive = isPreviewActive,
            onPickVisualMedia = onPickVisualMedia,
            onPickAudioMedia = onPickAudioMedia
        )

        GenreDeckSection(
            engine = engine,
            activeDisplayGenre = activeDisplayGenre,
            activeAudioGenre = activeAudioGenre,
            onToggleLinkedBoth = { engine.setLinkedBoth(!engine.isLinkedBoth) },
            onDisplayGenreClick = { genre ->
                onDisplayGenreTap(genre)
                onOpenPresetSheet(PresetDomain.DISPLAY, genre.name)
            },
            onAudioGenreClick = { genre ->
                onAudioGenreTap(genre)
                onOpenPresetSheet(PresetDomain.AUDIO, genre.name)
            }
        )

        Spacer(modifier = Modifier.height(6.dp))
    }

    if (showUnlockDialog) {
        UnlockRoadmapDialog(onDismiss = { showUnlockDialog = false })
    }

    if (showDiscoverModal) {
        DiscoverCatalogModal(
            engine = engine,
            onApplyPreset = { preset ->
                engine.applyPreset(preset)
                onDiscoverPresetApplied(preset)
            },
            onDismiss = { showDiscoverModal = false }
        )
    }
}

// -----------------------------------------------------------------------------
// Discover & Search Modal powered by PresetShare.DiscoverCatalog
// -----------------------------------------------------------------------------

@Composable
private fun DiscoverCatalogModal(
    engine: SynkaEngine,
    onApplyPreset: (SynkaPreset) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var domainFilter by remember { mutableStateOf<PresetDomain?>(null) }
    var catalogEntries by remember { mutableStateOf(PresetShare.DiscoverCatalog.currentEntries()) }
    var isSyncing by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf("Curated community catalog (${catalogEntries.size} presets)")
    }

    LaunchedEffect(Unit) {
        isSyncing = true
        val result = PresetShare.DiscoverCatalog.fetchCatalog(forceRefresh = false)
        catalogEntries = result.entries
        statusText = result.statusMessage
        isSyncing = false
    }

    val filteredEntries = remember(catalogEntries, searchQuery, domainFilter) {
        PresetShare.DiscoverCatalog.search(
            entries = catalogEntries,
            query = searchQuery,
            domainFilter = domainFilter
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Discover Presets",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.primaryText
                    )
                    Text(
                        text = if (isSyncing) "Checking community feed..." else statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.elevatedSurface)
                            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                            .clickable(enabled = !isSyncing) {
                                scope.launch {
                                    isSyncing = true
                                    val result = PresetShare.DiscoverCatalog.fetchCatalog(forceRefresh = true)
                                    catalogEntries = result.entries
                                    statusText = result.statusMessage
                                    isSyncing = false
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isSyncing) "..." else "SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.activeAccent
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.elevatedSurface)
                            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "CLOSE",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.secondaryText
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search Kodak, Harman, IEM, OLED, Night...",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "CLEAR",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.activeAccent,
                            modifier = Modifier
                                .clickable { searchQuery = "" }
                                .padding(end = 10.dp)
                        )
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.activeAccent,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.primaryText,
                    unfocusedTextColor = colors.primaryText,
                    cursorColor = colors.activeAccent,
                    focusedContainerColor = colors.background,
                    unfocusedContainerColor = colors.background,
                    focusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    null to "All",
                    PresetDomain.DISPLAY to "Display",
                    PresetDomain.AUDIO to "Audio",
                    PresetDomain.BOTH to "Both"
                )
                filters.forEach { (filterDomain, label) ->
                    val selected = domainFilter == filterDomain
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) colors.activeAccent else colors.background)
                            .border(
                                width = 1.dp,
                                color = if (selected) colors.activeAccent else colors.hairline,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { domainFilter = filterDomain }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) SynkaPalette.ObsidianCharcoal else colors.primaryText
                        )
                    }
                }
            }

            if (filteredEntries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.background)
                        .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No presets match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primaryText
                    )
                    Text(
                        text = "Clear Filters",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.activeAccent,
                        modifier = Modifier.clickable {
                            searchQuery = ""
                            domainFilter = null
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredEntries, key = { it.preset.id }) { entry ->
                        val isSaved = engine.userPresets.any {
                            it.name.equals(entry.preset.name, ignoreCase = true)
                        }
                        val isActive = engine.activePresetId == entry.preset.id

                        DiscoverPresetCard(
                            entry = entry,
                            isActive = isActive,
                            isSaved = isSaved,
                            onApply = {
                                onApplyPreset(entry.preset)
                                Toast.makeText(
                                    context,
                                    "Applied '${entry.preset.name}'",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onSave = {
                                val saved = engine.saveUserPreset(
                                    name = entry.preset.name,
                                    domain = entry.preset.domain,
                                    displayGenre = entry.preset.displayGenre,
                                    audioGenre = entry.preset.audioGenre,
                                    arka = entry.preset.arka,
                                    svara = entry.preset.svara
                                )
                                onApplyPreset(saved)
                                Toast.makeText(
                                    context,
                                    "Saved '${saved.name}' to your presets",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onCopyCode = {
                                val code = PresetShare.encodePreset(entry.preset)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Synka Preset", code))
                                Toast.makeText(
                                    context,
                                    "Copied SYNK1 code for '${entry.preset.name}'",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPresetCard(
    entry: PresetShare.CatalogEntry,
    isActive: Boolean,
    isSaved: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onCopyCode: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isActive) colors.elevatedSurface else colors.background)
            .border(
                width = if (isActive) 1.4.dp else 1.dp,
                color = if (isActive) colors.activeAccent else colors.hairline,
                shape = shape
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) colors.activeAccentStrong else colors.primaryText
                )
                Text(
                    text = "by ${entry.author}" + if (entry.isRemote) " • Remote" else " • Curated",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = entry.preset.domain.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.activeAccent
                )
            }
        }

        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryText
        )

        if (entry.tags.isNotEmpty()) {
            Text(
                text = entry.tags.joinToString("  •  ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.activeAccent)
                    .clickable(onClick = onApply)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isActive) "Applied" else "Apply",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = SynkaPalette.ObsidianCharcoal
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .clickable(onClick = onSave)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSaved) "Save Copy" else "Save",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primaryText
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .clickable(onClick = onCopyCode)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SYNK1",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Top Bar: Physical ON/OFF Toggle | Discover Pill | Hamburger Menu
// -----------------------------------------------------------------------------

@Composable
private fun HomeTopBar(
    isMasterEnabled: Boolean,
    onToggleMaster: () -> Unit,
    onDiscoverClick: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MasterBypassPillToggle(
            enabled = isMasterEnabled,
            onToggle = onToggleMaster
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(50))
                .clickable(onClick = onDiscoverClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Discover",
                style = MaterialTheme.typography.titleSmall,
                color = colors.primaryText
            )
            SearchLoupeIcon(
                color = colors.secondaryText,
                modifier = Modifier.size(14.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenDrawer),
            contentAlignment = Alignment.Center
        ) {
            HamburgerMenuIcon(
                color = colors.primaryText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MasterBypassPillToggle(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val trackColor by animateColorAsState(
        targetValue = if (enabled) SynkaPalette.SindoorCrimson else colors.elevatedSurface,
        animationSpec = tween(durationMillis = 160),
        label = "masterTrack"
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) SynkaPalette.SindoorCrimson else colors.hairline,
        animationSpec = tween(durationMillis = 160),
        label = "masterBorder"
    )
    val knobOffset by animateDpAsState(
        targetValue = if (enabled) 42.dp else 4.dp,
        animationSpec = tween(durationMillis = 160),
        label = "masterKnob"
    )

    Box(
        modifier = Modifier
            .width(74.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (enabled) "ON" else "OFF",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else colors.secondaryText,
            modifier = Modifier
                .align(if (enabled) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 11.dp)
        )

        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(28.dp)
                .clip(CircleShape)
                .background(if (enabled) SynkaPalette.ChandiSilver else colors.secondaryText.copy(alpha = 0.7f))
                .border(
                    width = 1.dp,
                    color = SynkaPalette.ObsidianCharcoal.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )
    }
}

// -----------------------------------------------------------------------------
// Thin Banner: "UNLOCK The full Potential" (Yantra watermark ONLY in Artisan)
// -----------------------------------------------------------------------------

@Composable
private fun UnlockPotentialBanner(
    isArtisanMode: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = if (isArtisanMode) colors.activeAccent.copy(alpha = 0.35f) else colors.hairline,
                shape = shape
            )
            .clickable(onClick = onClick)
    ) {
        if (isArtisanMode) {
            YantraWatermarkCanvas(modifier = Modifier.matchParentSize())
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.activeAccent)
                )
                Text(
                    text = "UNLOCK The full Potential",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primaryText,
                    letterSpacing = 0.6.sp
                )
            }

            Text(
                text = "v1 ROADMAP",
                style = MaterialTheme.typography.labelSmall,
                color = colors.activeAccent
            )
        }
    }
}

@Composable
private fun UnlockRoadmapDialog(onDismiss: () -> Unit) {
    val colors = LocalSynkaColors.current

    Dialog(onDismissRequest = onDismiss) {
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
                text = "Synka Studio Roadmap",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.elevatedSurface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "v1 • LOCAL MEDIA SANDBOX (ACTIVE)",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.activeAccent
                )
                Text(
                    text = "All features are unlocked locally. Arka runs live AGSL 8-channel HSL and tone grading on your chosen photos and videos, while Svara processes local audio through a 12-stage TDF-II parametric DSP chain without root or DRM workarounds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primaryText
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "v2 • TV & SYSTEM EXTENSIONS (PLANNED)",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondaryText
                )
                Text(
                    text = "Planned work explores companion Smart TV calibration console sync, external display reference profiles, and Android system audio/overlay hooks where OS security boundaries permit. Nothing is paywalled or simulated in v1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.activeAccent)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Got It",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SynkaPalette.ObsidianCharcoal
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Center Live Preview Card (Visual Viewport + Before/After Hold + Audio Bar)
// -----------------------------------------------------------------------------

@Composable
private fun LivePreviewCard(
    engine: SynkaEngine,
    isPreviewActive: Boolean,
    onPickVisualMedia: () -> Unit,
    onPickAudioMedia: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val cardShape = RoundedCornerShape(18.dp)

    var containerViewRef by remember { mutableStateOf<FrameLayout?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var imageViewRef by remember { mutableStateOf<ImageView?>(null) }
    var isHoldingBeforeAfter by remember { mutableStateOf(false) }

    val testPatternBitmap = remember { createCalibrationTestPatternBitmap(960, 540) }
    DisposableEffect(testPatternBitmap) {
        onDispose {
            if (!testPatternBitmap.isRecycled) {
                testPatternBitmap.recycle()
            }
        }
    }

    LaunchedEffect(engine, containerViewRef, textureViewRef, imageViewRef, isPreviewActive) {
        val container = containerViewRef
        val tv = textureViewRef
        val iv = imageViewRef
        if (isPreviewActive && container != null && tv != null && iv != null) {
            engine.bindVisualTarget(tv, iv, container)
        }
    }

    DisposableEffect(engine, containerViewRef, textureViewRef, imageViewRef) {
        val container = containerViewRef
        val tv = textureViewRef
        val iv = imageViewRef
        onDispose {
            engine.unbindVisualTarget(tv, iv, container)
        }
    }

    LaunchedEffect(engine.visualUri, engine.visualIsVideo, isPreviewActive, textureViewRef, imageViewRef) {
        val tv = textureViewRef
        val iv = imageViewRef
        if (tv != null && iv != null) {
            if (engine.visualUri != null && engine.visualIsVideo) {
                tv.visibility = View.VISIBLE
                iv.visibility = View.GONE
            } else {
                tv.visibility = View.GONE
                iv.visibility = View.VISIBLE
                if (engine.visualUri == null && !testPatternBitmap.isRecycled) {
                    iv.setImageBitmap(testPatternBitmap)
                }
            }
        }
    }

    LaunchedEffect(
        isPreviewActive,
        isHoldingBeforeAfter,
        engine.isMasterEnabled,
        engine.activeArka,
        engine.visualUri,
        engine.visualIsVideo,
        containerViewRef,
        textureViewRef,
        imageViewRef
    ) {
        if (!isPreviewActive) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val bypass = isHoldingBeforeAfter || !engine.isMasterEnabled
            val container = containerViewRef ?: return@LaunchedEffect
            textureViewRef?.let { engine.arkaShader.clearFromView(it) }
            imageViewRef?.let { engine.arkaShader.clearFromView(it) }
            engine.arkaShader.applyToView(
                targetView = container,
                params = engine.activeArka,
                bypass = bypass
            )
        }
    }

    val activePresetName = remember(engine.activePresetId, engine.userPresets) {
        engine.findPresetById(engine.activePresetId)?.name ?: "Custom Grade"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, cardShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(216.dp)
                .background(SynkaPalette.ObsidianCharcoal)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = true)
                        var activated = false
                        try {
                            val upEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation()
                            }
                            if (upEarly == null) {
                                activated = true
                                isHoldingBeforeAfter = true
                                waitForUpOrCancellation()
                            }
                        } finally {
                            if (activated) {
                                isHoldingBeforeAfter = false
                            }
                        }
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val parentFrame = this

                        val tv = TextureView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER
                            )
                            visibility = View.GONE
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    parentFrame.invalidate()
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    parentFrame.invalidate()
                                }

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                    parentFrame.invalidate()
                                }
                            }
                        }

                        val iv = ImageView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER
                            )
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            visibility = View.VISIBLE
                            if (!testPatternBitmap.isRecycled) {
                                setImageBitmap(testPatternBitmap)
                            }
                        }

                        addView(tv)
                        addView(iv)
                        containerViewRef = parentFrame
                        textureViewRef = tv
                        imageViewRef = iv
                    }
                },
                onRelease = { container ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        engine.arkaShader.clearFromView(container)
                        textureViewRef?.let { engine.arkaShader.clearFromView(it) }
                        imageViewRef?.let { engine.arkaShader.clearFromView(it) }
                    }
                    textureViewRef?.surfaceTextureListener = null
                    engine.unbindVisualTarget(textureViewRef, imageViewRef, container)
                    container.removeAllViews()
                    containerViewRef = null
                    textureViewRef = null
                    imageViewRef = null
                }
            )

            val badgeText = when {
                isHoldingBeforeAfter -> "BEFORE • ORIGINAL"
                !engine.isMasterEnabled -> "BYPASS • OFF"
                engine.visualUri == null -> "TEST CHART • $activePresetName"
                else -> activePresetName.uppercase()
            }
            val badgeBg = when {
                isHoldingBeforeAfter -> SynkaPalette.SindoorCrimson.copy(alpha = 0.90f)
                !engine.isMasterEnabled -> SynkaPalette.ObsidianCharcoal.copy(alpha = 0.82f)
                else -> SynkaPalette.ObsidianCharcoal.copy(alpha = 0.72f)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeBg)
                    .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHoldingBeforeAfter) Color.White else colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isHoldingBeforeAfter) {
                Text(
                    text = "Hold for Before/After",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primaryText.copy(alpha = 0.60f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface.copy(alpha = 0.92f))
                    .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable(onClick = onPickVisualMedia)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (engine.visualUri == null) "Pick Media" else "Change Media",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        CompactAudioStatusBar(
            engine = engine,
            onPickAudioMedia = onPickAudioMedia
        )
    }
}

@Composable
private fun CompactAudioStatusBar(
    engine: SynkaEngine,
    onPickAudioMedia: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val hasPlayableAudio = engine.audioUri != null || (engine.visualUri != null && engine.visualIsVideo)

    val miniBars = remember { FloatArray(SvaraAudio.WAVEFORM_BARS) }
    var pulseTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(engine.svaraAudio, engine.isAudioPlaying) {
        while (true) {
            if (engine.isAudioPlaying) {
                engine.svaraAudio.readWaveform(miniBars)
                pulseTick++
                delay(40L)
            } else {
                var active = false
                for (i in miniBars.indices) {
                    val decayed = miniBars[i] * 0.7f
                    miniBars[i] = if (decayed < 0.01f) 0f else decayed
                    if (miniBars[i] > 0f) active = true
                }
                if (active) {
                    pulseTick++
                    delay(40L)
                } else {
                    delay(140L)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !engine.isMasterEnabled -> colors.secondaryText.copy(alpha = 0.45f)
                        engine.isAudioPlaying -> SynkaPalette.SindoorCrimson
                        hasPlayableAudio -> colors.activeAccent
                        else -> colors.secondaryText.copy(alpha = 0.45f)
                    }
                )
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (hasPlayableAudio) {
                    engine.audioTitle.ifBlank { "Loaded Media Audio" }
                } else {
                    "No audio loaded"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val dspLabel = if (engine.isMasterEnabled) {
                "SVARA DSP ON • Pre ${formatSignedDb(engine.activeSvara.preAmpDb)}"
            } else {
                "SVARA BYPASSED"
            }
            Text(
                text = dspLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText,
                maxLines = 1
            )
        }

        Canvas(
            modifier = Modifier
                .width(44.dp)
                .height(18.dp)
        ) {
            @Suppress("UNUSED_VARIABLE")
            val tick = pulseTick
            val barCount = 12
            val step = (miniBars.size / barCount).coerceAtLeast(1)
            val slotW = size.width / barCount
            val barW = (slotW * 0.58f).coerceAtLeast(2f)
            val midY = size.height * 0.5f
            val activeColor = if (engine.isMasterEnabled) colors.activeAccent else colors.secondaryText.copy(alpha = 0.4f)

            for (i in 0 until barCount) {
                val mag = miniBars[(i * step).coerceAtMost(miniBars.lastIndex)].coerceIn(0f, 1f)
                val barH = (mag * size.height).coerceAtLeast(3f)
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(i * slotW + (slotW - barW) * 0.5f, midY - barH * 0.5f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
                )
            }
        }

        if (hasPlayableAudio) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, CircleShape)
                    .clickable { engine.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                PlayPauseIcon(
                    isPlaying = engine.isAudioPlaying,
                    color = colors.primaryText,
                    modifier = Modifier.size(13.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(50))
                    .clickable(onClick = onPickAudioMedia)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Audio",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primaryText
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Genre Deck: 4 Display Mode Cards + [ both 🔗 ] Link Pill + 4 For Audio Cards
// -----------------------------------------------------------------------------

@Composable
private fun GenreDeckSection(
    engine: SynkaEngine,
    activeDisplayGenre: DisplayGenre?,
    activeAudioGenre: AudioGenre?,
    onToggleLinkedBoth: () -> Unit,
    onDisplayGenreClick: (DisplayGenre) -> Unit,
    onAudioGenreClick: (AudioGenre) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Display Mode",
                style = MaterialTheme.typography.titleSmall,
                color = colors.secondaryText
            )
            if (activeDisplayGenre != null) {
                Text(
                    text = displayGenreLabel(activeDisplayGenre).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.activeAccent
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val displayItems = listOf(
                DisplayGenre.CINEMA_GRADE to "Cinema Grade",
                DisplayGenre.RED_ISO to "Red Iso",
                DisplayGenre.BW to "B&W",
                DisplayGenre.WARM to "Warm"
            )
            displayItems.forEach { (genre, label) ->
                GenreDeckCard(
                    label = label,
                    isSelected = activeDisplayGenre == genre,
                    isMasterEnabled = engine.isMasterEnabled,
                    onClick = { onDisplayGenreClick(genre) },
                    modifier = Modifier.weight(1f),
                    glyph = { tint ->
                        DisplayGenreGlyph(
                            genre = genre,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = colors.hairline
            )

            val linked = engine.isLinkedBoth
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (linked) colors.elevatedSurface else colors.surface)
                    .border(
                        width = 1.dp,
                        color = if (linked) colors.activeAccent else colors.hairline,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable(onClick = onToggleLinkedBoth)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "both",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (linked) FontWeight.Bold else FontWeight.Medium,
                    color = if (linked) colors.activeAccentStrong else colors.secondaryText
                )
                LinkChainIcon(
                    linked = linked,
                    color = if (linked) colors.activeAccent else colors.secondaryText,
                    modifier = Modifier.size(14.dp)
                )
            }

            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = colors.hairline
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "For Audio",
                style = MaterialTheme.typography.titleSmall,
                color = colors.secondaryText
            )
            if (activeAudioGenre != null) {
                Text(
                    text = audioGenreLabel(activeAudioGenre).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.activeAccent
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val audioItems = listOf(
                AudioGenre.BASS to "Bass",
                AudioGenre.FLAT to "Flat",
                AudioGenre.VOCAL to "Vocal",
                AudioGenre.LATE_NIGHT to "Late Night"
            )
            audioItems.forEach { (genre, label) ->
                GenreDeckCard(
                    label = label,
                    isSelected = activeAudioGenre == genre,
                    isMasterEnabled = engine.isMasterEnabled,
                    onClick = { onAudioGenreClick(genre) },
                    modifier = Modifier.weight(1f),
                    glyph = { tint ->
                        AudioGenreGlyph(
                            genre = genre,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun GenreDeckCard(
    label: String,
    isSelected: Boolean,
    isMasterEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable (Color) -> Unit
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (isSelected) colors.elevatedSurface else colors.surface
    val borderColor = when {
        isSelected && isMasterEnabled -> colors.activeAccent
        isSelected -> colors.secondaryText.copy(alpha = 0.55f)
        else -> colors.hairline
    }
    val contentTint = if (isSelected) colors.activeAccentStrong else colors.primaryText

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bgColor)
            .border(width = if (isSelected) 1.5.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(2.dp))
        glyph(if (isSelected) colors.activeAccent else colors.secondaryText)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentTint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// -----------------------------------------------------------------------------
// Neutral Procedural Color-Gradient Test Pattern Bitmap (shared with PresetSheet)
// -----------------------------------------------------------------------------

internal fun createCalibrationTestPatternBitmap(width: Int, height: Int): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    canvas.drawColor(AndroidColor.rgb(20, 20, 22))

    val padX = width * 0.06f
    val usableW = width - padX * 2f

    val spectrumTop = height * 0.14f
    val spectrumBottom = height * 0.44f
    val hueColors = intArrayOf(
        AndroidColor.rgb(230, 57, 70),
        AndroidColor.rgb(235, 132, 46),
        AndroidColor.rgb(232, 204, 58),
        AndroidColor.rgb(66, 184, 96),
        AndroidColor.rgb(52, 188, 196),
        AndroidColor.rgb(64, 120, 224),
        AndroidColor.rgb(138, 86, 214),
        AndroidColor.rgb(214, 72, 166),
        AndroidColor.rgb(230, 57, 70)
    )
    paint.shader = LinearGradient(
        padX,
        spectrumTop,
        padX + usableW,
        spectrumTop,
        hueColors,
        null,
        Shader.TileMode.CLAMP
    )
    canvas.drawRoundRect(padX, spectrumTop, padX + usableW, spectrumBottom, 16f, 16f, paint)
    paint.shader = null

    val swatchTop = height * 0.50f
    val swatchBottom = height * 0.67f
    val swatches = intArrayOf(
        AndroidColor.rgb(205, 48, 58),
        AndroidColor.rgb(198, 146, 114),
        AndroidColor.rgb(118, 118, 118),
        AndroidColor.rgb(74, 142, 86),
        AndroidColor.rgb(76, 134, 198)
    )
    val gap = usableW * 0.02f
    val swatchW = (usableW - gap * (swatches.size - 1)) / swatches.size
    swatches.forEachIndexed { idx, argb ->
        val left = padX + idx * (swatchW + gap)
        paint.color = argb
        canvas.drawRoundRect(left, swatchTop, left + swatchW, swatchBottom, 12f, 12f, paint)
    }

    val rampTop = height * 0.73f
    val rampBottom = height * 0.86f
    val steps = 11
    val stepW = usableW / steps
    for (i in 0 until steps) {
        val lum = ((i.toFloat() / (steps - 1)) * 255f).toInt().coerceIn(0, 255)
        paint.color = AndroidColor.rgb(lum, lum, lum)
        val left = padX + i * stepW
        canvas.drawRect(left, rampTop, left + stepW + 0.5f, rampBottom, paint)
    }

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = AndroidColor.argb(45, 255, 255, 255)
    canvas.drawRect(padX, rampTop, padX + usableW, rampBottom, paint)

    return bmp
}

// -----------------------------------------------------------------------------
// Crisp Minimal Studio Vector Glyphs
// -----------------------------------------------------------------------------

@Composable
private fun SearchLoupeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val r = size.minDimension * 0.34f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color = color, radius = r, center = c, style = stroke)
        drawLine(
            color = color,
            start = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            end = Offset(size.width * 0.90f, size.height * 0.90f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun HamburgerMenuIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeW = 1.8.dp.toPx()
        val ys = floatArrayOf(0.24f, 0.50f, 0.76f)
        ys.forEach { frac ->
            drawLine(
                color = color,
                start = Offset(size.width * 0.12f, size.height * frac),
                end = Offset(size.width * 0.88f, size.height * frac),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun LinkChainIcon(linked: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val pillH = h * 0.52f
        val pillTop = (h - pillH) * 0.5f
        val leftW = w * 0.46f
        val radius = CornerRadius(pillH * 0.5f, pillH * 0.5f)

        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.04f, pillTop),
            size = Size(leftW, pillH),
            cornerRadius = radius,
            style = stroke
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w - w * 0.04f - leftW, pillTop),
            size = Size(leftW, pillH),
            cornerRadius = radius,
            style = stroke
        )
        if (linked) {
            drawLine(
                color = color,
                start = Offset(w * 0.36f, h * 0.5f),
                end = Offset(w * 0.64f, h * 0.5f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun DisplayGenreGlyph(
    genre: DisplayGenre,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (genre) {
            DisplayGenre.CINEMA_GRADE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.08f, h * 0.20f),
                    size = Size(w * 0.84f, h * 0.60f),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.08f, h * 0.35f), Offset(w * 0.92f, h * 0.35f), 1.2.dp.toPx())
                drawLine(tint, Offset(w * 0.08f, h * 0.65f), Offset(w * 0.92f, h * 0.65f), 1.2.dp.toPx())
            }
            DisplayGenre.RED_ISO -> {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * 0.38f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = stroke
                )
                drawCircle(
                    color = SynkaPalette.SindoorCrimson,
                    radius = size.minDimension * 0.17f,
                    center = Offset(w * 0.5f, h * 0.5f)
                )
            }
            DisplayGenre.BW -> {
                val r = size.minDimension * 0.38f
                val center = Offset(w * 0.5f, h * 0.5f)
                drawCircle(color = tint, radius = r, center = center, style = stroke)
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f)
                )
            }
            DisplayGenre.WARM -> {
                val c = Offset(w * 0.5f, h * 0.5f)
                val r = size.minDimension * 0.22f
                drawCircle(color = tint, radius = r, center = c, style = stroke)
                val rayIn = size.minDimension * 0.33f
                val rayOut = size.minDimension * 0.45f
                for (i in 0 until 8) {
                    val rad = Math.toRadians(i * 45.0)
                    val dx = kotlin.math.cos(rad).toFloat()
                    val dy = kotlin.math.sin(rad).toFloat()
                    drawLine(
                        color = tint,
                        start = Offset(c.x + rayIn * dx, c.y + rayIn * dy),
                        end = Offset(c.x + rayOut * dx, c.y + rayOut * dy),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioGenreGlyph(
    genre: AudioGenre,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = 1.8.dp.toPx()
        when (genre) {
            AudioGenre.BASS -> {
                val heights = floatArrayOf(0.82f, 0.66f, 0.38f, 0.28f)
                val slot = w / heights.size
                heights.forEachIndexed { idx, frac ->
                    val x = slot * idx + slot * 0.5f
                    drawLine(
                        color = tint,
                        start = Offset(x, h * (1f - frac * 0.8f) * 0.5f + h * 0.1f),
                        end = Offset(x, h * 0.88f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
            AudioGenre.FLAT -> {
                drawLine(
                    color = tint,
                    start = Offset(w * 0.10f, h * 0.50f),
                    end = Offset(w * 0.90f, h * 0.50f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                drawCircle(tint, 2.2.dp.toPx(), Offset(w * 0.30f, h * 0.50f))
                drawCircle(tint, 2.2.dp.toPx(), Offset(w * 0.70f, h * 0.50f))
            }
            AudioGenre.VOCAL -> {
                val heights = floatArrayOf(0.28f, 0.62f, 0.88f, 0.62f, 0.28f)
                val slot = w / heights.size
                heights.forEachIndexed { idx, frac ->
                    val x = slot * idx + slot * 0.5f
                    val half = h * frac * 0.42f
                    drawLine(
                        color = tint,
                        start = Offset(x, h * 0.5f - half),
                        end = Offset(x, h * 0.5f + half),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
            AudioGenre.LATE_NIGHT -> {
                val path = Path().apply {
                    moveTo(w * 0.62f, h * 0.16f)
                    cubicTo(w * 0.30f, h * 0.20f, w * 0.26f, h * 0.78f, w * 0.64f, h * 0.84f)
                    cubicTo(w * 0.44f, h * 0.72f, w * 0.44f, h * 0.28f, w * 0.62f, h * 0.16f)
                    close()
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
internal fun PlayPauseIcon(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (isPlaying) {
            val barW = w * 0.26f
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.18f, h * 0.14f),
                size = Size(barW, h * 0.72f),
                cornerRadius = CornerRadius(2f, 2f)
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.56f, h * 0.14f),
                size = Size(barW, h * 0.72f),
                cornerRadius = CornerRadius(2f, 2f)
            )
        } else {
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.86f)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

internal fun displayGenreLabel(genre: DisplayGenre): String = when (genre) {
    DisplayGenre.CINEMA_GRADE -> "Cinema Grade"
    DisplayGenre.RED_ISO -> "Red Iso"
    DisplayGenre.BW -> "B&W"
    DisplayGenre.WARM -> "Warm"
}

internal fun audioGenreLabel(genre: AudioGenre): String = when (genre) {
    AudioGenre.BASS -> "Bass"
    AudioGenre.FLAT -> "Flat"
    AudioGenre.VOCAL -> "Vocal"
    AudioGenre.LATE_NIGHT -> "Late Night"
}

private fun formatSignedDb(db: Float): String {
    val sign = if (db >= 0f) "+" else ""
    return "$sign${String.format(java.util.Locale.US, "%.1f", db)} dB"
}
