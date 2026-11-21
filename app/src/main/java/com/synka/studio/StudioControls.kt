package com.synka.studio

import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// -----------------------------------------------------------------------------
// 1. ConnectedFolderTabs (+ Companion Seamless Container)
// -----------------------------------------------------------------------------

@Composable
fun ConnectedFolderTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val colors = LocalSynkaColors.current
    val safeSelected = selectedIndex.coerceIn(0, tabs.lastIndex)
    val tabCount = tabs.size

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawBehind

                val tabW = w / tabCount.toFloat()
                val radius = 11.dp.toPx().coerceAtMost(tabW * 0.25f).coerceAtMost(h * 0.45f)
                val strokePx = 1.dp.toPx()
                val halfStroke = strokePx * 0.5f

                val activeLeft = safeSelected * tabW
                val activeRight = activeLeft + tabW

                // Subtle recessed fill behind inactive tabs
                for (i in 0 until tabCount) {
                    if (i == safeSelected) continue
                    val left = i * tabW + 2.dp.toPx()
                    val right = (i + 1) * tabW - 2.dp.toPx()
                    val top = 5.dp.toPx()
                    if (right > left) {
                        val inactivePath = Path().apply {
                            moveTo(left, h)
                            lineTo(left, top + radius * 0.7f)
                            arcTo(
                                rect = Rect(left, top, left + radius * 1.4f, top + radius * 1.4f),
                                startAngleDegrees = 180f,
                                sweepAngleDegrees = 90f,
                                forceMoveTo = false
                            )
                            lineTo(right - radius * 0.7f, top)
                            arcTo(
                                rect = Rect(right - radius * 1.4f, top, right, top + radius * 1.4f),
                                startAngleDegrees = 270f,
                                sweepAngleDegrees = 90f,
                                forceMoveTo = false
                            )
                            lineTo(right, h)
                            close()
                        }
                        drawPath(
                            path = inactivePath,
                            color = colors.background
                        )
                    }
                }

                // Active folder tab fill extending flush into the container below (no bottom edge)
                val activeFillPath = Path().apply {
                    moveTo(activeLeft + halfStroke, h + strokePx)
                    lineTo(activeLeft + halfStroke, radius + halfStroke)
                    arcTo(
                        rect = Rect(
                            activeLeft + halfStroke,
                            halfStroke,
                            activeLeft + halfStroke + radius * 2f,
                            halfStroke + radius * 2f
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(activeRight - halfStroke - radius, halfStroke)
                    arcTo(
                        rect = Rect(
                            activeRight - halfStroke - radius * 2f,
                            halfStroke,
                            activeRight - halfStroke,
                            halfStroke + radius * 2f
                        ),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(activeRight - halfStroke, h + strokePx)
                    close()
                }
                drawPath(path = activeFillPath, color = colors.surface)

                // Continuous outline: left baseline -> up active tab -> across top -> down -> right baseline
                val outlinePath = Path().apply {
                    if (activeLeft > halfStroke) {
                        moveTo(halfStroke, h - halfStroke)
                        lineTo(activeLeft + halfStroke, h - halfStroke)
                    } else {
                        moveTo(activeLeft + halfStroke, h)
                    }
                    lineTo(activeLeft + halfStroke, radius + halfStroke)
                    arcTo(
                        rect = Rect(
                            activeLeft + halfStroke,
                            halfStroke,
                            activeLeft + halfStroke + radius * 2f,
                            halfStroke + radius * 2f
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(activeRight - halfStroke - radius, halfStroke)
                    arcTo(
                        rect = Rect(
                            activeRight - halfStroke - radius * 2f,
                            halfStroke,
                            activeRight - halfStroke,
                            halfStroke + radius * 2f
                        ),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    if (activeRight < w - halfStroke) {
                        lineTo(activeRight - halfStroke, h - halfStroke)
                        lineTo(w - halfStroke, h - halfStroke)
                    } else {
                        lineTo(activeRight - halfStroke, h)
                    }
                }
                drawPath(
                    path = outlinePath,
                    color = colors.hairline,
                    style = Stroke(width = strokePx, cap = StrokeCap.Square, join = StrokeJoin.Round)
                )

                // Subtle active accent indicator bar tucked cleanly inside the active tab top edge
                val indicatorPad = (tabW * 0.24f).coerceAtLeast(10.dp.toPx())
                if (activeRight - activeLeft > indicatorPad * 2f) {
                    drawLine(
                        color = colors.activeAccent,
                        start = Offset(activeLeft + indicatorPad, strokePx * 2.5f),
                        end = Offset(activeRight - indicatorPad, strokePx * 2.5f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = index == safeSelected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(index) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) colors.activeAccentStrong else colors.secondaryText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Companion workspace body placed directly beneath [ConnectedFolderTabs] so its left,
 * right, and bottom borders join seamlessly without a top divider under the active tab.
 */
@Composable
fun ConnectedFolderBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalSynkaColors.current
    val bottomRadius = 16.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = bottomRadius, bottomEnd = bottomRadius))
            .background(colors.surface)
            .drawBehind {
                val strokePx = 1.dp.toPx()
                val half = strokePx * 0.5f
                val r = bottomRadius.toPx()
                val w = size.width
                val h = size.height

                val borderPath = Path().apply {
                    moveTo(half, 0f)
                    lineTo(half, h - r - half)
                    arcTo(
                        rect = Rect(half, h - r * 2f - half, half + r * 2f, h - half),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    lineTo(w - r - half, h - half)
                    arcTo(
                        rect = Rect(w - r * 2f - half, h - r * 2f - half, w - half, h - half),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    lineTo(w - half, 0f)
                }
                drawPath(
                    path = borderPath,
                    color = colors.hairline,
                    style = Stroke(width = strokePx)
                )
            }
            .padding(12.dp),
        content = content
    )
}

// -----------------------------------------------------------------------------
// 2. StudioVerticalFader
// -----------------------------------------------------------------------------

@Composable
fun StudioVerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    subLabel: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    defaultValue: Float = 0f,
    enabled: Boolean = true,
    trackHeight: Dp = 148.dp,
    accentColor: Color = LocalSynkaColors.current.activeAccent,
    valueFormatter: (Float) -> String = ::formatStudioIntegerReadout,
    onDoubleTapReset: () -> Unit = { onValueChange(defaultValue) },
    topNode: (@Composable () -> Unit)? = null
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current

    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(valueRange)
    val currentDefault by rememberUpdatedState(defaultValue)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnReset by rememberUpdatedState(onDoubleTapReset)

    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val zeroInsideRange = valueRange.start < 0f && valueRange.endInclusive > 0f
    val zeroAnchor = if (zeroInsideRange) 0f else defaultValue.coerceIn(valueRange.start, valueRange.endInclusive)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (topNode != null) {
            Box(
                modifier = Modifier.height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                topNode()
            }
        }

        // Numeric Readout (+2, 0, -3)
        Text(
            text = valueFormatter(clampedValue),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 10.sp,
            fontWeight = if (abs(clampedValue - zeroAnchor) > 0.05f) FontWeight.Bold else FontWeight.Medium,
            color = when {
                !enabled -> colors.secondaryText.copy(alpha = 0.45f)
                abs(clampedValue - zeroAnchor) > 0.05f -> accentColor
                else -> colors.secondaryText
            },
            maxLines = 1
        )

        // Vertical Drag Rail (Unified awaitEachGesture: never fights verticalScroll or drops touch)
        Canvas(
            modifier = Modifier
                .width(42.dp)
                .height(trackHeight)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    var lastTapUpMs = 0L
                    var lastTapPos = Offset.Zero
                    val dragSlopPx = 4.dp.toPx()
                    val padPx = 12.dp.toPx()
                    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                    val doubleTapSlopPx = viewConfiguration.touchSlop * 2.5f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val downPos = down.position
                        var isDragging = false
                        var lastEmitted = currentValue
                        val span = (currentRange.endInclusive - currentRange.start).coerceAtLeast(0.0001f)
                        val hasZero = currentRange.start < 0f && currentRange.endInclusive > 0f
                        val snapBand = span * 0.012f
                        var prevSign = signBucket(lastEmitted, if (hasZero) 0f else currentDefault, snapBand * 0.5f)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                if (!isDragging) {
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

                            val distFromDown = (change.position - downPos).getDistance()
                            if (!isDragging && distFromDown > dragSlopPx) {
                                isDragging = true
                                lastTapUpMs = 0L
                            }

                            // Consume motion on the rail so parent verticalScroll never steals fader drag
                            if (change.position != change.previousPosition) {
                                change.consume()
                            }

                            if (isDragging) {
                                val usableH = (size.height - padPx * 2f).coerceAtLeast(1f)
                                val frac = (1f - ((change.position.y - padPx) / usableH)).coerceIn(0f, 1f)
                                var mapped = currentRange.start + frac * span
                                if (hasZero && abs(mapped) <= snapBand) {
                                    mapped = 0f
                                }
                                mapped = mapped.coerceIn(currentRange.start, currentRange.endInclusive)

                                val nextSign = signBucket(mapped, if (hasZero) 0f else currentDefault, snapBand * 0.5f)
                                if (hasZero && nextSign != prevSign && (nextSign == 0 || (prevSign != 0 && prevSign == -nextSign))) {
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
            val padY = 12.dp.toPx()
            val usableH = (size.height - padY * 2f).coerceAtLeast(1f)
            val centerX = size.width * 0.5f
            val railW = 6.dp.toPx()
            val railLeft = centerX - railW * 0.5f

            val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
            val normVal = ((clampedValue - valueRange.start) / span).coerceIn(0f, 1f)
            val thumbY = padY + (1f - normVal) * usableH

            val normZero = ((zeroAnchor - valueRange.start) / span).coerceIn(0f, 1f)
            val zeroY = padY + (1f - normZero) * usableH

            // Center zero-tick calibration line extending past the track
            if (zeroInsideRange) {
                drawLine(
                    color = colors.secondaryText.copy(alpha = 0.48f),
                    start = Offset(centerX - 13.dp.toPx(), zeroY),
                    end = Offset(centerX + 13.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Recessed matte vertical rail
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

            // Active fill bar from zero-tick (or bottom) to thumb
            val fillTop = minOf(zeroY, thumbY)
            val fillBottom = maxOf(zeroY, thumbY)
            val fillHeight = (fillBottom - fillTop).coerceAtLeast(0f)
            if (fillHeight > 0.5f) {
                drawRoundRect(
                    color = if (enabled) accentColor else colors.secondaryText.copy(alpha = 0.35f),
                    topLeft = Offset(railLeft, fillTop),
                    size = Size(railW, fillHeight),
                    cornerRadius = CornerRadius(railW * 0.5f, railW * 0.5f)
                )
            }

            // Tactile physical fader cap
            val capW = 24.dp.toPx()
            val capH = 14.dp.toPx()
            val capLeft = centerX - capW * 0.5f
            val capTop = thumbY - capH * 0.5f

            drawRoundRect(
                color = if (enabled) colors.elevatedSurface else colors.surface,
                topLeft = Offset(capLeft, capTop),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
            drawRoundRect(
                color = if (enabled && abs(clampedValue - zeroAnchor) > 0.05f) {
                    accentColor
                } else {
                    colors.primaryText.copy(alpha = if (enabled) 0.45f else 0.2f)
                },
                topLeft = Offset(capLeft, capTop),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                style = Stroke(width = 1.2.dp.toPx())
            )
            // Center grip notch on fader cap
            drawLine(
                color = if (enabled) colors.primaryText.copy(alpha = 0.85f) else colors.secondaryText.copy(alpha = 0.35f),
                start = Offset(capLeft + 5.dp.toPx(), thumbY),
                end = Offset(capLeft + capW - 5.dp.toPx(), thumbY),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!subLabel.isNullOrBlank()) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 3. StudioHorizontalSlider
// -----------------------------------------------------------------------------

@Composable
fun StudioHorizontalSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    defaultValue: Float = 0f,
    enabled: Boolean = true,
    accentColor: Color = LocalSynkaColors.current.activeAccent,
    valueFormatter: (Float) -> String = ::formatStudioIntegerReadout,
    onDoubleTapReset: () -> Unit = { onValueChange(defaultValue) }
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current

    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(valueRange)
    val currentDefault by rememberUpdatedState(defaultValue)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnReset by rememberUpdatedState(onDoubleTapReset)

    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val zeroInsideRange = valueRange.start < 0f && valueRange.endInclusive > 0f
    val anchorValue = if (zeroInsideRange) 0f else valueRange.start

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueFormatter(clampedValue),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (abs(clampedValue - defaultValue) > 0.05f) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !enabled -> colors.secondaryText.copy(alpha = 0.45f)
                    abs(clampedValue - defaultValue) > 0.05f -> accentColor
                    else -> colors.secondaryText
                }
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

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
                        var isDragging = false
                        var lastEmitted = currentValue
                        val span = (currentRange.endInclusive - currentRange.start).coerceAtLeast(0.0001f)
                        val hasZero = currentRange.start < 0f && currentRange.endInclusive > 0f
                        val snapBand = span * 0.012f
                        var prevSign = signBucket(lastEmitted, if (hasZero) 0f else currentDefault, snapBand * 0.5f)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                if (!isDragging) {
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

                            if (!isDragging) {
                                // Yield cleanly to parent verticalScroll if gesture is clearly vertical
                                if (dy > viewConfiguration.touchSlop * 1.1f && dy > dx * 1.5f) {
                                    break
                                }
                                if (dx > dragSlopPx && dx >= dy * 0.75f) {
                                    isDragging = true
                                    lastTapUpMs = 0L
                                }
                            }

                            if (isDragging) {
                                change.consume()
                                val usableW = (size.width - padPx * 2f).coerceAtLeast(1f)
                                val frac = ((change.position.x - padPx) / usableW).coerceIn(0f, 1f)
                                var mapped = currentRange.start + frac * span
                                if (hasZero && abs(mapped) <= snapBand) {
                                    mapped = 0f
                                }
                                mapped = mapped.coerceIn(currentRange.start, currentRange.endInclusive)

                                val nextSign = signBucket(mapped, if (hasZero) 0f else currentDefault, snapBand * 0.5f)
                                if (hasZero && nextSign != prevSign && (nextSign == 0 || (prevSign != 0 && prevSign == -nextSign))) {
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
            val padX = 11.dp.toPx()
            val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
            val midY = size.height * 0.5f
            val trackH = 6.dp.toPx()
            val trackTop = midY - trackH * 0.5f

            val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
            val normVal = ((clampedValue - valueRange.start) / span).coerceIn(0f, 1f)
            val thumbX = padX + normVal * usableW

            val normAnchor = ((anchorValue - valueRange.start) / span).coerceIn(0f, 1f)
            val anchorX = padX + normAnchor * usableW

            // Zero-cross tick mark
            if (zeroInsideRange) {
                drawLine(
                    color = colors.secondaryText.copy(alpha = 0.5f),
                    start = Offset(anchorX, midY - 10.dp.toPx()),
                    end = Offset(anchorX, midY + 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Recessed matte horizontal track
            drawRoundRect(
                color = SynkaPalette.ObsidianCharcoal,
                topLeft = Offset(padX, trackTop),
                size = Size(usableW, trackH),
                cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
            )
            drawRoundRect(
                color = colors.hairline,
                topLeft = Offset(padX, trackTop),
                size = Size(usableW, trackH),
                cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
                style = Stroke(width = 1.dp.toPx())
            )

            // Active fill segment
            val fillLeft = minOf(anchorX, thumbX)
            val fillRight = maxOf(anchorX, thumbX)
            val fillW = (fillRight - fillLeft).coerceAtLeast(0f)
            if (fillW > 0.5f) {
                drawRoundRect(
                    color = if (enabled) accentColor else colors.secondaryText.copy(alpha = 0.35f),
                    topLeft = Offset(fillLeft, trackTop),
                    size = Size(fillW, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                )
            }

            // Precision thumb knob
            val knobR = 8.5.dp.toPx()
            drawCircle(
                color = if (enabled) colors.elevatedSurface else colors.surface,
                radius = knobR,
                center = Offset(thumbX, midY)
            )
            drawCircle(
                color = if (enabled && abs(clampedValue - defaultValue) > 0.05f) {
                    accentColor
                } else {
                    colors.primaryText.copy(alpha = if (enabled) 0.55f else 0.25f)
                },
                radius = knobR,
                center = Offset(thumbX, midY),
                style = Stroke(width = 1.6.dp.toPx())
            )
            drawCircle(
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.35f),
                radius = 2.4.dp.toPx(),
                center = Offset(thumbX, midY)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 4. YantraGlobe (Jantar Mantar Concentric Geometric 2D Pad)
// -----------------------------------------------------------------------------

@Composable
fun YantraGlobe(
    label: String,
    x: Float,
    y: Float,
    onChange: (Float, Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val colors = LocalSynkaColors.current
    val haptic = LocalHapticFeedback.current
    val isArtisan = colors.mode == SynkaThemeMode.ARTISAN_EDITION
    val ringAccent = if (isArtisan) SynkaPalette.KansaBronze else SynkaPalette.ChandiSilver

    val currentX by rememberUpdatedState(x)
    val currentY by rememberUpdatedState(y)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnReset by rememberUpdatedState(onDoubleTapReset)

    val (safeX, safeY) = clampToUnitCircle(x, y)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) colors.primaryText else colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    var lastTapUpMs = 0L
                    var lastTapPos = Offset.Zero
                    val dragSlopPx = 4.dp.toPx()
                    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                    val doubleTapSlopPx = viewConfiguration.touchSlop * 2.5f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val downPos = down.position
                        var isDragging = false
                        var prevDist = hypot(currentX.toDouble(), currentY.toDouble()).toFloat()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                if (!isDragging) {
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

                            val distFromDown = (change.position - downPos).getDistance()
                            if (!isDragging && distFromDown > dragSlopPx) {
                                isDragging = true
                                lastTapUpMs = 0L
                            }

                            // Consume motion immediately so vertical movement inside the globe never scrolls page
                            if (change.position != change.previousPosition) {
                                change.consume()
                            }

                            if (isDragging) {
                                val cx = size.width * 0.5f
                                val cy = size.height * 0.5f
                                val usableR = (minOf(size.width, size.height) * 0.5f - 10.dp.toPx()).coerceAtLeast(1f)
                                val rawX = (change.position.x - cx) / usableR
                                // Upward drag = +Y, downward drag = -Y
                                val rawY = -((change.position.y - cy) / usableR)
                                var (nx, ny) = clampToUnitCircle(rawX, rawY)

                                val dist = hypot(nx.toDouble(), ny.toDouble()).toFloat()
                                if (dist <= 0.035f) {
                                    nx = 0f
                                    ny = 0f
                                }
                                // Tactile detent tick when entering the center origin zone
                                if (prevDist > 0.08f && dist <= 0.08f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                prevDist = dist
                                currentOnChange(nx, ny)
                            }
                        }
                    }
                }
        ) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val outerR = (minOf(size.width, size.height) * 0.5f - 6.dp.toPx()).coerceAtLeast(1f)
            val activeR = (outerR - 4.dp.toPx()).coerceAtLeast(1f)

            val thinStroke = Stroke(width = 1.dp.toPx())
            val dashedStroke = Stroke(
                width = 0.9.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)
            )

            // Matte obsidian dial surface
            drawCircle(
                color = SynkaPalette.ObsidianCharcoal,
                radius = outerR,
                center = center
            )

            // Concentric Yantra / Jantar Mantar rings
            drawCircle(
                color = ringAccent.copy(alpha = if (enabled) 0.42f else 0.18f),
                radius = outerR,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawCircle(
                color = ringAccent.copy(alpha = 0.18f),
                radius = activeR * 0.72f,
                center = center,
                style = dashedStroke
            )
            drawCircle(
                color = ringAccent.copy(alpha = 0.24f),
                radius = activeR * 0.44f,
                center = center,
                style = thinStroke
            )
            drawCircle(
                color = ringAccent.copy(alpha = 0.16f),
                radius = activeR * 0.16f,
                center = center,
                style = thinStroke
            )

            // Cardinal crosshairs + 45-degree astronomical meridian spokes
            drawLine(
                color = ringAccent.copy(alpha = 0.24f),
                start = Offset(center.x - activeR, center.y),
                end = Offset(center.x + activeR, center.y),
                strokeWidth = 0.9.dp.toPx()
            )
            drawLine(
                color = ringAccent.copy(alpha = 0.24f),
                start = Offset(center.x, center.y - activeR),
                end = Offset(center.x, center.y + activeR),
                strokeWidth = 0.9.dp.toPx()
            )

            val diag = activeR * 0.7071f
            drawLine(
                color = ringAccent.copy(alpha = 0.10f),
                start = Offset(center.x - diag, center.y - diag),
                end = Offset(center.x + diag, center.y + diag),
                strokeWidth = 0.7.dp.toPx()
            )
            drawLine(
                color = ringAccent.copy(alpha = 0.10f),
                start = Offset(center.x - diag, center.y + diag),
                end = Offset(center.x + diag, center.y - diag),
                strokeWidth = 0.7.dp.toPx()
            )

            // Micro-degree perimeter ticks (every 5 deg short, every 15 deg medium, every 90 deg cardinal)
            for (deg in 0 until 360 step 5) {
                val rad = deg * (PI / 180.0)
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                val tickLen = when {
                    deg % 90 == 0 -> 7.dp.toPx()
                    deg % 15 == 0 -> 4.5.dp.toPx()
                    else -> 2.2.dp.toPx()
                }
                val tickAlpha = when {
                    deg % 90 == 0 -> 0.55f
                    deg % 15 == 0 -> 0.30f
                    else -> 0.14f
                }
                drawLine(
                    color = ringAccent.copy(alpha = tickAlpha),
                    start = Offset(center.x + (outerR - tickLen) * cosA, center.y + (outerR - tickLen) * sinA),
                    end = Offset(center.x + outerR * cosA, center.y + outerR * sinA),
                    strokeWidth = if (deg % 90 == 0) 1.2.dp.toPx() else 0.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Center origin pip
            drawCircle(
                color = ringAccent.copy(alpha = 0.45f),
                radius = 2.dp.toPx(),
                center = center
            )

            // Vector tether + active puck position
            val puckCenter = Offset(
                x = center.x + safeX * activeR,
                y = center.y - safeY * activeR
            )
            val magnitude = hypot(safeX.toDouble(), safeY.toDouble()).toFloat()
            if (magnitude > 0.01f) {
                drawLine(
                    color = ringAccent.copy(alpha = if (enabled) 0.75f else 0.25f),
                    start = center,
                    end = puckCenter,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Puck outer glow + bezel + bright center dot
            drawCircle(
                color = ringAccent.copy(alpha = if (enabled) 0.20f else 0.08f),
                radius = 12.dp.toPx(),
                center = puckCenter
            )
            drawCircle(
                color = colors.elevatedSurface,
                radius = 7.5.dp.toPx(),
                center = puckCenter
            )
            drawCircle(
                color = if (enabled) ringAccent else colors.secondaryText.copy(alpha = 0.4f),
                radius = 7.5.dp.toPx(),
                center = puckCenter,
                style = Stroke(width = 1.8.dp.toPx())
            )
            drawCircle(
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.4f),
                radius = 2.4.dp.toPx(),
                center = puckCenter
            )
        }

        val coordText = String.format(Locale.US, "X %+.2f  •  Y %+.2f", safeX, safeY)
        Text(
            text = subtitle ?: coordText,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText,
            maxLines = 1
        )
    }
}

// -----------------------------------------------------------------------------
// 5. VisualStudioHeader (~35% Top Visual Grading Preview + Actions)
// -----------------------------------------------------------------------------

@Composable
fun VisualStudioHeader(
    engine: SynkaEngine,
    onEditMedia: () -> Unit,
    onSavePreset: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(232.dp),
    isPreviewActive: Boolean = true,
    saveDialogTitle: String = "Save Display Preset",
    defaultSaveName: String = ""
) {
    val colors = LocalSynkaColors.current
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var imageViewRef by remember { mutableStateOf<ImageView?>(null) }
    var isHoldingBeforeAfter by remember { mutableStateOf(false) }
    var isBeforeAfterLocked by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val fallbackChart = remember { createCalibrationTestPatternBitmap(960, 540) }
    DisposableEffect(fallbackChart) {
        onDispose {
            if (!fallbackChart.isRecycled) {
                fallbackChart.recycle()
            }
        }
    }

    LaunchedEffect(engine, textureViewRef, imageViewRef, isPreviewActive) {
        val tv = textureViewRef
        val iv = imageViewRef
        if (isPreviewActive && tv != null && iv != null) {
            engine.bindVisualTarget(tv, iv)
        }
    }

    DisposableEffect(engine) {
        onDispose {
            engine.bindVisualTarget(null, null)
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
                if (engine.visualUri == null && !fallbackChart.isRecycled) {
                    iv.setImageBitmap(fallbackChart)
                }
            }
        }
    }

    val effectiveBypass = isHoldingBeforeAfter || isBeforeAfterLocked || !engine.isMasterEnabled

    LaunchedEffect(
        isPreviewActive,
        effectiveBypass,
        engine.activeArka,
        engine.visualUri,
        engine.visualIsVideo,
        textureViewRef,
        imageViewRef
    ) {
        if (!isPreviewActive) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                    bypass = effectiveBypass
                )
            }
        }
    }

    val activePresetName = remember(engine.activePresetId, engine.userPresets) {
        engine.findPresetById(engine.activePresetId)?.name ?: "Custom Grade"
    }

    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, cardShape)
    ) {
        // Live Neutral Preview Viewport (Long-press anywhere for instant Before/After bypass)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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

            // Top-left live status badge
            val badgeText = when {
                isHoldingBeforeAfter || isBeforeAfterLocked -> "BEFORE • ORIGINAL"
                !engine.isMasterEnabled -> "MASTER OFF"
                else -> activePresetName.uppercase()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isHoldingBeforeAfter || isBeforeAfterLocked) {
                            SynkaPalette.SindoorCrimson.copy(alpha = 0.90f)
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
                    color = if (isHoldingBeforeAfter || isBeforeAfterLocked) Color.White else colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Top-right Edit media picker chip
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface.copy(alpha = 0.90f))
                    .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable(onClick = onEditMedia)
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Edit",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )
            }
        }

        // Studio Action Strip: Before/After Eye Toggle | + Save | ↺ Reset
        StudioHeaderActionStrip(
            isBeforeAfterActive = isBeforeAfterLocked || isHoldingBeforeAfter,
            onToggleBeforeAfter = { isBeforeAfterLocked = !isBeforeAfterLocked },
            onSaveClick = { showSaveDialog = true },
            onResetClick = onReset
        )
    }

    if (showSaveDialog) {
        SavePresetNameDialog(
            title = saveDialogTitle,
            initialName = defaultSaveName,
            onConfirm = { name ->
                showSaveDialog = false
                onSavePreset(name)
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

// -----------------------------------------------------------------------------
// 6. AudioStudioHeader (~35% Top Audio Waveform Deck + Actions)
// -----------------------------------------------------------------------------

@Composable
fun AudioStudioHeader(
    engine: SynkaEngine,
    onEditMedia: () -> Unit,
    onSavePreset: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(216.dp),
    saveDialogTitle: String = "Save Audio Preset",
    defaultSaveName: String = ""
) {
    val colors = LocalSynkaColors.current
    val hasPlayableAudio = engine.audioUri != null || (engine.visualUri != null && engine.visualIsVideo)

    var isHoldingBeforeAfter by remember { mutableStateOf(false) }
    var isBeforeAfterLocked by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }

    val waveformBars = remember { FloatArray(SvaraAudio.WAVEFORM_BARS) }
    var frameTick by remember { mutableIntStateOf(0) }

    val effectiveBypass = isHoldingBeforeAfter || isBeforeAfterLocked || !engine.isMasterEnabled

    // Push live audible Before/After bypass directly to Svara DSP and restore on exit
    LaunchedEffect(effectiveBypass, engine.activeSvara) {
        engine.svaraAudio.updateParams(
            params = engine.activeSvara,
            bypassed = effectiveBypass
        )
    }

    DisposableEffect(engine) {
        onDispose {
            engine.svaraAudio.updateParams(
                params = engine.activeSvara,
                bypassed = !engine.isMasterEnabled
            )
        }
    }

    // 30 fps lock-free 48-bar waveform sampler
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

    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, cardShape)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(SynkaPalette.ObsidianCharcoal)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar: Track title + DSP telemetry + Edit audio picker chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (hasPlayableAudio) {
                            engine.audioTitle.ifBlank { "Loaded Audio Track" }
                        } else {
                            "No audio loaded • Tap Edit"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasPlayableAudio) colors.primaryText else colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = when {
                        isHoldingBeforeAfter || isBeforeAfterLocked -> "BEFORE • DSP BYPASSED"
                        !engine.isMasterEnabled -> "MASTER OFF"
                        else -> "${engine.svaraAudio.activeSampleRate} Hz • ${engine.svaraAudio.activeEncodingLabel()} • Pre ${String.format(Locale.US, "%+.1f", engine.activeSvara.preAmpDb)} dB"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isHoldingBeforeAfter || isBeforeAfterLocked) {
                            SynkaPalette.SindoorCrimson
                        } else {
                            colors.secondaryText
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.surface)
                        .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable(onClick = onEditMedia)
                        .padding(horizontal = 11.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText
                    )
                }
            }

            // 48-Bar Live Waveform Canvas (Press-and-hold anywhere for Before/After bypass)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp)
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
                Canvas(modifier = Modifier.fillMaxSize()) {
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
                        val barH = (mag * size.height).coerceAtLeast(3f)
                        val barColor = when {
                            effectiveBypass -> colors.secondaryText.copy(alpha = 0.42f)
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
            }

            // Transport: Play/Pause + Seek Bar + Timestamp
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
                    onValueChange = { v ->
                        isScrubbing = true
                        scrubPosMs = v.toLong()
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
                    text = formatStudioMillis(displayedPos),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )
            }
        }

        // Studio Action Strip: Before/After Eye Toggle | + Save | ↺ Reset
        StudioHeaderActionStrip(
            isBeforeAfterActive = isBeforeAfterLocked || isHoldingBeforeAfter,
            onToggleBeforeAfter = { isBeforeAfterLocked = !isBeforeAfterLocked },
            onSaveClick = { showSaveDialog = true },
            onResetClick = onReset
        )
    }

    if (showSaveDialog) {
        SavePresetNameDialog(
            title = saveDialogTitle,
            initialName = defaultSaveName,
            onConfirm = { name ->
                showSaveDialog = false
                onSavePreset(name)
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

// -----------------------------------------------------------------------------
// Shared Studio Header Action Strip & Save Preset Name Dialog
// -----------------------------------------------------------------------------

@Composable
private fun StudioHeaderActionStrip(
    isBeforeAfterActive: Boolean,
    onToggleBeforeAfter: () -> Unit,
    onSaveClick: () -> Unit,
    onResetClick: () -> Unit
) {
    val colors = LocalSynkaColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Before / After Eye Toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (isBeforeAfterActive) {
                        SynkaPalette.SindoorCrimson.copy(alpha = 0.22f)
                    } else {
                        colors.elevatedSurface
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (isBeforeAfterActive) SynkaPalette.SindoorCrimson else colors.hairline,
                    shape = RoundedCornerShape(50)
                )
                .clickable(onClick = onToggleBeforeAfter)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BeforeAfterEyeIcon(
                bypassed = isBeforeAfterActive,
                color = if (isBeforeAfterActive) SynkaPalette.SindoorCrimson else colors.primaryText,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (isBeforeAfterActive) "Original" else "Before/After",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isBeforeAfterActive) SynkaPalette.SindoorCrimson else colors.primaryText
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // + Save Preset Button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.activeAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable(onClick = onSaveClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "+ Save",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.activeAccentStrong
                )
            }

            // ↺ Reset Button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(50))
                    .clickable(onClick = onResetClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ResetArcIcon(
                    color = colors.secondaryText,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.primaryText
                )
            }
        }
    }
}

@Composable
fun SavePresetNameDialog(
    title: String,
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalSynkaColors.current
    var nameText by remember(initialName) { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText
            )

            OutlinedTextField(
                value = nameText,
                onValueChange = { if (it.length <= 40) nameText = it },
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Enter preset name...",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.primaryText,
                    unfocusedTextColor = colors.primaryText,
                    focusedBorderColor = colors.activeAccent,
                    unfocusedBorderColor = colors.hairline,
                    cursorColor = colors.activeAccent,
                    focusedContainerColor = colors.background,
                    unfocusedContainerColor = colors.background
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryText
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                val canSave = nameText.trim().isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (canSave) colors.activeAccent else colors.elevatedSurface)
                        .clickable(enabled = canSave) { onConfirm(nameText.trim()) }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (canSave) SynkaPalette.ObsidianCharcoal else colors.secondaryText.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Studio Header Vector Icons & Pure Math Helpers
// -----------------------------------------------------------------------------

@Composable
private fun BeforeAfterEyeIcon(
    bypassed: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eyePath = Path().apply {
            moveTo(w * 0.08f, h * 0.50f)
            cubicTo(w * 0.28f, h * 0.16f, w * 0.72f, h * 0.16f, w * 0.92f, h * 0.50f)
            cubicTo(w * 0.72f, h * 0.84f, w * 0.28f, h * 0.84f, w * 0.08f, h * 0.50f)
            close()
        }
        drawPath(path = eyePath, color = color, style = stroke)
        drawCircle(
            color = color,
            radius = size.minDimension * 0.15f,
            center = Offset(w * 0.50f, h * 0.50f)
        )
        if (bypassed) {
            drawLine(
                color = color,
                start = Offset(w * 0.16f, h * 0.84f),
                end = Offset(w * 0.84f, h * 0.16f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ResetArcIcon(
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
        // Arrowhead at top-left end of arc
        val head = Path().apply {
            moveTo(w * 0.14f, h * 0.18f)
            lineTo(w * 0.14f, h * 0.42f)
            lineTo(w * 0.38f, h * 0.42f)
        }
        drawPath(path = head, color = color, style = stroke)
    }
}

private fun clampToUnitCircle(x: Float, y: Float): Pair<Float, Float> {
    val safeX = if (x.isFinite()) x else 0f
    val safeY = if (y.isFinite()) y else 0f
    val dist = hypot(safeX.toDouble(), safeY.toDouble()).toFloat()
    return if (dist > 1f) {
        (safeX / dist) to (safeY / dist)
    } else {
        safeX to safeY
    }
}

private fun signBucket(value: Float, anchor: Float, epsilon: Float): Int {
    val delta = value - anchor
    return when {
        delta > epsilon -> 1
        delta < -epsilon -> -1
        else -> 0
    }
}

internal fun formatStudioIntegerReadout(value: Float): String {
    val rounded = value.roundToInt()
    return when {
        rounded > 0 -> "+$rounded"
        rounded < 0 -> "$rounded"
        else -> "0"
    }
}

internal fun formatStudioDecimalReadout(value: Float): String {
    return when {
        abs(value) < 0.05f -> "0.0"
        value > 0f -> String.format(Locale.US, "+%.1f", value)
        else -> String.format(Locale.US, "%.1f", value)
    }
}

private fun formatStudioMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
