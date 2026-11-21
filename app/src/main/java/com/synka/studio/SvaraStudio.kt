package com.synka.studio

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SvaraTabTitles = listOf("10-Band", "Contour", "Globes", "Safety")

@Composable
fun SvaraStudio(
    engine: SynkaEngine,
    activeAudioGenre: AudioGenre?,
    onEditAudioMedia: () -> Unit,
    onResetAudioToFlat: () -> Unit,
    onPresetSaved: (SynkaPreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current
    val svara = engine.activeSvara

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedBandIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AudioStudioHeader(
            engine = engine,
            onEditMedia = onEditAudioMedia,
            onSavePreset = { presetName ->
                val resolvedGenre = activeAudioGenre ?: inferAudioGenre(svara)
                val saved = engine.saveUserPreset(
                    name = presetName,
                    domain = PresetDomain.AUDIO,
                    audioGenre = resolvedGenre,
                    svara = svara
                )
                onPresetSaved(saved)
                Toast.makeText(context, "Saved \"${saved.name}\"", Toast.LENGTH_SHORT).show()
            },
            onReset = {
                onResetAudioToFlat()
            },
            saveDialogTitle = "Save Audio Preset",
            defaultSaveName = engine.findPresetById(engine.activePresetId)
                ?.takeIf { it.isUserCreated }
                ?.name
                .orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f)
        ) {
            ConnectedFolderTabs(
                tabs = SvaraTabTitles,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            ConnectedFolderBody(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> ParametricEqTab(
                        svara = svara,
                        selectedBandIndex = selectedBandIndex,
                        onSelectBand = { selectedBandIndex = it },
                        onUpdateSvara = { engine.updateSvara(it) }
                    )
                    1 -> SoundContourTab(
                        svara = svara,
                        onUpdateSvara = { engine.updateSvara(it) }
                    )
                    2 -> DualAudioGlobesTab(
                        svara = svara,
                        onUpdateSvara = { engine.updateSvara(it) }
                    )
                    else -> CinemaSafetyTab(
                        svara = svara,
                        onUpdateSvara = { engine.updateSvara(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ParametricEqTab(
    svara: SvaraParams,
    selectedBandIndex: Int,
    onSelectBand: (Int) -> Unit,
    onUpdateSvara: (SvaraParams) -> Unit
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    val bands = remember(svara.bands) {
        val defaults = SvaraParams.DEFAULT_FREQUENCIES_HZ
        List(defaults.size) { index ->
            svara.bands.getOrElse(index) { EqBand(freqHz = defaults[index]) }.clamped()
        }
    }

    val safeIndex = selectedBandIndex.coerceIn(0, bands.lastIndex)
    val activeBand = bands[safeIndex]

    fun updateBand(index: Int, transform: (EqBand) -> EqBand) {
        val nextBands = List(bands.size) { i ->
            if (i == index) transform(bands[i]).clamped() else bands[i]
        }
        onUpdateSvara(svara.copy(bands = nextBands))
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectedBandInspectorBar(
            bandIndex = safeIndex,
            band = activeBand,
            onStepFreq = { deltaSign ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                updateBand(safeIndex) { b ->
                    val nextFreq = stepBandFrequency(b.freqHz, deltaSign)
                    b.copy(freqHz = nextFreq)
                }
            },
            onStepQ = { deltaSign ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                updateBand(safeIndex) { b ->
                    val nextQ = stepBandQ(b.q, deltaSign)
                    b.copy(q = nextQ)
                }
            },
            onResetBand = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val defaultFreq = SvaraParams.DEFAULT_FREQUENCIES_HZ[safeIndex]
                updateBand(safeIndex) {
                    EqBand(freqHz = defaultFreq, q = 1.4f, gainDb = 0f)
                }
            }
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val railHeight = (maxHeight - 80.dp).coerceIn(84.dp, 200.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ParametricEqVerticalFader(
                        value = svara.preAmpDb,
                        onValueChange = { onUpdateSvara(svara.copy(preAmpDb = it)) },
                        onDoubleTapReset = { onUpdateSvara(svara.copy(preAmpDb = 0f)) },
                        label = "Pre",
                        valueRange = -12f..12f,
                        trackHeight = railHeight,
                        accentColor = if (svara.preAmpDb > 6f) SynkaPalette.SindoorCrimson else colors.activeAccent,
                        yieldHorizontalScroll = false,
                        modifier = Modifier.width(46.dp),
                        topNode = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(colors.elevatedSurface)
                                    .border(1.dp, colors.hairline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "dB",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.secondaryText
                                )
                            }
                        }
                    )

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.hairline)
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(scrollState),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        bands.forEachIndexed { idx, band ->
                            val isSelected = idx == safeIndex
                            val defaultFreq = SvaraParams.DEFAULT_FREQUENCIES_HZ[idx]
                            val isTuned = abs(band.freqHz - defaultFreq) >= 1f || abs(band.q - 1.4f) >= 0.05f

                            ParametricEqVerticalFader(
                                value = band.gainDb,
                                onValueChange = { newGain ->
                                    if (safeIndex != idx) onSelectBand(idx)
                                    updateBand(idx) { it.copy(gainDb = newGain) }
                                },
                                onDoubleTapReset = {
                                    if (safeIndex != idx) onSelectBand(idx)
                                    updateBand(idx) { it.copy(gainDb = 0f) }
                                },
                                label = formatBandShortFreq(band.freqHz),
                                valueRange = -18f..18f,
                                trackHeight = railHeight,
                                accentColor = if (isSelected) colors.activeAccentStrong else colors.activeAccent,
                                isHighlighted = isSelected,
                                yieldHorizontalScroll = true,
                                modifier = Modifier.width(48.dp),
                                topNode = {
                                    BandSelectionNode(
                                        selected = isSelected,
                                        hasCustomFreqOrQ = isTuned,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelectBand(idx)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                if (scrollState.maxValue > 0) {
                    val progress = (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                        ) {
                            drawRoundRect(
                                color = colors.hairline,
                                size = size,
                                cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                            )
                            val thumbW = (size.width * 0.45f).coerceAtLeast(24.dp.toPx())
                            val thumbX = (size.width - thumbW) * progress
                            drawRoundRect(
                                color = colors.activeAccent.copy(alpha = 0.8f),
                                topLeft = Offset(thumbX, 0f),
                                size = Size(thumbW, size.height),
                                cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                            )
                        }
                        Text(
                            text = "31Hz ↔ 16kHz",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = colors.secondaryText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedBandInspectorBar(
    bandIndex: Int,
    band: EqBand,
    onStepFreq: (Int) -> Unit,
    onStepQ: (Int) -> Unit,
    onResetBand: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.elevatedSurface)
                .border(1.dp, colors.activeAccent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "B${bandIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = colors.activeAccentStrong
            )
        }

        CompactStepperPill(
            valueText = formatBandInspectorFreq(band.freqHz),
            onDecrement = { onStepFreq(-1) },
            onIncrement = { onStepFreq(+1) },
            modifier = Modifier.weight(1.18f)
        )

        CompactStepperPill(
            valueText = "Q ${String.format(Locale.US, "%.1f", band.q)}",
            onDecrement = { onStepQ(-1) },
            onIncrement = { onStepQ(+1) },
            modifier = Modifier.weight(0.96f)
        )

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.elevatedSurface)
                .border(1.dp, colors.hairline, RoundedCornerShape(9.dp))
                .clickable(onClick = onResetBand),
            contentAlignment = Alignment.Center
        ) {
            BandResetArcIcon(
                color = colors.primaryText,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun CompactStepperPill(
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalSynkaColors.current
    val shape = RoundedCornerShape(9.dp)

    Row(
        modifier = modifier
            .height(30.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight()
                .clickable(onClick = onDecrement),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight()
                .clickable(onClick = onIncrement),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )
        }
    }
}

@Composable
private fun BandSelectionNode(
    selected: Boolean,
    hasCustomFreqOrQ: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.activeAccent else colors.background)
            .border(
                width = if (selected) 1.6.dp else 1.2.dp,
                color = when {
                    selected -> colors.activeAccentStrong
                    hasCustomFreqOrQ -> colors.activeAccent.copy(alpha = 0.7f)
                    else -> colors.hairline
                },
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(SynkaPalette.ObsidianCharcoal)
            )
        } else if (hasCustomFreqOrQ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(colors.activeAccent)
            )
        }
    }
}

@Composable
private fun ParametricEqVerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    label: String,
    valueRange: ClosedFloatingPointRange<Float>,
    trackHeight: Dp,
    accentColor: Color,
    yieldHorizontalScroll: Boolean,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    topNode: (@Composable () -> Unit)? = null
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current

    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(valueRange)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnReset by rememberUpdatedState(onDoubleTapReset)

    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isHighlighted) colors.elevatedSurface.copy(alpha = 0.55f) else Color.Transparent
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (topNode != null) {
            Box(
                modifier = Modifier.height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                topNode()
            }
        }

        Text(
            text = formatStudioIntegerReadout(clampedValue),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 10.sp,
            fontWeight = if (abs(clampedValue) > 0.05f) FontWeight.Bold else FontWeight.Medium,
            color = if (abs(clampedValue) > 0.05f) accentColor else colors.secondaryText,
            maxLines = 1
        )

        Canvas(
            modifier = Modifier
                .width(42.dp)
                .height(trackHeight)
                .pointerInput(yieldHorizontalScroll) {
                    var lastTapUpMs = 0L
                    var lastTapPos = Offset.Zero
                    val dragSlopPx = 4.dp.toPx()
                    val padPx = 11.dp.toPx()
                    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                    val doubleTapSlopPx = viewConfiguration.touchSlop * 2.5f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val downPos = down.position
                        var isVerticalDrag = false
                        var lastEmitted = currentValue
                        val span = (currentRange.endInclusive - currentRange.start).coerceAtLeast(0.0001f)
                        val snapBand = span * 0.015f
                        var prevSign = eqSignBucket(lastEmitted, snapBand * 0.5f)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                if (!isVerticalDrag) {
                                    val now = SystemClock.uptimeMillis()
                                    val isDoubleTap = lastTapUpMs > 0L &&
                                        (now - lastTapUpMs) <= doubleTapTimeoutMs &&
                                        (downPos - lastTapPos).getDistance() <= doubleTapSlopPx
                                    if (isDoubleTap) {
                                        change.consume()
                                        lastTapUpMs = 0L
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnReset()
                                    } else {
                                        lastTapUpMs = now
                                        lastTapPos = downPos
                                    }
                                }
                                break
                            }

                            val dx = abs(change.position.x - downPos.x)
                            val dy = abs(change.position.y - downPos.y)

                            if (!isVerticalDrag) {
                                if (yieldHorizontalScroll && dx > viewConfiguration.touchSlop * 0.75f && dx > dy * 1.2f) {
                                    break
                                }
                                if (dy > dragSlopPx && (!yieldHorizontalScroll || dy >= dx * 0.8f)) {
                                    isVerticalDrag = true
                                    lastTapUpMs = 0L
                                }
                            }

                            if (isVerticalDrag) {
                                change.consume()
                                val usableH = (size.height - padPx * 2f).coerceAtLeast(1f)
                                val frac = (1f - ((change.position.y - padPx) / usableH)).coerceIn(0f, 1f)
                                var mapped = currentRange.start + frac * span
                                if (abs(mapped) <= snapBand) {
                                    mapped = 0f
                                }
                                mapped = mapped.coerceIn(currentRange.start, currentRange.endInclusive)

                                val nextSign = eqSignBucket(mapped, snapBand * 0.5f)
                                if (nextSign != prevSign && (nextSign == 0 || (prevSign != 0 && prevSign == -nextSign))) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                prevSign = nextSign

                                if (mapped != lastEmitted) {
                                    lastEmitted = mapped
                                    currentOnChange(mapped)
                                }
                            }
                        }
                    }
                }
        ) {
            val padY = 11.dp.toPx()
            val usableH = (size.height - padY * 2f).coerceAtLeast(1f)
            val centerX = size.width * 0.5f
            val railW = 5.5.dp.toPx()
            val railLeft = centerX - railW * 0.5f

            val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
            val normVal = ((clampedValue - valueRange.start) / span).coerceIn(0f, 1f)
            val thumbY = padY + (1f - normVal) * usableH

            val normZero = ((0f - valueRange.start) / span).coerceIn(0f, 1f)
            val zeroY = padY + (1f - normZero) * usableH

            drawLine(
                color = colors.secondaryText.copy(alpha = 0.48f),
                start = Offset(centerX - 12.dp.toPx(), zeroY),
                end = Offset(centerX + 12.dp.toPx(), zeroY),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            drawRoundRect(
                color = SynkaPalette.ObsidianCharcoal,
                topLeft = Offset(railLeft, padY),
                size = Size(railW, usableH),
                cornerRadius = CornerRadius(railW * 0.5f, railW * 0.5f)
            )
            drawRoundRect(
                color = colors.hairline,
                topLeft = Offset(railLeft, padY),
                size = Size(railW, usableH),
                cornerRadius = CornerRadius(railW * 0.5f, railW * 0.5f),
                style = Stroke(width = 1.dp.toPx())
            )

            val fillTop = minOf(zeroY, thumbY)
            val fillBottom = maxOf(zeroY, thumbY)
            val fillHeight = (fillBottom - fillTop).coerceAtLeast(0f)
            if (fillHeight > 0.5f) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(railLeft, fillTop),
                    size = Size(railW, fillHeight),
                    cornerRadius = CornerRadius(railW * 0.5f, railW * 0.5f)
                )
            }

            val capW = 22.dp.toPx()
            val capH = 13.dp.toPx()
            val capLeft = centerX - capW * 0.5f
            val capTop = thumbY - capH * 0.5f

            drawRoundRect(
                color = colors.elevatedSurface,
                topLeft = Offset(capLeft, capTop),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(4.5.dp.toPx(), 4.5.dp.toPx())
            )
            drawRoundRect(
                color = if (abs(clampedValue) > 0.05f || isHighlighted) {
                    accentColor
                } else {
                    colors.primaryText.copy(alpha = 0.45f)
                },
                topLeft = Offset(capLeft, capTop),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(4.5.dp.toPx(), 4.5.dp.toPx()),
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawLine(
                color = colors.primaryText.copy(alpha = 0.85f),
                start = Offset(capLeft + 4.5.dp.toPx(), thumbY),
                end = Offset(capLeft + capW - 4.5.dp.toPx(), thumbY),
                strokeWidth = 1.3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isHighlighted) colors.activeAccentStrong else colors.primaryText,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun SoundContourTab(
    svara: SvaraParams,
    onUpdateSvara: (SvaraParams) -> Unit
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
                text = "5-ZONE SOUND CONTOUR (dB)",
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
            val railHeight = (maxHeight - 78.dp).coerceIn(92.dp, 220.dp)

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StudioVerticalFader(
                    value = svara.su,
                    onValueChange = { onUpdateSvara(svara.copy(su = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(su = 0f)) },
                    label = "SU",
                    subLabel = "Sub-Rumble",
                    valueRange = -12f..12f,
                    trackHeight = railHeight,
                    valueFormatter = ::formatStudioDecimalReadout,
                    modifier = Modifier.weight(1f)
                )

                StudioVerticalFader(
                    value = svara.mi,
                    onValueChange = { onUpdateSvara(svara.copy(mi = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(mi = 0f)) },
                    label = "MI",
                    subLabel = "Mid-Bass",
                    valueRange = -12f..12f,
                    trackHeight = railHeight,
                    valueFormatter = ::formatStudioDecimalReadout,
                    modifier = Modifier.weight(1f)
                )

                StudioVerticalFader(
                    value = svara.vo,
                    onValueChange = { onUpdateSvara(svara.copy(vo = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(vo = 0f)) },
                    label = "VO",
                    subLabel = "Vocals",
                    valueRange = -12f..12f,
                    trackHeight = railHeight,
                    valueFormatter = ::formatStudioDecimalReadout,
                    modifier = Modifier.weight(1f)
                )

                StudioVerticalFader(
                    value = svara.tr,
                    onValueChange = { onUpdateSvara(svara.copy(tr = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(tr = 0f)) },
                    label = "TR",
                    subLabel = "Treble",
                    valueRange = -12f..12f,
                    trackHeight = railHeight,
                    valueFormatter = ::formatStudioDecimalReadout,
                    modifier = Modifier.weight(1f)
                )

                StudioVerticalFader(
                    value = svara.ai,
                    onValueChange = { onUpdateSvara(svara.copy(ai = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(ai = 0f)) },
                    label = "AI",
                    subLabel = "Air",
                    valueRange = -12f..12f,
                    trackHeight = railHeight,
                    valueFormatter = ::formatStudioDecimalReadout,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DualAudioGlobesTab(
    svara: SvaraParams,
    onUpdateSvara: (SvaraParams) -> Unit
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
                text = "DUAL ACOUSTIC GLOBES",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )
            Text(
                text = "Double-tap globe to recenter",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = colors.secondaryText
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val maxPerGlobeW = (maxWidth - 18.dp) / 2f
            val maxPerGlobeH = (maxHeight - 54.dp).coerceAtLeast(92.dp)
            val globeWidth = minOf(maxPerGlobeW, maxPerGlobeH).coerceAtLeast(92.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    YantraGlobe(
                        label = "Bass Punch & Depth",
                        x = svara.bassDepthX,
                        y = svara.bassDepthY,
                        onChange = { nx, ny ->
                            onUpdateSvara(svara.copy(bassDepthX = nx, bassDepthY = ny))
                        },
                        onDoubleTapReset = {
                            onUpdateSvara(svara.copy(bassDepthX = 0f, bassDepthY = 0f))
                        },
                        modifier = Modifier.width(globeWidth)
                    )
                    Text(
                        text = "X Depth  •  Y Punch",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colors.secondaryText
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    YantraGlobe(
                        label = "Soundstage & Air",
                        x = svara.airWidthX,
                        y = svara.airWidthY,
                        onChange = { nx, ny ->
                            onUpdateSvara(svara.copy(airWidthX = nx, airWidthY = ny))
                        },
                        onDoubleTapReset = {
                            onUpdateSvara(svara.copy(airWidthX = 0f, airWidthY = 0f))
                        },
                        modifier = Modifier.width(globeWidth)
                    )
                    Text(
                        text = "X Width  •  Y Air",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun CinemaSafetyTab(
    svara: SvaraParams,
    onUpdateSvara: (SvaraParams) -> Unit
) {
    val colors = LocalSynkaColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CINEMA & EAR SAFETY",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )
            Text(
                text = "Double-tap slider for default",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = colors.secondaryText
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StudioHorizontalSlider(
                    label = "Ni  •  Night Dialogue Boost",
                    value = svara.nightDialogue,
                    onValueChange = { onUpdateSvara(svara.copy(nightDialogue = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(nightDialogue = 0f)) },
                    valueRange = 0f..100f,
                    defaultValue = 0f,
                    accentColor = colors.activeAccent,
                    valueFormatter = ::formatSvaraPercent
                )
                Text(
                    text = "Lifts speech clarity and softens sudden loud peaks for late-night listening.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colors.secondaryText
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StudioHorizontalSlider(
                    label = "Ar  •  Anti-Crackle Limiter",
                    value = svara.antiCrackle,
                    onValueChange = { onUpdateSvara(svara.copy(antiCrackle = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(antiCrackle = 75f)) },
                    valueRange = 0f..100f,
                    defaultValue = 75f,
                    accentColor = SynkaPalette.KansaBronze,
                    valueFormatter = ::formatSvaraPercent
                )
                Text(
                    text = "Soft-knee dynamic ceiling prevents digital clipping under heavy EQ boosts.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colors.secondaryText
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StudioHorizontalSlider(
                    label = "Li  •  L/R Ear Balance",
                    value = svara.lrBalance,
                    onValueChange = { onUpdateSvara(svara.copy(lrBalance = it)) },
                    onDoubleTapReset = { onUpdateSvara(svara.copy(lrBalance = 0f)) },
                    valueRange = -100f..100f,
                    defaultValue = 0f,
                    accentColor = colors.activeAccentStrong,
                    valueFormatter = ::formatLrBalanceReadout
                )
                Text(
                    text = "Compensates for left/right IEM driver variance or ear sensitivity.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colors.secondaryText
                )
            }
        }
    }
}

@Composable
private fun BandResetArcIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = w * 0.16f
        drawArc(
            color = color,
            startAngle = -140f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(w - pad * 2f, h - pad * 2f),
            style = stroke
        )
        val head = Path().apply {
            moveTo(w * 0.14f, h * 0.18f)
            lineTo(w * 0.14f, h * 0.42f)
            lineTo(w * 0.38f, h * 0.42f)
        }
        drawPath(path = head, color = color, style = stroke)
    }
}

private fun stepBandFrequency(currentHz: Float, sign: Int): Float {
    val step = when {
        currentHz < 50f -> 2f
        currentHz < 120f -> 5f
        currentHz < 500f -> 10f
        currentHz < 2000f -> 50f
        currentHz < 6000f -> 100f
        else -> 250f
    }
    val next = currentHz + (step * sign)
    return next.coerceIn(20f, 20000f)
}

private fun stepBandQ(currentQ: Float, sign: Int): Float {
    val step = when {
        currentQ < 3.0f -> 0.1f
        currentQ < 8.0f -> 0.2f
        else -> 0.5f
    }
    val raw = (currentQ + step * sign).coerceIn(0.1f, 20.0f)
    return (raw * 10f).roundToInt() / 10f
}

private fun formatBandInspectorFreq(freqHz: Float): String {
    return if (freqHz >= 1000f) {
        val khz = freqHz / 1000f
        if (abs(khz - khz.roundToInt()) < 0.05f) {
            "${khz.roundToInt()} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", khz)
        }
    } else {
        "${freqHz.roundToInt()} Hz"
    }
}

private fun formatBandShortFreq(freqHz: Float): String {
    return if (freqHz >= 1000f) {
        val khz = freqHz / 1000f
        if (abs(khz - khz.roundToInt()) < 0.05f) {
            "${khz.roundToInt()}k"
        } else {
            String.format(Locale.US, "%.1fk", khz)
        }
    } else {
        "${freqHz.roundToInt()}"
    }
}

private fun formatSvaraPercent(percent: Float): String = "${percent.roundToInt()}%"

private fun formatLrBalanceReadout(balance: Float): String {
    val rounded = balance.roundToInt()
    return when {
        rounded < -1 -> "L ${abs(rounded)}"
        rounded > 1 -> "R $rounded"
        else -> "Center"
    }
}

private fun eqSignBucket(value: Float, epsilon: Float): Int = when {
    value > epsilon -> 1
    value < -epsilon -> -1
    else -> 0
}

private fun inferAudioGenre(svara: SvaraParams): AudioGenre {
    if (svara.nightDialogue >= 35f) return AudioGenre.LATE_NIGHT

    val bassAvg = ((svara.bands.getOrNull(0)?.gainDb ?: 0f) + (svara.bands.getOrNull(1)?.gainDb ?: 0f)) * 0.5f
    if (svara.su >= 3.5f || bassAvg >= 3.5f) return AudioGenre.BASS

    val vocalAvg = ((svara.bands.getOrNull(5)?.gainDb ?: 0f) + (svara.bands.getOrNull(6)?.gainDb ?: 0f)) * 0.5f
    if (svara.vo >= 3.0f || vocalAvg >= 2.5f) return AudioGenre.VOCAL

    return AudioGenre.FLAT
}
