package com.synka.studio.display.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.synka.studio.display.engine.DisplayEqEngine
import com.synka.studio.display.model.ControlScope
import com.synka.studio.display.model.DisplayEqState
import com.synka.studio.display.model.DisplayEqTier
import com.synka.studio.display.shader.synkaDisplayEqGpu
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.roundToInt

private const val SHIZUKU_PERMISSION_REQ_CODE = 4209

private val ObsidianBg = Color(0xFF121212)
private val ObsidianSurface = Color(0xFF1C1C1E)
private val ObsidianElevated = Color(0xFF252528)
private val KansaBronze = Color(0xFFD49A45)
private val MintLive = Color(0xFF81C995)
private val MutedText = Color(0xFF9E9EA6)
private val BorderHairline = Color(0xFF2E2E32)

private val HSL_BAND_LABELS = listOf(
    "Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"
)

private val HSL_BAND_SWATCHES = listOf(
    Color(0xFFE53935),
    Color(0xFFFB8C00),
    Color(0xFFFDD835),
    Color(0xFF43A047),
    Color(0xFF00ACC1),
    Color(0xFF1E88E5),
    Color(0xFF8E24AA),
    Color(0xFFD81B60)
)

private data class QuickDisplayPreset(
    val name: String,
    val build: (DisplayEqState) -> DisplayEqState
)

private val QUICK_PRESETS = listOf(
    QuickDisplayPreset("Night Dim") { cur ->
        cur.copy(
            enabled = true,
            dimStrength = 0.42f,
            temperature = 0.55f,
            tint = 0f,
            vignette = 0.20f,
            redGain = 1f,
            greenGain = 1f,
            blueGain = 1f,
            exposure = 0f,
            contrast = 0f,
            gamma = 1f,
            saturation = 0f
        )
    },
    QuickDisplayPreset("Warm Cinema") { cur ->
        cur.copy(
            enabled = true,
            dimStrength = 0.12f,
            temperature = 0.48f,
            tint = 0.08f,
            vignette = 0.35f,
            redGain = 1f,
            greenGain = 0.98f,
            blueGain = 0.92f,
            exposure = 0f,
            contrast = 0.22f,
            gamma = 0.94f,
            saturation = 0.14f,
            vibrance = 0.18f
        )
    },
    QuickDisplayPreset("Paper Read") { cur ->
        cur.copy(
            enabled = true,
            dimStrength = 0.24f,
            temperature = 0.72f,
            tint = 0.05f,
            vignette = 0.10f,
            redGain = 1f,
            greenGain = 0.96f,
            blueGain = 0.84f,
            contrast = -0.10f,
            saturation = -0.35f
        )
    },
    QuickDisplayPreset("Cool Crisp") { cur ->
        cur.copy(
            enabled = true,
            dimStrength = 0f,
            temperature = -0.45f,
            tint = 0f,
            vignette = 0f,
            redGain = 0.94f,
            greenGain = 1f,
            blueGain = 1.05f,
            contrast = 0.18f,
            vibrance = 0.22f,
            sharpen = 0.35f
        )
    },
    QuickDisplayPreset("Vivid GPU") { cur ->
        cur.copy(
            enabled = true,
            dimStrength = 0f,
            temperature = 0.12f,
            tint = 0f,
            vignette = 0.15f,
            contrast = 0.28f,
            gamma = 0.96f,
            saturation = 0.30f,
            vibrance = 0.35f,
            sharpen = 0.30f
        )
    },
    QuickDisplayPreset("Reset Flat") { cur ->
        DisplayEqState(
            enabled = false,
            oledSafeTintCap = cur.oledSafeTintCap,
            allowDirectRootSu = cur.allowDirectRootSu
        )
    }
)

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun List<Float>.replaceAt(index: Int, value: Float): List<Float> =
    List(8) { i -> if (i == index) value else getOrElse(i) { 0f } }

private fun checkShizukuRunning(): Boolean = runCatching {
    Shizuku.pingBinder() && !Shizuku.isPreV11()
}.getOrDefault(false)

private fun checkShizukuGranted(isRunning: Boolean): Boolean {
    if (!isRunning) return false
    return runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}

