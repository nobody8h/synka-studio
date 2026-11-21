package com.synka.studio

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class SynkaThemeMode {
    STUDIO_REFERENCE,
    ARTISAN_EDITION
}

object SynkaPalette {
    val ObsidianCharcoal = Color(0xFF121212)
    val DarkSlate = Color(0xFF1C1C1E)
    val ElevatedSlate = Color(0xFF262629)
    val ChandiSilver = Color(0xFFEAEAEA)
    val MutedSilver = Color(0xFF9A9AA0)
    val CleanSteel = Color(0xFFB8C0CC)
    val KansaBronze = Color(0xFFD49A45)
    val SindoorCrimson = Color(0xFFE63946)
}

@Immutable
data class SynkaColors(
    val mode: SynkaThemeMode,
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val activeAccent: Color,
    val activeAccentStrong: Color,
    val masterActiveSwitch: Color,
    val hairline: Color
)

private val StudioReferenceColors = SynkaColors(
    mode = SynkaThemeMode.STUDIO_REFERENCE,
    background = SynkaPalette.ObsidianCharcoal,
    surface = SynkaPalette.DarkSlate,
    elevatedSurface = SynkaPalette.ElevatedSlate,
    primaryText = SynkaPalette.ChandiSilver,
    secondaryText = SynkaPalette.MutedSilver,
    activeAccent = SynkaPalette.CleanSteel,
    activeAccentStrong = SynkaPalette.ChandiSilver,
    masterActiveSwitch = SynkaPalette.SindoorCrimson,
    hairline = SynkaPalette.ChandiSilver.copy(alpha = 0.12f)
)

private val ArtisanEditionColors = SynkaColors(
    mode = SynkaThemeMode.ARTISAN_EDITION,
    background = SynkaPalette.ObsidianCharcoal,
    surface = SynkaPalette.DarkSlate,
    elevatedSurface = SynkaPalette.ElevatedSlate,
    primaryText = SynkaPalette.ChandiSilver,
    secondaryText = SynkaPalette.MutedSilver,
    activeAccent = SynkaPalette.KansaBronze,
    activeAccentStrong = SynkaPalette.KansaBronze,
    masterActiveSwitch = SynkaPalette.SindoorCrimson,
    hairline = SynkaPalette.ChandiSilver.copy(alpha = 0.12f)
)

val LocalSynkaColors = staticCompositionLocalOf { StudioReferenceColors }

private val SynkaTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.6.sp
    )
)

@Composable
fun SynkaTheme(
    mode: SynkaThemeMode = SynkaThemeMode.STUDIO_REFERENCE,
    content: @Composable () -> Unit
) {
    val synkaColors = when (mode) {
        SynkaThemeMode.STUDIO_REFERENCE -> StudioReferenceColors
        SynkaThemeMode.ARTISAN_EDITION -> ArtisanEditionColors
    }

    val materialColorScheme = darkColorScheme(
        primary = synkaColors.activeAccent,
        onPrimary = SynkaPalette.ObsidianCharcoal,
        secondary = synkaColors.activeAccentStrong,
        onSecondary = SynkaPalette.ObsidianCharcoal,
        tertiary = synkaColors.masterActiveSwitch,
        onTertiary = SynkaPalette.ChandiSilver,
        background = synkaColors.background,
        onBackground = synkaColors.primaryText,
        surface = synkaColors.surface,
        onSurface = synkaColors.primaryText,
        surfaceVariant = synkaColors.elevatedSurface,
        onSurfaceVariant = synkaColors.secondaryText,
        outline = synkaColors.hairline,
        error = synkaColors.masterActiveSwitch,
        onError = SynkaPalette.ChandiSilver
    )

    CompositionLocalProvider(LocalSynkaColors provides synkaColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = SynkaTypography,
            content = content
        )
    }
}

/**
 * Subtle 5%-opacity astronomical yantra linework intended strictly for top
 * UNLOCK / Discover banners. Never place behind visual grading previews.
 */
@Composable
fun YantraWatermarkCanvas(
    modifier: Modifier = Modifier
) {
    val colors = LocalSynkaColors.current
    val strokeColor = colors.primaryText.copy(alpha = 0.05f)
    val accentStrokeColor = colors.activeAccent.copy(alpha = 0.05f)

    Canvas(modifier = modifier.clipToBounds()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val center = Offset(x = size.width * 0.78f, y = size.height * 0.5f)
        val baseRadius = min(size.width, size.height) * 0.92f
        val thinStroke = Stroke(width = 1.dp.toPx())
        val dashedStroke = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(4.dp.toPx(), 6.dp.toPx()),
                phase = 0f
            )
        )

        val ringRatios = floatArrayOf(0.24f, 0.44f, 0.66f, 0.88f, 1.12f)
        ringRatios.forEachIndexed { index, ratio ->
            val radius = baseRadius * ratio
            drawCircle(
                color = if (index == 2) accentStrokeColor else strokeColor,
                radius = radius,
                center = center,
                style = if (index % 2 == 1) dashedStroke else thinStroke
            )
        }

        // Cross-meridian and ecliptic radial axes
        val spokeCount = 12
        val innerRadius = baseRadius * 0.24f
        val outerRadius = baseRadius * 1.12f
        for (i in 0 until spokeCount) {
            val angleRad = (i * 30.0) * (PI / 180.0)
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val start = Offset(
                x = center.x + innerRadius * cosA,
                y = center.y + innerRadius * sinA
            )
            val end = Offset(
                x = center.x + outerRadius * cosA,
                y = center.y + outerRadius * sinA
            )
            drawLine(
                color = strokeColor,
                start = start,
                end = end,
                strokeWidth = 0.8.dp.toPx()
            )
        }

        // Degree calibration ticks on the primary astronomical ring
        val tickRingRadius = baseRadius * 0.66f
        val tickLength = 5.dp.toPx()
        for (deg in 0 until 360 step 15) {
            val angleRad = deg * (PI / 180.0)
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            drawLine(
                color = accentStrokeColor,
                start = Offset(
                    x = center.x + (tickRingRadius - tickLength) * cosA,
                    y = center.y + (tickRingRadius - tickLength) * sinA
                ),
                end = Offset(
                    x = center.x + (tickRingRadius + tickLength) * cosA,
                    y = center.y + (tickRingRadius + tickLength) * sinA
                ),
                strokeWidth = 0.9.dp.toPx()
            )
        }
    }
}
