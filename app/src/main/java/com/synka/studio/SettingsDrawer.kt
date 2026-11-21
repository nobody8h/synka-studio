package com.synka.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale
import java.util.UUID

private const val CRASH_LOG_FILE_NAME = "last_crash.txt"

@OptIn(UnstableApi::class)
@Composable
fun SettingsDrawerSheet(
    engine: SynkaEngine,
    onResetKora: () -> Unit,
    onPresetImported: (SynkaPreset) -> Unit,
    onCloseDrawer: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    var showShareImportDialog by remember { mutableStateOf(false) }
    var showCrashLogDialog by remember { mutableStateOf(false) }
    var showClearStorageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        SynkaOverlayService.refreshPermissionState(context)
    }

    val installReport = remember { OverlayPermissionHelper.inspectInstallSource(context) }

    val deviceSampleRateLabel = remember(
        engine.isAudioPlaying,
        engine.audioUri,
        engine.visualUri,
        engine.exoPlayer.audioFormat
    ) {
        resolveAudioSampleRateLabel(context, engine)
    }

    val agslStatusLabel = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Unavailable (Needs API 33+)"
        engine.isMasterEnabled -> "Active (API ${Build.VERSION.SDK_INT} AGSL)"
        else -> "Standby (Master Bypassed)"
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(316.dp)
            .fillMaxHeight(),
        drawerContainerColor = colors.surface,
        drawerContentColor = colors.primaryText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Synka Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.primaryText
                    )
                    Text(
                        text = "SETTINGS & SYSTEM ENGINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.activeAccent
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onCloseDrawer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "CLOSE",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Text(
                text = "SYSTEM-WIDE ENGINE (ALL APPS)",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerActionRow(
                    title = "Screen Filter (Display Overlay)",
                    subtitle = SynkaOverlayService.statusSummary,
                    accentHighlight = SynkaOverlayService.isRunning && !SynkaOverlayService.isPausedByUser,
                    onClick = {
                        if (!SynkaOverlayService.hasOverlayPermission) {
                            OverlayPermissionHelper.openOverlayPermissionSettings(context)
                        } else if (SynkaOverlayService.isRunning) {
                            SynkaOverlayService.stop(context)
                        } else {
                            SynkaOverlayService.updateFromEngine(
                                context,
                                engine.activeArka,
                                engine.isMasterEnabled
                            )
                            SynkaOverlayService.start(context)
                        }
                    }
                )

                if (OverlayPermissionHelper.isOnePlusOrOplusDevice()) {
                    DrawerActionRow(
                        title = "OxygenOS Battery Persistence",
                        subtitle = "Set 'Allow background activity' so OxygenOS keeps filters alive",
                        onClick = { OverlayPermissionHelper.openBatterySettings(context) }
                    )
                }

                DrawerActionRow(
                    title = "System Audio EQ (DynamicsProcessing)",
                    subtitle = SvaraGlobalService.statusSummary,
                    accentHighlight = SvaraGlobalService.isRunning,
                    onClick = {
                        if (SvaraGlobalService.isRunning) {
                            SvaraGlobalService.stop(context)
                        } else {
                            SvaraGlobalService.start(context)
                            SvaraGlobalService.updateFromEngine(
                                context,
                                engine.activeSvara,
                                engine.isMasterEnabled,
                                engine.isAudioPlaying
                            )
                        }
                    }
                )

                val hasSecure = SynkaOverlayService.hasSecureSettingsGrant
                DrawerActionRow(
                    title = if (hasSecure) "Hardware B&W Ready (Granted)" else "Optional: Copy ADB Command for System B&W",
                    subtitle = if (hasSecure) {
                        "WRITE_SECURE_SETTINGS active for global Monochrome"
                    } else {
                        "pm grant com.synka.studio android.permission.WRITE_SECURE_SETTINGS"
                    },
                    onClick = {
                        val cmd = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                        copyToClipboard(context, "Synka ADB Command", cmd)
                        Toast.makeText(context, "Copied ADB command", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Text(
                text = "THEME EDITION",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            val isArtisan = engine.themeMode == SynkaThemeMode.ARTISAN_EDITION
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeOptionCard(
                    title = "Studio Reference",
                    subtitle = "Clean Steel accents, neutral grading workspace",
                    selected = !isArtisan,
                    onClick = { engine.setThemeMode(SynkaThemeMode.STUDIO_REFERENCE) }
                )
                ThemeOptionCard(
                    title = "Artisan Edition",
                    subtitle = "Kansa Bronze + 5% Yantra banner linework",
                    selected = isArtisan,
                    onClick = { engine.setThemeMode(SynkaThemeMode.ARTISAN_EDITION) }
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Text(
                text = "PRESETS & MAINTENANCE",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerActionRow(
                    title = "Share / Import Preset Code",
                    subtitle = "Export or import SYNK1:... compressed codes",
                    accentHighlight = true,
                    onClick = { showShareImportDialog = true }
                )

                DrawerActionRow(
                    title = "Reset All to Kora (Neutral)",
                    subtitle = "Zero Arka visual grade & flat Svara DSP",
                    onClick = onResetKora
                )

                DrawerActionRow(
                    title = "Crash Log Viewer",
                    subtitle = "Inspect filesDir/$CRASH_LOG_FILE_NAME",
                    onClick = { showCrashLogDialog = true }
                )

                DrawerActionRow(
                    title = "Clear Saved Presets / Cache",
                    subtitle = "${engine.userPresets.size} saved custom preset(s)",
                    destructive = false,
                    onClick = { showClearStorageDialog = true }
                )
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            Text(
                text = "ENGINE DIAGNOSTICS",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiagnosticRow("Android API", "API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
                DiagnosticRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagnosticRow("Install Source", "${installReport.channel.name} / ${installReport.packageSourceLabel}")
                DiagnosticRow(
                    "Touch Opacity Cap",
                    "${(SynkaOverlayService.activeMaxTouchOpacity * 100f).toInt()}% (applied ${(SynkaOverlayService.activeAppliedAlpha * 100f).toInt()}%)"
                )
                DiagnosticRow("AGSL Shader", agslStatusLabel)
                DiagnosticRow("Local Svara DSP", "${engine.svaraAudio.activeEncodingLabel()} @ ${engine.svaraAudio.activeSampleRate} Hz")
                DiagnosticRow("Media3 Version", MediaLibraryInfo.VERSION)
                DiagnosticRow("Device Sample Rate", deviceSampleRateLabel)
                DiagnosticRow("System Screen", SynkaOverlayService.statusSummary)
                DiagnosticRow("System Audio", SvaraGlobalService.statusSummary)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showShareImportDialog) {
        ShareImportPresetDialog(
            engine = engine,
            onPresetImported = { imported ->
                onPresetImported(imported)
            },
            onDismiss = { showShareImportDialog = false }
        )
    }

    if (showCrashLogDialog) {
        CrashLogViewerDialog(
            onDismiss = { showCrashLogDialog = false }
        )
    }

    if (showClearStorageDialog) {
        ClearStorageDialog(
            engine = engine,
            onResetKora = onResetKora,
            onDismiss = { showClearStorageDialog = false }
        )
    }
}

@Composable
private fun ShareImportPresetDialog(
    engine: SynkaEngine,
    onPresetImported: (SynkaPreset) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    var selectedTab by remember { mutableIntStateOf(0) }

    val currentLivePreset = remember(engine.activeArka, engine.activeSvara, engine.activePresetId) {
        val baseName = engine.findPresetById(engine.activePresetId)?.name ?: "Current Studio Grade"
        SynkaPreset(
            id = "live_snapshot",
            name = "$baseName (Live State)",
            domain = PresetDomain.BOTH,
            arka = engine.activeArka.normalized(),
            svara = engine.activeSvara.normalized(),
            isUserCreated = false
        )
    }

    val exportablePresets = remember(engine.userPresets, currentLivePreset) {
        buildList {
            addAll(engine.userPresets)
            add(currentLivePreset)
            addAll(SynkaBuiltInPresets.ALL_PRESETS)
        }
    }

    var selectedPresetId by remember(exportablePresets) {
        mutableStateOf(
            engine.userPresets.firstOrNull()?.id
                ?: engine.activePresetId
                ?: currentLivePreset.id
        )
    }

    val selectedPreset = exportablePresets.firstOrNull { it.id == selectedPresetId }
        ?: exportablePresets.first()

    val encodedCode = remember(selectedPreset) {
        PresetShare.encodePreset(selectedPreset)
    }

    var importCodeInput by remember { mutableStateOf("") }
    val decodedResult = remember(importCodeInput) {
        if (importCodeInput.isBlank()) null else PresetShare.decodePreset(importCodeInput)
    }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Preset Code Exchange",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )
                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Export SYNK1", "Import SYNK1").forEachIndexed { idx, label ->
                    val active = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) colors.activeAccent else Color.Transparent)
                            .clickable {
                                selectedTab = idx
                                importFeedback = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) SynkaPalette.ObsidianCharcoal else colors.primaryText
                        )
                    }
                }
            }

            if (selectedTab == 0) {
                Text(
                    text = "SELECT PRESET (${engine.userPresets.size} custom)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    exportablePresets.forEach { preset ->
                        val isSelected = preset.id == selectedPreset.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) colors.elevatedSurface else colors.background)
                                .border(
                                    width = if (isSelected) 1.3.dp else 1.dp,
                                    color = if (isSelected) colors.activeAccent else colors.hairline,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedPresetId = preset.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) colors.activeAccentStrong else colors.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val tag = when {
                                    preset.isUserCreated -> "CUSTOM • ${preset.domain.name}"
                                    preset.id == "live_snapshot" -> "LIVE WORKSPACE • BOTH"
                                    else -> "BUILT-IN • ${preset.domain.name}"
                                }
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.secondaryText
                                )
                            }

                            if (preset.isUserCreated) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SynkaPalette.SindoorCrimson.copy(alpha = 0.14f))
                                        .border(
                                            1.dp,
                                            SynkaPalette.SindoorCrimson.copy(alpha = 0.45f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            engine.deleteUserPreset(preset.id)
                                            Toast.makeText(
                                                context,
                                                "Deleted '${preset.name}'",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Delete",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SynkaPalette.SindoorCrimson
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.background)
                        .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ENCODED PAYLOAD",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.activeAccent
                        )
                        Text(
                            text = "${encodedCode.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.secondaryText
                        )
                    }
                    Text(
                        text = encodedCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = colors.primaryText,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.activeAccent)
                            .clickable {
                                copyToClipboard(context, "Synka Preset Code", encodedCode)
                                Toast.makeText(
                                    context,
                                    "Copied SYNK1 code for '${selectedPreset.name}'",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Copy Code",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SynkaPalette.ObsidianCharcoal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.elevatedSurface)
                            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                            .clickable {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Synka Preset: ${selectedPreset.name}")
                                    putExtra(Intent.EXTRA_TEXT, encodedCode)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Share Synka Preset")
                                    )
                                }
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Share...",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primaryText
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PASTE SYNK1:... CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PASTE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.activeAccent,
                            modifier = Modifier.clickable {
                                val pasted = readFromClipboard(context)
                                if (pasted.isNullOrBlank()) {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                } else {
                                    importCodeInput = pasted.trim()
                                    importFeedback = null
                                }
                            }
                        )
                        if (importCodeInput.isNotEmpty()) {
                            Text(
                                text = "CLEAR",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondaryText,
                                modifier = Modifier.clickable {
                                    importCodeInput = ""
                                    importFeedback = null
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = importCodeInput,
                    onValueChange = {
                        importCodeInput = it
                        importFeedback = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 104.dp, max = 148.dp),
                    placeholder = {
                        Text(
                            text = "SYNK1:H4sIAAAAAAAA...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.activeAccent,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.primaryText,
                        unfocusedTextColor = colors.primaryText,
                        cursorColor = colors.activeAccent,
                        focusedContainerColor = colors.background,
                        unfocusedContainerColor = colors.background,
                        focusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.6f),
                        unfocusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                decodedResult?.let { result ->
                    result.onSuccess { decoded ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.activeAccent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "VALID PRESET • ${decoded.domain.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.activeAccent
                            )
                            Text(
                                text = decoded.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primaryText
                            )
                            Text(
                                text = summarizePresetValues(decoded),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondaryText
                            )
                        }
                    }.onFailure { err ->
                        Text(
                            text = err.message ?: "Malformed preset code.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SynkaPalette.SindoorCrimson
                        )
                    }
                }

                importFeedback?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.activeAccent
                    )
                }

                val decodedPreset = decodedResult?.getOrNull()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (decodedPreset != null) colors.activeAccent else colors.elevatedSurface
                        )
                        .clickable(enabled = decodedPreset != null) {
                            val candidate = decodedPreset ?: return@clickable
                            val safeId = resolveImportId(candidate.id, engine)
                            val saved = engine.saveUserPreset(
                                name = candidate.name,
                                domain = candidate.domain,
                                displayGenre = candidate.displayGenre,
                                audioGenre = candidate.audioGenre,
                                arka = candidate.arka,
                                svara = candidate.svara,
                                id = safeId
                            )
                            engine.applyPreset(saved)
                            onPresetImported(saved)
                            Toast.makeText(
                                context,
                                "Imported & applied '${saved.name}'",
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Import & Apply Preset",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (decodedPreset != null) {
                            SynkaPalette.ObsidianCharcoal
                        } else {
                            colors.secondaryText
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashLogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current
    val crashFile = remember { File(context.filesDir, CRASH_LOG_FILE_NAME) }

    var refreshTick by remember { mutableIntStateOf(0) }
    val crashText = remember(refreshTick) {
        runCatching {
            if (crashFile.exists()) crashFile.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Crash Log Viewer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText
                    )
                    Text(
                        text = "filesDir/$CRASH_LOG_FILE_NAME",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText
                    )
                }
                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.background)
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = crashText ?: "No crash log recorded. filesDir/$CRASH_LOG_FILE_NAME is clean.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (crashText != null) colors.primaryText else colors.secondaryText
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (crashText != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.activeAccent)
                            .clickable {
                                copyToClipboard(context, "Synka Crash Log", crashText)
                                Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Copy Log",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SynkaPalette.ObsidianCharcoal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.elevatedSurface)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                            .clickable {
                                runCatching { if (crashFile.exists()) crashFile.delete() }
                                refreshTick++
                                Toast.makeText(context, "Crash log cleared", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Delete Log",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primaryText
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SynkaPalette.SindoorCrimson.copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                SynkaPalette.SindoorCrimson.copy(alpha = 0.45f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                throw RuntimeException("Synka Crash Catcher Test")
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Test Crash Catcher",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SynkaPalette.SindoorCrimson
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearStorageDialog(
    engine: SynkaEngine,
    onResetKora: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    val customCount = engine.userPresets.size
    val cacheKb = remember { computeDirectoryBytes(context.cacheDir) / 1024L }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Clear Saved Data",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText
            )

            Text(
                text = "Custom saved presets: $customCount\nApp cache directory: ${cacheKb} KB",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.elevatedSurface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                        .clickable {
                            clearAppCache(context)
                            PresetShare.DiscoverCatalog.clearCache()
                            Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear Cache Only",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primaryText
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SynkaPalette.SindoorCrimson.copy(alpha = 0.16f))
                        .border(
                            1.dp,
                            SynkaPalette.SindoorCrimson.copy(alpha = 0.55f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            val toDelete = engine.userPresets.map { it.id }
                            toDelete.forEach { id -> engine.deleteUserPreset(id) }
                            clearAppCache(context)
                            PresetShare.DiscoverCatalog.clearCache()
                            onResetKora()
                            Toast.makeText(
                                context,
                                "Cleared ${toDelete.size} saved preset(s) and cache",
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear Saved Presets & Cache",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SynkaPalette.SindoorCrimson
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
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
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.activeAccent else colors.hairline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.activeAccentStrong else colors.primaryText
            )
            if (selected) {
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.activeAccent
                )
            }
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText
        )
    }
}

@Composable
private fun DrawerActionRow(
    title: String,
    subtitle: String,
    accentHighlight: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val borderColor = when {
        destructive -> SynkaPalette.SindoorCrimson.copy(alpha = 0.5f)
        accentHighlight -> colors.activeAccent.copy(alpha = 0.55f)
        else -> colors.hairline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elevatedSurface)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (destructive) SynkaPalette.SindoorCrimson else colors.primaryText
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val colors = LocalSynkaColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = colors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(UnstableApi::class)
private fun resolveAudioSampleRateLabel(context: Context, engine: SynkaEngine): String {
    val streamRate = runCatching { engine.exoPlayer.audioFormat?.sampleRate ?: -1 }.getOrDefault(-1)
    if (streamRate > 0) {
        return "${String.format(Locale.US, "%,d", streamRate)} Hz (Stream)"
    }
    val hwRate = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }.getOrNull()
    return if (hwRate != null && hwRate > 0) {
        "${String.format(Locale.US, "%,d", hwRate)} Hz (Device)"
    } else {
        "System Default"
    }
}

private fun resolveImportId(candidateId: String, engine: SynkaEngine): String {
    val clean = candidateId.trim()
    val collidesWithBuiltIn = when {
        clean.startsWith("builtin_") -> true
        clean == "live_snapshot" -> true
        SynkaBuiltInPresets.ALL_PRESETS.any { it.id == clean } -> true
        else -> false
    }
    val collidesWithUser = engine.userPresets.any { it.id == clean }
    return when {
        clean.isBlank() -> "user_${UUID.randomUUID()}"
        collidesWithBuiltIn -> "user_${UUID.randomUUID()}"
        collidesWithUser -> "user_${UUID.randomUUID()}"
        else -> clean
    }
}

private fun summarizePresetValues(preset: SynkaPreset): String {
    val activeHslCount = preset.arka.bands.count { b ->
        when {
            b.sat != 0f -> true
            b.hue != 0f -> true
            b.lum != 0f -> true
            b.solo -> true
            b.mute -> true
            else -> false
        }
    }
    val activeEqCount = preset.svara.bands.count { it.gainDb != 0f }
    val preSign = if (preset.svara.preAmpDb >= 0f) "+" else ""
    val preStr = "$preSign${String.format(Locale.US, "%.1f", preset.svara.preAmpDb)} dB"
    return when (preset.domain) {
        PresetDomain.DISPLAY -> "Arka: $activeHslCount HSL band(s) • Warmth ${preset.arka.warmth.toInt()}"
        PresetDomain.AUDIO -> "Svara: $activeEqCount EQ band(s) • PreAmp $preStr"
        PresetDomain.BOTH -> "Arka: $activeHslCount HSL • Svara: $activeEqCount EQ ($preStr)"
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun computeDirectoryBytes(dir: File?): Long {
    if (dir == null) return 0L
    if (!dir.exists()) return 0L
    var total = 0L
    dir.walkTopDown().forEach { file ->
        if (file.isFile) total += file.length()
    }
    return total
}

private fun clearAppCache(context: Context) {
    runCatching {
        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