@Composable
fun SynkaDisplayEqScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember(context) { DisplayEqEngine.getInstance(context) }
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val state by engine.state.collectAsState()
    val activeTier by engine.activeTier.collectAsState()

    var hasOverlayPerm by remember { mutableStateOf(engine.hasOverlayPermission()) }
    var shizukuRunning by remember { mutableStateOf(checkShizukuRunning()) }
    var shizukuGranted by remember { mutableStateOf(checkShizukuGranted(shizukuRunning)) }
    var pendingStateAfterOverlay by remember { mutableStateOf<DisplayEqState?>(null) }
    var selectedHslBand by remember { mutableIntStateOf(0) }
    var showAdvancedSection by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        engine.reapplyNow()
    }

    fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun launchOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(
                context,
                "Open Android Settings -> Apps -> Synka Studio -> Display over other apps.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun applyOrRequestPermission(targetState: DisplayEqState) {
        if (!targetState.enabled) {
            pendingStateAfterOverlay = null
            engine.setState(targetState)
            return
        }
        val canRunSystemWide = engine.hasOverlayPermission() ||
            shizukuGranted ||
            targetState.allowDirectRootSu
        if (canRunSystemWide) {
            pendingStateAfterOverlay = null
            requestNotificationIfNeeded()
            engine.setState(targetState)
        } else {
            pendingStateAfterOverlay = targetState
            Toast.makeText(
                context,
                "Allow 'Display over other apps' once to activate System-Wide Display EQ.",
                Toast.LENGTH_LONG
            ).show()
            launchOverlayPermissionSettings()
        }
    }

    // Refresh permissions & apply pending state automatically upon returning from Settings
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPerm = engine.hasOverlayPermission()
                val running = checkShizukuRunning()
                shizukuRunning = running
                shizukuGranted = checkShizukuGranted(running)

                val pending = pendingStateAfterOverlay
                if (pending != null && (hasOverlayPerm || shizukuGranted)) {
                    pendingStateAfterOverlay = null
                    requestNotificationIfNeeded()
                    engine.setState(pending)
                } else {
                    engine.reapplyNow()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shizuku Binder & Permission Listeners (thread-safe main-handler dispatch)
    DisposableEffect(Unit) {
        val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
                mainHandler.post {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    shizukuRunning = checkShizukuRunning()
                    shizukuGranted = granted
                    if (granted) {
                        Toast.makeText(
                            context,
                            "Shizuku Hardware Color Engine enabled (Zero OLED Lift).",
                            Toast.LENGTH_SHORT
                        ).show()
                        engine.reapplyNow()
                    }
                }
            }
        }
        val binderReceivedListener = Shizuku.OnBinderReceivedListener {
            mainHandler.post {
                val running = checkShizukuRunning()
                shizukuRunning = running
                shizukuGranted = checkShizukuGranted(running)
                engine.reapplyNow()
            }
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            mainHandler.post {
                shizukuRunning = false
                shizukuGranted = false
                engine.reapplyNow()
            }
        }

        runCatching {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }

        onDispose {
            runCatching {
                Shizuku.removeRequestPermissionResultListener(permListener)
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
            }
        }
    }

    val tierHeadline = when {
        !state.enabled -> "Off"
        state.isNeutral() -> "Armed (Flat) — Pick a preset or move any slider"
        activeTier == DisplayEqTier.INACTIVE && !hasOverlayPerm && !shizukuGranted ->
            "GPU Preview Only (Grant Overlay for System-Wide)"
        else -> activeTier.label
    }
    val tierColor = when {
        !state.enabled -> MutedText
        activeTier == DisplayEqTier.TIER2_SHIZUKU_HARDWARE ||
            activeTier == DisplayEqTier.TIER2_ROOT_SURFACEFLINGER -> MintLive
        activeTier == DisplayEqTier.TIER1_STANDARD_OVERLAY ||
            activeTier == DisplayEqTier.TIER1_TRUSTED_A11Y_OVERLAY -> KansaBronze
        state.isNeutral() -> KansaBronze
        else -> MutedText
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. MASTER SWITCH & LIVE TIER STATUS CARD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianSurface)
                .border(1.dp, if (state.enabled) KansaBronze.copy(alpha = 0.5f) else BorderHairline, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Synka 3-Tier Display EQ",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tierHeadline,
                        color = tierColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            val nextState = if (state.isNeutral()) {
                                // Subtle warm comfort default so flipping ON gives immediate feedback
                                state.copy(enabled = true, temperature = 0.25f, dimStrength = 0.08f)
                            } else {
                                state.copy(enabled = true)
                            }
                            applyOrRequestPermission(nextState)
                        } else {
                            applyOrRequestPermission(state.copy(enabled = false))
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianBg,
                        checkedTrackColor = KansaBronze
                    )
                )
            }

            if (!hasOverlayPerm && !shizukuGranted) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        pendingStateAfterOverlay = if (state.isNeutral()) {
                            state.copy(enabled = true, temperature = 0.25f, dimStrength = 0.08f)
                        } else {
                            state.copy(enabled = true)
                        }
                        launchOverlayPermissionSettings()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KansaBronze,
                        contentColor = ObsidianBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable System-Wide Overlay (1-Tap Setup)", fontWeight = FontWeight.SemiBold)
                }
            }

            // Show Shizuku upgrade ONLY if Shizuku is already running on this phone
            if (shizukuRunning && !shizukuGranted) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            if (Shizuku.shouldShowRequestPermissionRationale()) {
                                Toast.makeText(
                                    context,
                                    "Open Shizuku app -> Authorized applications -> enable Synka Studio.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
                            }
                        }.onFailure {
                            Toast.makeText(context, "Shizuku request failed: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Shizuku Running — Unlock Hardware Mode (Zero OLED Lift)",
                        color = MintLive,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (shizukuRunning && shizukuGranted) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Shizuku Hardware Mode Ready • Turn off phone Eye Comfort / Adaptive Tone for best accuracy.",
                    color = MintLive,
                    fontSize = 11.sp
                )
            }
        }

        // 2. LIVE AGSL GPU CALIBRATION REFERENCE STRIP (Proves Tier 3 Shader Live Immediately)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianSurface)
                .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Live AGSL GPU Calibration Preview",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (state.enabled) "Active on Local Videos" else "Bypassed (Off)",
                    color = if (state.enabled) MintLive else MutedText,
                    fontSize = 10.5.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .synkaDisplayEqGpu(state)
            ) {
                CalibrationSwatchCanvas(modifier = Modifier.fillMaxSize())
            }
        }

        // 3. ONE-TAP PRESETS
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            QUICK_PRESETS.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = { applyOrRequestPermission(preset.build(state)) },
                    label = {
                        Text(
                            text = preset.name,
                            fontSize = 12.sp,
                            color = if (preset.name == "Reset Flat") MutedText else KansaBronze
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ObsidianSurface
                    )
                )
            }
        }

        // 4. SECTION A: SYSTEM-WIDE CORE CONTROLS
        SectionCard(
            title = "System-Wide Display Controls",
            badgeText = "SYSTEM-WIDE",
            badgeColor = MintLive
        ) {
            EqSliderRow(
                label = "Sub-Min Dimmer (OLED Black)",
                valueText = "${(state.dimStrength / 0.72f * 100f).roundToInt()}%",
                value = state.dimStrength,
                range = 0f..0.72f,
                onValueChange = { v -> engine.updateState { it.copy(dimStrength = v) } }
            )
            EqSliderRow(
                label = "Color Temperature",
                valueText = formatSignedPct(state.temperature, neg = "Cool", pos = "Warm"),
                value = state.temperature,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(temperature = v) } }
            )
            EqSliderRow(
                label = "Tint (Green / Magenta)",
                valueText = formatSignedPct(state.tint, neg = "Green", pos = "Mag"),
                value = state.tint,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(tint = v) } }
            )
            EqSliderRow(
                label = "Pure-Black Vignette",
                valueText = "${(state.vignette * 100f).roundToInt()}%",
                value = state.vignette,
                range = 0f..1f,
                onValueChange = { v -> engine.updateState { it.copy(vignette = v) } }
            )

            HorizontalDivider(thickness = 1.dp, color = BorderHairline)
            Spacer(Modifier.height(6.dp))

            EqSliderRow(
                label = "Red Gain",
                valueText = "${(state.redGain * 100f).roundToInt()}%",
                value = state.redGain,
                range = 0.2f..1.25f,
                onValueChange = { v -> engine.updateState { it.copy(redGain = v) } }
            )
            EqSliderRow(
                label = "Green Gain",
                valueText = "${(state.greenGain * 100f).roundToInt()}%",
                value = state.greenGain,
                range = 0.2f..1.25f,
                onValueChange = { v -> engine.updateState { it.copy(greenGain = v) } }
            )
            EqSliderRow(
                label = "Blue Gain",
                valueText = "${(state.blueGain * 100f).roundToInt()}%",
                value = state.blueGain,
                range = 0.2f..1.25f,
                onValueChange = { v -> engine.updateState { it.copy(blueGain = v) } }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("OLED-Safe Tint Cap", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Caps chromatic overlay at 18% to prevent washed-out OLED blacks.",
                        color = MutedText,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = state.oledSafeTintCap,
                    onCheckedChange = { safe -> engine.updateState { it.copy(oledSafeTintCap = safe) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianBg,
                        checkedTrackColor = MintLive
                    )
                )
            }
        }

        // 5. SECTION B: TONE, SATURATION & OPTICS (Hardware + In-App GPU)
        val satScope = engine.scopeForControl("saturation")
        val toneScope = engine.scopeForControl("contrast")
        SectionCard(
            title = "Tone, Saturation & Optics",
            badgeText = if (toneScope == ControlScope.SYSTEM_WIDE) {
                "SYSTEM-WIDE (ROOT)"
            } else if (satScope == ControlScope.SYSTEM_WIDE) {
                "SAT HW + VIDEO GPU"
            } else {
                "IN-APP VIDEO GPU"
            },
            badgeColor = if (toneScope == ControlScope.SYSTEM_WIDE) MintLive else KansaBronze
        ) {
            EqSliderRow(
                label = "Saturation",
                valueText = formatSignedPct(state.saturation),
                value = state.saturation,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(saturation = v) } }
            )
            EqSliderRow(
                label = "Exposure (EV)",
                valueText = String.format(Locale.US, "%+.2f EV", state.exposure),
                value = state.exposure,
                range = -2f..2f,
                onValueChange = { v -> engine.updateState { it.copy(exposure = v) } }
            )
            EqSliderRow(
                label = "Contrast",
                valueText = formatSignedPct(state.contrast),
                value = state.contrast,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(contrast = v) } }
            )
            EqSliderRow(
                label = "Gamma",
                valueText = String.format(Locale.US, "%.2f", state.gamma),
                value = state.gamma,
                range = 0.4f..2.5f,
                onValueChange = { v -> engine.updateState { it.copy(gamma = v) } }
            )
            EqSliderRow(
                label = "Vibrance",
                valueText = formatSignedPct(state.vibrance),
                value = state.vibrance,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(vibrance = v) } }
            )
            EqSliderRow(
                label = "Sharpen (Unsharp Mask)",
                valueText = "${(state.sharpen * 100f).roundToInt()}%",
                value = state.sharpen,
                range = 0f..1f,
                onValueChange = { v -> engine.updateState { it.copy(sharpen = v) } }
            )

            HorizontalDivider(thickness = 1.dp, color = BorderHairline)
            Spacer(Modifier.height(6.dp))

            EqSliderRow(
                label = "Blacks",
                valueText = formatSignedPct(state.blacks),
                value = state.blacks,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(blacks = v) } }
            )
            EqSliderRow(
                label = "Shadows",
                valueText = formatSignedPct(state.shadows),
                value = state.shadows,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(shadows = v) } }
            )
            EqSliderRow(
                label = "Highlights",
                valueText = formatSignedPct(state.highlights),
                value = state.highlights,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(highlights = v) } }
            )
            EqSliderRow(
                label = "Whites",
                valueText = formatSignedPct(state.whites),
                value = state.whites,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(whites = v) } }
            )
        }

        // 6. SECTION C: 8-CHANNEL HSL COLOR GRADER (Tier 3 GPU)
        SectionCard(
            title = "8-Channel HSL Grader",
            badgeText = "IN-APP VIDEO GPU",
            badgeColor = KansaBronze
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                HSL_BAND_LABELS.forEachIndexed { idx, bandName ->
                    val isSelected = selectedHslBand == idx
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) ObsidianElevated else ObsidianBg)
                            .border(
                                width = if (isSelected) 1.4.dp else 1.dp,
                                color = if (isSelected) HSL_BAND_SWATCHES[idx] else BorderHairline,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { selectedHslBand = idx }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(HSL_BAND_SWATCHES[idx])
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = bandName,
                            color = if (isSelected) Color.White else MutedText,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            val bandIdx = selectedHslBand.coerceIn(0, 7)
            val hueVal = state.hslHue.getOrElse(bandIdx) { 0f }
            val satVal = state.hslSat.getOrElse(bandIdx) { 0f }
            val lumVal = state.hslLum.getOrElse(bandIdx) { 0f }

            EqSliderRow(
                label = "${HSL_BAND_LABELS[bandIdx]} Hue",
                valueText = "${(hueVal * 30f).roundToInt()}°",
                value = hueVal,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(hslHue = it.hslHue.replaceAt(bandIdx, v)) } }
            )
            EqSliderRow(
                label = "${HSL_BAND_LABELS[bandIdx]} Saturation",
                valueText = formatSignedPct(satVal),
                value = satVal,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(hslSat = it.hslSat.replaceAt(bandIdx, v)) } }
            )
            EqSliderRow(
                label = "${HSL_BAND_LABELS[bandIdx]} Luminance",
                valueText = formatSignedPct(lumVal),
                value = lumVal,
                range = -1f..1f,
                onValueChange = { v -> engine.updateState { it.copy(hslLum = it.hslLum.replaceAt(bandIdx, v)) } }
            )

            OutlinedButton(
                onClick = {
                    engine.updateState {
                        it.copy(
                            hslHue = it.hslHue.replaceAt(bandIdx, 0f),
                            hslSat = it.hslSat.replaceAt(bandIdx, 0f),
                            hslLum = it.hslLum.replaceAt(bandIdx, 0f)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset ${HSL_BAND_LABELS[bandIdx]} Band", color = KansaBronze, fontSize = 12.sp)
            }
        }

        // 7. OPTIONAL ADVANCED HARDWARE / FULL-SCREEN OVERLAY OPTIONS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianSurface)
                .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedSection = !showAdvancedSection }
            ) {
                Text(
                    text = "Optional Full-Screen & Root Escalation",
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (showAdvancedSection) "Hide" else "Show",
                    color = KansaBronze,
                    fontSize = 12.sp
                )
            }

            if (showAdvancedSection) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Trusted Accessibility Overlay lets Synka dim the notification shade & status bar with zero touch blocks. Reads zero screen content or keystrokes.",
                    color = MutedText,
                    fontSize = 11.5.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "Unable to open Accessibility settings.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Accessibility Settings (Optional)", color = KansaBronze, fontSize = 12.sp)
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Root SurfaceFlinger Matrix (su)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Rooted devices only. Enables full system-wide 4x4 hardware color matrix.",
                            color = MutedText,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = state.allowDirectRootSu,
                        onCheckedChange = { allow -> engine.updateState { it.copy(allowDirectRootSu = allow) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBg,
                            checkedTrackColor = MintLive
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    badgeText: String,
    badgeColor: Color,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianSurface)
            .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun EqSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, color = Color.White, fontSize = 12.5.sp)
            Text(
                text = valueText,
                color = KansaBronze,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = KansaBronze,
                activeTrackColor = KansaBronze,
                inactiveTrackColor = Color(0xFF3A3A3C)
            )
        )
    }
}

