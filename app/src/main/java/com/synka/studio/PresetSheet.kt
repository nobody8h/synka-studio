package com.synka.studio

import android.os.Build
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(
    engine: SynkaEngine,
    activeDomain: PresetDomain,
    selectedDisplayGenre: DisplayGenre,
    selectedAudioGenre: AudioGenre,
    onSelectDisplayGenre: (DisplayGenre) -> Unit,
    onSelectAudioGenre: (AudioGenre) -> Unit,
    onSwitchDomain: (PresetDomain) -> Unit,
    onApplyPreset: (SynkaPreset) -> Unit,
    onOpenStudio: (SynkaPreset, PresetDomain) -> Unit,
    onEditVisualMedia: () -> Unit,
    onEditAudioMedia: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isAudioMode = activeDomain == PresetDomain.AUDIO

    val previewHeight by animateDpAsState(
        targetValue = if (isAudioMode) 114.dp else 184.dp,
        animationSpec = tween(durationMillis = 220),
        label = "sheetPreviewHeight"
    )

    val presets = remember(
        activeDomain,
        selectedDisplayGenre,
        selectedAudioGenre,
        engine.isLinkedBoth,
        engine.userPresets,
        engine.favoriteIds
    ) {
        val all = engine.allPresets()
        val filtered = if (isAudioMode) {
            all.filter { preset ->
                preset.audioGenre == selectedAudioGenre &&
                    (preset.id != SynkaBuiltInPresets.KORA.id || selectedAudioGenre == AudioGenre.FLAT)
            }
        } else {
            all.filter { preset ->
                preset.displayGenre == selectedDisplayGenre &&
                    preset.id != SynkaBuiltInPresets.KORA.id
            }
        }

        filtered.sortedWith(
            compareByDescending<SynkaPreset> { it.isFavorite }
                .thenByDescending { if (engine.isLinkedBoth) it.domain == PresetDomain.BOTH else it.domain != PresetDomain.BOTH }
                .thenByDescending { it.isUserCreated }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.primaryText,
        scrimColor = Color.Black.copy(alpha = 0.48f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.secondaryText.copy(alpha = 0.45f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact Header Row: Domain Switcher + Link Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.background)
                        .border(1.dp, colors.hairline, RoundedCornerShape(50))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SheetDomainPill(
                        label = "Display",
                        selected = !isAudioMode,
                        onClick = { onSwitchDomain(PresetDomain.DISPLAY) }
                    )
                    SheetDomainPill(
                        label = "Audio",
                        selected = isAudioMode,
                        onClick = { onSwitchDomain(PresetDomain.AUDIO) }
                    )
                }

                Text(
                    text = if (engine.isLinkedBoth) "BOTH LINKED" else "INDEPENDENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (engine.isLinkedBoth) colors.activeAccent else colors.secondaryText
                )
            }

            // Pinned Top Preview Box (Visual Grading Preview OR Half-Height Audio Test Deck)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SynkaPalette.ObsidianCharcoal)
                    .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
            ) {
                if (isAudioMode) {
                    PinnedAudioTestDeck(
                        engine = engine,
                        onEditAudioMedia = onEditAudioMedia
                    )
                } else {
                    PinnedVisualPreviewBox(
                        engine = engine,
                        onEditVisualMedia = onEditVisualMedia
                    )
                }
            }

            // 4 Dynamic Genre Pills directly below Preview Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isAudioMode) {
                    AudioGenre.entries.forEach { genre ->
                        DynamicGenrePill(
                            label = audioGenreLabel(genre),
                            selected = selectedAudioGenre == genre,
                            onClick = { onSelectAudioGenre(genre) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    DisplayGenre.entries.forEach { genre ->
                        DynamicGenrePill(
                            label = displayGenreLabel(genre),
                            selected = selectedDisplayGenre == genre,
                            onClick = { onSelectDisplayGenre(genre) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            // Scrollable Preset List Rows
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(items = presets, key = { it.id }) { preset ->
                    val isActive = engine.activePresetId == preset.id
                    PresetListRow(
                        preset = preset,
                        isActive = isActive,
                        onApply = { onApplyPreset(preset) },
                        onToggleFavorite = { engine.toggleFavorite(preset.id) },
                        onOpenStudio = {
                            val targetDomain = when {
                                isAudioMode -> PresetDomain.AUDIO
                                preset.domain == PresetDomain.AUDIO -> PresetDomain.AUDIO
                                else -> PresetDomain.DISPLAY
                            }
                            onOpenStudio(preset, targetDomain)
                        }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Pinned Visual Preview Box (Live AGSL TextureView/ImageView + Edit + Hold B/A)
// -----------------------------------------------------------------------------

@Composable
private fun PinnedVisualPreviewBox(
    engine: SynkaEngine,
    onEditVisualMedia: () -> Unit
) {
    val colors = LocalSynkaColors.current
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var imageViewRef by remember { mutableStateOf<ImageView?>(null) }
    var isHoldingBeforeAfter by remember { mutableStateOf(false) }

    val fallbackChart = remember { createCalibrationTestPatternBitmap(960, 540) }
    DisposableEffect(fallbackChart) {
        onDispose {
            if (!fallbackChart.isRecycled) {
                fallbackChart.recycle()
            }
        }
    }

    // Bind PresetSheet's TextureView/ImageView as the live engine visual target
    LaunchedEffect(engine, textureViewRef, imageViewRef) {
        val tv = textureViewRef
        val iv = imageViewRef
        if (tv != null && iv != null) {
            engine.bindVisualTarget(tv, iv)
        }
    }

    // Sync TextureView vs ImageView visibility & fallback calibration bitmap
    LaunchedEffect(engine.visualUri, engine.visualIsVideo, textureViewRef, imageViewRef) {
        val tv = textureViewRef
        val iv = imageViewRef
        if (tv != null && iv != null) {
            if (engine.visualUri != null && engine.visualIsVideo) {
                tv.visibility = View.VISIBLE
                iv.visibility = View.GONE
            } else {
                tv.visibility = View.GONE
                iv.visibility = View.VISIBLE
                if (engine.visualUri == null && !fallbackChart.isRecycled) {
                    iv.setImageBitmap(fallbackChart)
                }
            }
        }
    }

    // Apply live AGSL shader or instant Before/After bypass while pressed
    LaunchedEffect(
        isHoldingBeforeAfter,
        engine.isMasterEnabled,
        engine.activeArka,
        engine.visualUri,
        engine.visualIsVideo,
        textureViewRef,
        imageViewRef
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val bypass = isHoldingBeforeAfter || !engine.isMasterEnabled
            val activeView: View? = if (engine.visualUri != null && engine.visualIsVideo) {
                textureViewRef
            } else {
                imageViewRef
            }
            val inactiveView: View? = if (engine.visualUri != null && engine.visualIsVideo) {
                imageViewRef
            } else {
                textureViewRef
            }
            inactiveView?.let { engine.arkaShader.clearFromView(it) }
            activeView?.let {
                engine.arkaShader.applyToView(
                    targetView = it,
                    params = engine.activeArka,
                    bypass = bypass
                )
            }
        }
    }

    val activePresetName = remember(engine.activePresetId, engine.userPresets) {
        engine.findPresetById(engine.activePresetId)?.name ?: "Custom Grade"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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

                    val tv = TextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        visibility = View.GONE
                    }

                    val iv = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        visibility = View.VISIBLE
                    }

                    addView(tv)
                    addView(iv)
                    textureViewRef = tv
                    imageViewRef = iv
                }
            },
            onRelease = { container ->
                textureViewRef?.let { tv ->
                    engine.exoPlayer.clearVideoTextureView(tv)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        engine.arkaShader.clearFromView(tv)
                    }
                }
                imageViewRef?.let { iv ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        engine.arkaShader.clearFromView(iv)
                    }
                }
                container.removeAllViews()
                textureViewRef = null
                imageViewRef = null
            }
        )

        // Top-left live grade / Before-After badge
        val badgeText = when {
            isHoldingBeforeAfter -> "BEFORE • ORIGINAL"
            !engine.isMasterEnabled -> "BYPASS • OFF"
            else -> activePresetName.uppercase()
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isHoldingBeforeAfter) {
                        SynkaPalette.SindoorCrimson.copy(alpha = 0.92f)
                    } else {
                        SynkaPalette.ObsidianCharcoal.copy(alpha = 0.76f)
                    }
                )
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

        // Bottom-left Before/After hint
        if (!isHoldingBeforeAfter) {
            Text(
                text = "Hold for Before/After",
                style = MaterialTheme.typography.labelSmall,
                color = colors.primaryText.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        // Top-right Edit button (SAF picker for permanent test photo or video)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.surface.copy(alpha = 0.92f))
                .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                .clickable(onClick = onEditVisualMedia)
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Half-Height Audio Test Deck (48-Bar Waveform + Seek + Play/Pause + Edit)
// -----------------------------------------------------------------------------

@Composable
private fun PinnedAudioTestDeck(
    engine: SynkaEngine,
    onEditAudioMedia: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val hasPlayableAudio = engine.audioUri != null || (engine.visualUri != null && engine.visualIsVideo)

    val waveformBars = remember { FloatArray(SvaraAudio.WAVEFORM_BARS) }
    var frameTick by remember { mutableIntStateOf(0) }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine.svaraAudio, engine.isAudioPlaying) {
        while (true) {
            if (engine.isAudioPlaying) {
                engine.svaraAudio.readWaveform(waveformBars)
                frameTick++
                delay(33L)
            } else {
                var active = false
                var i = 0
                while (i < waveformBars.size) {
                    val decayed = waveformBars[i] * 0.74f
                    waveformBars[i] = if (decayed < 0.006f) 0f else decayed
                    if (waveformBars[i] > 0f) active = true
                    i++
                }
                if (active) {
                    frameTick++
                    delay(33L)
                } else {
                    delay(120L)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top strip: Track Title + Corner Edit Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (hasPlayableAudio) {
                    engine.audioTitle.ifBlank { "Test Audio Track" }
                } else {
                    "No audio loaded • Tap Edit to pick track"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (hasPlayableAudio) colors.primaryText else colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface)
                    .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable(onClick = onEditAudioMedia)
                    .padding(horizontal = 11.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Edit",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )
            }
        }

        // Real-time 48-bar frequency waveform canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            @Suppress("UNUSED_VARIABLE")
            val tick = frameTick

            val barCount = SvaraAudio.WAVEFORM_BARS
            val slotW = size.width / barCount
            val barW = (slotW * 0.64f).coerceAtLeast(1.8f)
            val gapX = (slotW - barW) * 0.5f
            val midY = size.height * 0.5f
            val corner = CornerRadius(barW * 0.5f, barW * 0.5f)

            drawLine(
                color = colors.hairline,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f
            )

            var i = 0
            while (i < barCount) {
                val mag = waveformBars[i].coerceIn(0f, 1f)
                val barH = (mag * size.height).coerceAtLeast(2.5f)
                val barColor = when {
                    !engine.isMasterEnabled -> colors.secondaryText.copy(alpha = 0.45f)
                    mag > 0.88f -> SynkaPalette.SindoorCrimson
                    else -> colors.activeAccent
                }
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * slotW + gapX, midY - barH * 0.5f),
                    size = Size(barW, barH),
                    cornerRadius = corner
                )
                i++
            }
        }

        // Play / Pause + Seek Bar + Timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (hasPlayableAudio) colors.elevatedSurface else colors.surface)
                    .border(1.dp, colors.hairline, CircleShape)
                    .clickable(enabled = hasPlayableAudio) { engine.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                PlayPauseIcon(
                    isPlaying = engine.isAudioPlaying,
                    color = if (hasPlayableAudio) colors.primaryText else colors.secondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(13.dp)
                )
            }

            val safeDuration = engine.audioDurationMs.coerceAtLeast(1L)
            val displayedPos = if (isScrubbing) {
                scrubPosMs
            } else {
                engine.audioPositionMs.coerceIn(0L, safeDuration)
            }

            Slider(
                value = displayedPos.toFloat(),
                onValueChange = { value ->
                    isScrubbing = true
                    scrubPosMs = value.toLong()
                },
                onValueChangeFinished = {
                    engine.seekTo(scrubPosMs)
                    isScrubbing = false
                },
                enabled = hasPlayableAudio && engine.audioDurationMs > 0L,
                valueRange = 0f..safeDuration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = colors.activeAccentStrong,
                    activeTrackColor = colors.activeAccent,
                    inactiveTrackColor = colors.elevatedSurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
            )

            Text(
                text = formatSheetMillis(displayedPos),
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Genre Pills & Preset List Row (Name | ♡/★ | 🎚️ | ▷)
// -----------------------------------------------------------------------------

@Composable
private fun SheetDomainPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.activeAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) SynkaPalette.ObsidianCharcoal else colors.secondaryText
        )
    }
}

@Composable
private fun DynamicGenrePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.elevatedSurface else colors.background)
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = if (selected) colors.activeAccent else colors.hairline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.activeAccentStrong else colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresetListRow(
    preset: SynkaPreset,
    isActive: Boolean,
    onApply: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenStudio: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isActive) colors.elevatedSurface else colors.background)
            .border(
                width = if (isActive) 1.4.dp else 1.dp,
                color = if (isActive) colors.activeAccent else colors.hairline,
                shape = shape
            )
            .clickable(onClick = onApply)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) colors.activeAccentStrong else colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildString {
                append(preset.domain.name)
                if (preset.isUserCreated) append(" • USER")
                if (isActive) append(" • ACTIVE")
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) colors.activeAccent else colors.secondaryText,
                maxLines = 1
            )
        }

        // Favorite Star / Heart Toggle (♡ / ★)
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center
        ) {
            FavoriteStarIcon(
                isFavorite = preset.isFavorite,
                activeColor = colors.activeAccent,
                inactiveColor = colors.secondaryText,
                modifier = Modifier.size(16.dp)
            )
        }

        // Studio Tune Button (🎚️ -> opens ArkaStudio or SvaraStudio with this preset)
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenStudio),
            contentAlignment = Alignment.Center
        ) {
            StudioFaderIcon(
                color = colors.primaryText,
                modifier = Modifier.size(16.dp)
            )
        }

        // Play / Apply Triangle (▷)
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isActive) colors.activeAccent else colors.surface)
                .border(
                    width = 1.dp,
                    color = if (isActive) colors.activeAccent else colors.hairline,
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onApply),
            contentAlignment = Alignment.Center
        ) {
            ApplyTriangleIcon(
                filled = isActive,
                color = if (isActive) SynkaPalette.ObsidianCharcoal else colors.primaryText,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Crisp Studio Row Action Icons (Zero External Icon Dependency)
// -----------------------------------------------------------------------------

@Composable
private fun FavoriteStarIcon(
    isFavorite: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val outerR = size.minDimension * 0.44f
        val innerR = outerR * 0.44f
        val path = Path()

        for (i in 0 until 10) {
            val angleDeg = -90.0 + i * 36.0
            val rad = angleDeg * (PI / 180.0)
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + (r * cos(rad)).toFloat()
            val y = cy + (r * sin(rad)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        if (isFavorite) {
            drawPath(path = path, color = activeColor)
        } else {
            drawPath(
                path = path,
                color = inactiveColor,
                style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
private fun StudioFaderIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = 1.5.dp.toPx()
        val xs = floatArrayOf(0.22f, 0.50f, 0.78f)
        val knobs = floatArrayOf(0.32f, 0.68f, 0.44f)

        for (i in xs.indices) {
            val x = w * xs[i]
            drawLine(
                color = color.copy(alpha = 0.65f),
                start = Offset(x, h * 0.14f),
                end = Offset(x, h * 0.86f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = color,
                radius = 2.4.dp.toPx(),
                center = Offset(x, h * knobs[i])
            )
        }
    }
}

@Composable
private fun ApplyTriangleIcon(
    filled: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.86f)
            close()
        }
        if (filled) {
            drawPath(path = path, color = color)
        } else {
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
            )
        }
    }
}

private fun formatSheetMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
