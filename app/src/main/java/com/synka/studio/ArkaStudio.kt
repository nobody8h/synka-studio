package com.synka.studio

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class SpectrumChannelMode(val label: String) {
    PUNCH("Punch (Sat)"),
    SHADE("Shade (Hue)"),
    BRIGHT("Bright (Lum)")
}

private val ArkaTabTitles = listOf("Spectrum", "Tone EQ", "Mood", "Optics")

@Composable
fun ArkaStudio(
    engine: SynkaEngine,
    activeDisplayGenre: DisplayGenre?,
    onEditVisualMedia: () -> Unit,
    onResetToKora: () -> Unit,
    onPresetSaved: (SynkaPreset) -> Unit = {},
    isPreviewActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current
    val arka = engine.activeArka

    var selectedTab by remember { mutableIntStateOf(0) }
    var spectrumMode by remember { mutableStateOf(SpectrumChannelMode.PUNCH) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top ~35%: Shared Live Visual Grading Preview Header
        VisualStudioHeader(
            engine = engine,
            onEditMedia = onEditVisualMedia,
            onSavePreset = { presetName ->
                val resolvedGenre = activeDisplayGenre ?: inferDisplayGenre(arka)
                val saved = engine.saveUserPreset(
                    name = presetName,
                    domain = PresetDomain.DISPLAY,
                    displayGenre = resolvedGenre,
                    arka = arka
                )
                onPresetSaved(saved)
                Toast.makeText(context, "Saved \"${saved.name}\"", Toast.LENGTH_SHORT).show()
            },
            onReset = {
                onResetToKora()
            },
            isPreviewActive = isPreviewActive,
            saveDialogTitle = "Save Display Preset",
            defaultSaveName = engine.findPresetById(engine.activePresetId)
                ?.takeIf { it.isUserCreated }
                ?.name
                .orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
        )

        // Bottom ~65%: Non-Scrolling Connected 4-Tab Workspace
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f)
        ) {
            ConnectedFolderTabs(
                tabs = ArkaTabTitles,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            ConnectedFolderBody(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> ColorSpectrumTab(
                        arka = arka,
                        mode = spectrumMode,
                        onModeChange = { spectrumMode = it },
                        onUpdateArka = { engine.updateArka(it) }
                    )
                    1 -> ToneEqTab(
                        arka = arka,
                        onUpdateArka = { engine.updateArka(it) }
                    )
                    2 -> CinematicMoodTab(
                        arka = arka,
                        onUpdateArka = { engine.updateArka(it) }
                    )
                    else -> ComfortOpticsTab(
                        arka = arka,
                        onUpdateArka = { engine.updateArka(it) }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Tab 1 — Color Spectrum (8-Band Light Mixer + [S]/[M] Circular Nodes)
// -----------------------------------------------------------------------------

@Composable
private fun ColorSpectrumTab(
    arka: ArkaParams,
    mode: SpectrumChannelMode,
    onModeChange: (SpectrumChannelMode) -> Unit,
    onUpdateArka: (ArkaParams) -> Unit
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current
    val isArtisan = colors.mode == SynkaThemeMode.ARTISAN_EDITION

    val bands = remember(arka.bands) {
        List(ColorBand.entries.size) { index ->
            arka.bands.getOrElse(index) { BandGrade() }
        }
    }
    val hasAnySolo = bands.any { it.solo }
    val hasAnySoloOrMute = bands.any { it.solo || it.mute }

    fun updateBand(index: Int, transform: (BandGrade) -> BandGrade) {
        val nextBands = List(ColorBand.entries.size) { i ->
            val existing = bands[i]
            if (i == index) transform(existing).clamped() else existing
        }
        onUpdateArka(arka.copy(bands = nextBands))
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 3-way pill switch [ Punch (Sat) | Shade (Hue) | Bright (Lum) ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.background)
                .border(1.dp, colors.hairline, RoundedCornerShape(50))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpectrumChannelMode.entries.forEach { entry ->
                val selected = mode == entry
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.activeAccent else Color.Transparent)
                        .clickable { onModeChange(entry) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) SynkaPalette.ObsidianCharcoal else colors.secondaryText,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Compact node-cycle legend + one-tap Clear S/M action when active
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Tap node: Normal → S → M  •  Double-tap rail to zero",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = colors.secondaryText
            )

            if (hasAnySoloOrMute) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable {
                            val cleared = bands.map { it.copy(solo = false, mute = false) }
                            onUpdateArka(arka.copy(bands = cleared))
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Clear S/M",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.activeAccentStrong
                    )
                }
            }
        }

        // 8 Vertical StudioVerticalFader Rails (R, O, Y, G, C, B, P, M)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val railHeight = (maxHeight - 72.dp).coerceIn(88.dp, 204.dp)

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ColorBand.entries.forEachIndexed { idx, colorBand ->
                    val bandGrade = bands[idx]
                    val swatch = colorBandSwatch(colorBand)
                    val isDimmedByRouting = bandGrade.mute || (hasAnySolo && !bandGrade.solo)
                    val railAccent = if (isDimmedByRouting) {
                        swatch.copy(alpha = 0.35f)
                    } else {
                        swatch
                    }

                    val channelValue = when (mode) {
                        SpectrumChannelMode.PUNCH -> bandGrade.sat
                        SpectrumChannelMode.SHADE -> bandGrade.hue
                        SpectrumChannelMode.BRIGHT -> bandGrade.lum
                    }
                    val channelRange = when (mode) {
                        SpectrumChannelMode.PUNCH -> -100f..100f
                        SpectrumChannelMode.SHADE -> -30f..30f
                        SpectrumChannelMode.BRIGHT -> -100f..100f
                    }
                    val formatter: (Float) -> String = when (mode) {
                        SpectrumChannelMode.SHADE -> ::formatHueDegrees
                        else -> ::formatStudioIntegerReadout
                    }

                    StudioVerticalFader(
                        value = channelValue,
                        onValueChange = { updated ->
                            updateBand(idx) { b ->
                                when (mode) {
                                    SpectrumChannelMode.PUNCH -> b.copy(sat = updated)
                                    SpectrumChannelMode.SHADE -> b.copy(hue = updated)
                                    SpectrumChannelMode.BRIGHT -> b.copy(lum = updated)
                                }
                            }
                        },
                        onDoubleTapReset = {
                            updateBand(idx) { b ->
                                when (mode) {
                                    SpectrumChannelMode.PUNCH -> b.copy(sat = 0f)
                                    SpectrumChannelMode.SHADE -> b.copy(hue = 0f)
                                    SpectrumChannelMode.BRIGHT -> b.copy(lum = 0f)
                                }
                            }
                        },
                        label = colorBandInitial(colorBand),
                        valueRange = channelRange,
                        defaultValue = 0f,
                        trackHeight = railHeight,
                        accentColor = railAccent,
                        valueFormatter = formatter,
                        modifier = Modifier.weight(1f),
                        topNode = {
                            SpectrumBandNode(
                                swatch = swatch,
                                isSolo = bandGrade.solo,
                                isMute = bandGrade.mute,
                                isOtherSoloActive = hasAnySolo && !bandGrade.solo,
                                isArtisanMode = isArtisan,
                                onTapCycle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    updateBand(idx) { b ->
                                        when {
                                            !b.solo && !b.mute -> b.copy(solo = true, mute = false)
                                            b.solo -> b.copy(solo = false, mute = true)
                                            else -> b.copy(solo = false, mute = false)
                                        }
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpectrumBandNode(
    swatch: Color,
    isSolo: Boolean,
    isMute: Boolean,
    isOtherSoloActive: Boolean,
    isArtisanMode: Boolean,
    onTapCycle: () -> Unit
) {
    val soloHighlight = if (isArtisanMode) SynkaPalette.KansaBronze else SynkaPalette.SindoorCrimson

    val fillColor = when {
        isSolo -> soloHighlight
        isMute -> SynkaPalette.ObsidianCharcoal
        isOtherSoloActive -> swatch.copy(alpha = 0.28f)
        else -> swatch
    }
    val ringColor = when {
        isSolo -> Color.White.copy(alpha = 0.90f)
        isMute -> SynkaPalette.MutedSilver.copy(alpha = 0.45f)
        isOtherSoloActive -> swatch.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.24f)
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(fillColor)
            .border(
                width = if (isSolo) 2.dp else 1.2.dp,
                color = ringColor,
                shape = CircleShape
            )
            .clickable(onClick = onTapCycle),
        contentAlignment = Alignment.Center
    ) {
        when {
            isSolo -> {
                Text(
                    text = "S",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isArtisanMode) SynkaPalette.ObsidianCharcoal else Color.White
                )
            }
            isMute -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = SynkaPalette.MutedSilver.copy(alpha = 0.45f),
                        start = Offset(size.width * 0.22f, size.height * 0.78f),
                        end = Offset(size.width * 0.78f, size.height * 0.22f),
                        strokeWidth = 1.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                Text(
                    text = "M",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SynkaPalette.MutedSilver
                )
            }
            else -> {
                // Clean matte center specular pip indicating neutral state
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOtherSoloActive) {
                                SynkaPalette.ObsidianCharcoal.copy(alpha = 0.55f)
                            } else {
                                SynkaPalette.ObsidianCharcoal.copy(alpha = 0.30f)
                            }
                        )
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Tab 2 — Tone EQ (5-Zone Luminance Faders: Black, Shadow, Mids, High, White)
// -----------------------------------------------------------------------------

@Composable
private fun ToneEqTab(
    arka: ArkaParams,
    onUpdateArka: (ArkaParams) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "5-ZONE LUMINANCE CONTOUR",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )
            Text(
                text = "Double-tap to zero",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = colors.secondaryText
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val railHeight = (maxHeight - 74.dp).coerceIn(96.dp, 224.dp)

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StudioVerticalFader(
                    value = arka.blacks,
                    onValueChange = { onUpdateArka(arka.copy(blacks = it)) },
                    onDoubleTapReset = { onUpdateArka(arka.copy(blacks = 0f)) },
                    label = "Black",
                    valueRange = -100f..100f,
                    trackHeight = railHeight,
                    modifier = Modifier.weight(1f),
                    topNode = { ToneZoneSwatchDot(Color(0xFF1A1A1D)) }
                )

                StudioVerticalFader(
                    value = arka.shadows,
                    onValueChange = { onUpdateArka(arka.copy(shadows = it)) },
                    onDoubleTapReset = { onUpdateArka(arka.copy(shadows = 0f)) },
                    label = "Shadow",
                    valueRange = -100f..100f,
                    trackHeight = railHeight,
                    modifier = Modifier.weight(1f),
                    topNode = { ToneZoneSwatchDot(Color(0xFF47474D)) }
                )

                StudioVerticalFader(
                    value = arka.mids,
                    onValueChange = { onUpdateArka(arka.copy(mids = it)) },
                    onDoubleTapReset = { onUpdateArka(arka.copy(mids = 0f)) },
                    label = "Mids",
                    valueRange = -100f..100f,
                    trackHeight = railHeight,
                    modifier = Modifier.weight(1f),
                    topNode = { ToneZoneSwatchDot(Color(0xFF82828A)) }
                )

                StudioVerticalFader(
                    value = arka.highlights,
                    onValueChange = { onUpdateArka(arka.copy(highlights = it)) },
                    onDoubleTapReset = { onUpdateArka(arka.copy(highlights = 0f)) },
                    label = "High",
                    valueRange = -100f..100f,
                    trackHeight = railHeight,
                    modifier = Modifier.weight(1f),
                    topNode = { ToneZoneSwatchDot(Color(0xFFC2C2C8)) }
                )

                StudioVerticalFader(
                    value = arka.whites,
                    onValueChange = { onUpdateArka(arka.copy(whites = it)) },
                    onDoubleTapReset = { onUpdateArka(arka.copy(whites = 0f)) },
                    label = "White",
                    valueRange = -100f..100f,
                    trackHeight = railHeight,
                    modifier = Modifier.weight(1f),
                    topNode = { ToneZoneSwatchDot(Color(0xFFF2F2F5)) }
                )
            }
        }
    }
}

@Composable
private fun ToneZoneSwatchDot(fill: Color) {
    val colors = LocalSynkaColors.current
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, colors.primaryText.copy(alpha = 0.28f), CircleShape)
    )
}

// -----------------------------------------------------------------------------
// Tab 3 — Cinematic Mood (Dual YantraGlobes + Warmth & Tint Sliders)
// -----------------------------------------------------------------------------

@Composable
private fun CinematicMoodTab(
    arka: ArkaParams,
    onUpdateArka: (ArkaParams) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Side-by-side Shadow Tint & Highlight Tint YantraGlobes sized cleanly to remaining space
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val maxPerGlobeW = (maxWidth - 18.dp) / 2f
            val maxPerGlobeH = (maxHeight - 40.dp).coerceAtLeast(88.dp)
            val globeWidth = minOf(maxPerGlobeW, maxPerGlobeH).coerceAtLeast(88.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                YantraGlobe(
                    label = "Shadow Tint",
                    x = arka.shadowTintX,
                    y = arka.shadowTintY,
                    onChange = { nx, ny ->
                        onUpdateArka(arka.copy(shadowTintX = nx, shadowTintY = ny))
                    },
                    onDoubleTapReset = {
                        onUpdateArka(arka.copy(shadowTintX = 0f, shadowTintY = 0f))
                    },
                    modifier = Modifier.width(globeWidth)
                )

                YantraGlobe(
                    label = "Highlight Tint",
                    x = arka.highlightTintX,
                    y = arka.highlightTintY,
                    onChange = { nx, ny ->
                        onUpdateArka(arka.copy(highlightTintX = nx, highlightTintY = ny))
                    },
                    onDoubleTapReset = {
                        onUpdateArka(arka.copy(highlightTintX = 0f, highlightTintY = 0f))
                    },
                    modifier = Modifier.width(globeWidth)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        // Compact Warmth & Tint Horizontal Sliders
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StudioHorizontalSlider(
                label = "Warmth",
                value = arka.warmth,
                onValueChange = { onUpdateArka(arka.copy(warmth = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(warmth = 0f)) },
                valueRange = -100f..100f,
                accentColor = SynkaPalette.KansaBronze
            )

            StudioHorizontalSlider(
                label = "Tint",
                value = arka.tint,
                onValueChange = { onUpdateArka(arka.copy(tint = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(tint = 0f)) },
                valueRange = -100f..100f,
                accentColor = Color(0xFFD648A6)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Tab 4 — Comfort & Optics (Subzero Dimmer, Paper, Split R / G / B Trims)
// -----------------------------------------------------------------------------

@Composable
private fun ComfortOpticsTab(
    arka: ArkaParams,
    onUpdateArka: (ArkaParams) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StudioHorizontalSlider(
                label = "Subzero Dimmer",
                value = arka.subzeroDimmer,
                onValueChange = { onUpdateArka(arka.copy(subzeroDimmer = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(subzeroDimmer = 0f)) },
                valueRange = 0f..100f,
                defaultValue = 0f,
                accentColor = colors.activeAccent,
                valueFormatter = ::formatPercentReadout
            )

            StudioHorizontalSlider(
                label = "Paper",
                value = arka.paper,
                onValueChange = { onUpdateArka(arka.copy(paper = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(paper = 0f)) },
                valueRange = 0f..100f,
                defaultValue = 0f,
                accentColor = SynkaPalette.KansaBronze,
                valueFormatter = ::formatPercentReadout
            )
        }

        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        // Compact Split R / G / B White-Balance Trim Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RGB OPTICS TRIM",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )
                if (arka.trimR != 0f || arka.trimG != 0f || arka.trimB != 0f) {
                    Text(
                        text = "Reset RGB",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.activeAccent,
                        modifier = Modifier.clickable {
                            onUpdateArka(arka.copy(trimR = 0f, trimG = 0f, trimB = 0f))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            StudioHorizontalSlider(
                label = "R  Red Trim",
                value = arka.trimR,
                onValueChange = { onUpdateArka(arka.copy(trimR = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(trimR = 0f)) },
                valueRange = -50f..50f,
                defaultValue = 0f,
                accentColor = Color(0xFFE63946)
            )

            StudioHorizontalSlider(
                label = "G  Green Trim",
                value = arka.trimG,
                onValueChange = { onUpdateArka(arka.copy(trimG = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(trimG = 0f)) },
                valueRange = -50f..50f,
                defaultValue = 0f,
                accentColor = Color(0xFF42B860)
            )

            StudioHorizontalSlider(
                label = "B  Blue Trim",
                value = arka.trimB,
                onValueChange = { onUpdateArka(arka.copy(trimB = it)) },
                onDoubleTapReset = { onUpdateArka(arka.copy(trimB = 0f)) },
                valueRange = -50f..50f,
                defaultValue = 0f,
                accentColor = Color(0xFF4078E0)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Arka Visual Mapping & Formatting Helpers
// -----------------------------------------------------------------------------

private fun colorBandInitial(band: ColorBand): String = when (band) {
    ColorBand.RED -> "R"
    ColorBand.ORANGE -> "O"
    ColorBand.YELLOW -> "Y"
    ColorBand.GREEN -> "G"
    ColorBand.CYAN -> "C"
    ColorBand.BLUE -> "B"
    ColorBand.PURPLE -> "P"
    ColorBand.MAGENTA -> "M"
}

private fun colorBandSwatch(band: ColorBand): Color = when (band) {
    ColorBand.RED -> Color(0xFFE63946)
    ColorBand.ORANGE -> Color(0xFFEB842E)
    ColorBand.YELLOW -> Color(0xFFE8CC3A)
    ColorBand.GREEN -> Color(0xFF42B860)
    ColorBand.CYAN -> Color(0xFF34BCC4)
    ColorBand.BLUE -> Color(0xFF4078E0)
    ColorBand.PURPLE -> Color(0xFF8A56D6)
    ColorBand.MAGENTA -> Color(0xFFD648A6)
}

private fun formatHueDegrees(deg: Float): String {
    val rounded = deg.roundToInt()
    return when {
        rounded > 0 -> "+${rounded}°"
        rounded < 0 -> "${rounded}°"
        else -> "0°"
    }
}

private fun formatPercentReadout(percent: Float): String = "${percent.roundToInt()}%"

private fun inferDisplayGenre(arka: ArkaParams): DisplayGenre {
    val allDesaturated = arka.bands.isNotEmpty() && arka.bands.all { it.sat <= -85f }
    if (allDesaturated) return DisplayGenre.BW

    val redBand = arka.bands.getOrNull(ColorBand.RED.ordinal)
    if (redBand != null && redBand.solo) return DisplayGenre.RED_ISO

    if (arka.warmth >= 22f || arka.paper >= 18f) return DisplayGenre.WARM

    return DisplayGenre.CINEMA_GRADE
}