@Composable
private fun CalibrationSwatchCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val topH = h * 0.44f
        val midH = h * 0.28f
        val botH = h - topH - midH

        // Top band: cinematic sky-to-sunset-to-skin-tone gradient
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF0A192F),
                    Color(0xFF1D4E89),
                    Color(0xFF3FA796),
                    Color(0xFFE9C46A),
                    Color(0xFFE76F51),
                    Color(0xFFD49A6A),
                    Color(0xFF8D5524)
                )
            ),
            topLeft = Offset.Zero,
            size = Size(w, topH)
        )

        // Middle band: 8 HSL primary color blocks
        val bandW = w / HSL_BAND_SWATCHES.size
        HSL_BAND_SWATCHES.forEachIndexed { idx, col ->
            drawRect(
                color = col,
                topLeft = Offset(idx * bandW, topH),
                size = Size(bandW + 1f, midH)
            )
        }

        // Bottom band: 8-step true-black (#000000) to pure-white (#FFFFFF) grayscale ramp
        val steps = 8
        val stepW = w / steps
        for (i in 0 until steps) {
            val luma = i / (steps - 1f)
            drawRect(
                color = Color(luma, luma, luma, 1f),
                topLeft = Offset(i * stepW, topH + midH),
                size = Size(stepW + 1f, botH)
            )
        }
    }
}

private fun formatSignedPct(
    value: Float,
    neg: String = "",
    pos: String = ""
): String {
    val pct = (value * 100f).roundToInt()
    return when {
        pct > 0 -> if (pos.isNotBlank()) "+$pct% $pos" else "+$pct%"
        pct < 0 -> if (neg.isNotBlank()) "$pct% $neg" else "$pct%"
        else -> "0%"
    }
}
