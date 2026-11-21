package com.synka.studio

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size as AndroidSize
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private enum class LocalMediaSegment {
    VIDEO,
    MUSIC
}

private enum class MediaSortMode(val label: String) {
    NEWEST("Date (Newest)"),
    NAME_ASC("Name (A–Z)"),
    DURATION_DESC("Duration (Longest)"),
    SIZE_DESC("Size (Largest)")
}

private data class LocalMediaItem(
    val id: Long,
    val cacheKey: Long,
    val uri: Uri,
    val title: String,
    val subtitle: String,
    val folderName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val isVideo: Boolean,
    val albumId: Long = -1L
)

// In-memory LRU thumbnail cache surviving tab switches; never manually recycled to avoid Compose frame races.
private object MediaThumbnailCache {
    private val maxCacheKb: Int = run {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        (maxMemKb / 12).coerceIn(4 * 1024, 24 * 1024)
    }

    private val lru = object : LruCache<Long, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: Long, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private val failedKeys: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    fun get(key: Long): Bitmap? = lru.get(key)

    fun put(key: Long, bitmap: Bitmap) {
        failedKeys.remove(key)
        lru.put(key, bitmap)
    }

    fun isKnownCorrupt(key: Long): Boolean = failedKeys.contains(key)

    fun markCorrupt(key: Long) {
        failedKeys.add(key)
    }

    fun clearFailures() {
        failedKeys.clear()
    }
}

@Composable
fun LocalMediaScreen(
    engine: SynkaEngine,
    onVideoLoaded: (Uri) -> Unit,
    onAudioLoaded: (Uri, Boolean) -> Unit,
    onPickVisualSaf: () -> Unit,
    onPickAudioSaf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    var activeSegment by remember { mutableStateOf(LocalMediaSegment.VIDEO) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(MediaSortMode.NEWEST) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showFolderSortMenu by remember { mutableStateOf(false) }

    var permissionVersion by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }

    var videoItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var audioItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var isLoadingMedia by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var bindingTargetItem by remember { mutableStateOf<LocalMediaItem?>(null) }

    val hasVideoPermission = remember(permissionVersion) {
        hasReadPermissionForSegment(context, LocalMediaSegment.VIDEO)
    }
    val hasAudioPermission = remember(permissionVersion) {
        hasReadPermissionForSegment(context, LocalMediaSegment.MUSIC)
    }
    val hasActivePermission = when (activeSegment) {
        LocalMediaSegment.VIDEO -> hasVideoPermission
        LocalMediaSegment.MUSIC -> hasAudioPermission
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionVersion++
        refreshTick++
    }

    fun requestMediaPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    // Load MediaStore on Dispatchers.IO whenever segment, permission, or manual refresh changes
    LaunchedEffect(activeSegment, hasActivePermission, refreshTick) {
        if (!hasActivePermission) {
            isLoadingMedia = false
            loadError = null
            return@LaunchedEffect
        }
        isLoadingMedia = true
        loadError = null
        val queryResult = runCatching {
            withContext(Dispatchers.IO) {
                when (activeSegment) {
                    LocalMediaSegment.VIDEO -> queryVideoMediaStore(context)
                    LocalMediaSegment.MUSIC -> queryAudioMediaStore(context)
                }
            }
        }
        queryResult.onSuccess { list ->
            if (activeSegment == LocalMediaSegment.VIDEO) {
                videoItems = list
            } else {
                audioItems = list
            }
        }.onFailure { err ->
            loadError = err.message ?: "Unable to read local media."
        }
        isLoadingMedia = false
    }

    // Reset folder filter when switching between VIDEO and MUSIC
    LaunchedEffect(activeSegment) {
        selectedFolder = null
    }

    val rawItems = if (activeSegment == LocalMediaSegment.VIDEO) videoItems else audioItems

    val availableFolders = remember(rawItems) {
        rawItems
            .groupingBy { it.folderName }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    val displayedItems = remember(rawItems, searchQuery, selectedFolder, sortMode) {
        val q = searchQuery.trim().lowercase(Locale.US)
        val filtered = rawItems.filter { item ->
            val folderOk = selectedFolder == null || item.folderName == selectedFolder
            if (!folderOk) return@filter false
            if (q.isEmpty()) return@filter true
            item.title.lowercase(Locale.US).contains(q) ||
                item.subtitle.lowercase(Locale.US).contains(q) ||
                item.folderName.lowercase(Locale.US).contains(q)
        }
        when (sortMode) {
            MediaSortMode.NEWEST -> filtered.sortedByDescending { it.dateModifiedSec }
            MediaSortMode.NAME_ASC -> filtered.sortedBy { it.title.lowercase(Locale.US) }
            MediaSortMode.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            MediaSortMode.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Bar: [ VIDEO | MUSIC ] (~75% width) + 🔍 Search + 📁 Folder/Sort
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val pillShape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(pillShape)
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, pillShape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val videoSelected = activeSegment == LocalMediaSegment.VIDEO
                val musicSelected = activeSegment == LocalMediaSegment.MUSIC

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (videoSelected) colors.activeAccent else Color.Transparent)
                        .clickable { activeSegment = LocalMediaSegment.VIDEO },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "VIDEO",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (videoSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (videoSelected) SynkaPalette.ObsidianCharcoal else colors.primaryText
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(colors.hairline)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (musicSelected) colors.activeAccent else Color.Transparent)
                        .clickable { activeSegment = LocalMediaSegment.MUSIC },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MUSIC",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (musicSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (musicSelected) SynkaPalette.ObsidianCharcoal else colors.primaryText
                    )
                }
            }

            // 🔍 Search Toggle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSearchVisible || searchQuery.isNotBlank()) colors.elevatedSurface else colors.surface)
                    .border(
                        width = 1.dp,
                        color = if (isSearchVisible || searchQuery.isNotBlank()) colors.activeAccent else colors.hairline,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        isSearchVisible = !isSearchVisible
                        if (!isSearchVisible) searchQuery = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                MediaSearchIcon(
                    color = if (isSearchVisible || searchQuery.isNotBlank()) colors.activeAccent else colors.primaryText,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 📁 Folder / Sort Dropdown
            Box {
                val filterActive = selectedFolder != null || sortMode != MediaSortMode.NEWEST
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (filterActive) colors.elevatedSurface else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (filterActive) colors.activeAccent else colors.hairline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { showFolderSortMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    MediaFolderIcon(
                        color = if (filterActive) colors.activeAccent else colors.primaryText,
                        modifier = Modifier.size(17.dp)
                    )
                }

                DropdownMenu(
                    expanded = showFolderSortMenu,
                    onDismissRequest = { showFolderSortMenu = false },
                    modifier = Modifier
                        .background(colors.surface)
                        .width(228.dp)
                ) {
                    Text(
                        text = "SORT BY",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    MediaSortMode.entries.forEach { mode ->
                        val isCurrent = sortMode == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isCurrent) "● ${mode.label}" else mode.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isCurrent) colors.activeAccentStrong else colors.primaryText
                                )
                            },
                            onClick = {
                                sortMode = mode
                                showFolderSortMenu = false
                            }
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = colors.hairline)

                    Text(
                        text = "FOLDER FILTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (selectedFolder == null) "● All Folders (${rawItems.size})" else "All Folders (${rawItems.size})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selectedFolder == null) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedFolder == null) colors.activeAccentStrong else colors.primaryText
                            )
                        },
                        onClick = {
                            selectedFolder = null
                            showFolderSortMenu = false
                        }
                    )
                    availableFolders.take(8).forEach { (folder, count) ->
                        val isCurrent = selectedFolder == folder
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = (if (isCurrent) "● " else "") + "$folder ($count)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) colors.activeAccentStrong else colors.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                selectedFolder = folder
                                showFolderSortMenu = false
                            }
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = colors.hairline)

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Pick File (SAF)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.activeAccent
                            )
                        },
                        onClick = {
                            showFolderSortMenu = false
                            if (activeSegment == LocalMediaSegment.VIDEO) {
                                onPickVisualSaf()
                            } else {
                                onPickAudioSaf()
                            }
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Refresh Library",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.primaryText
                            )
                        },
                        onClick = {
                            showFolderSortMenu = false
                            MediaThumbnailCache.clearFailures()
                            refreshTick++
                        }
                    )
                }
            }
        }

        // Collapsible Search Bar
        AnimatedVisibility(visible = isSearchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = if (activeSegment == LocalMediaSegment.VIDEO) {
                            "Filter videos by title or folder..."
                        } else {
                            "Filter tracks by title, artist, or folder..."
                        },
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
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = colors.secondaryText.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Status / Active Filter Ribbon
        if (hasActivePermission) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val folderSummary = selectedFolder ?: "All Folders"
                Text(
                    text = "${displayedItems.size} ${if (activeSegment == LocalMediaSegment.VIDEO) "video(s)" else "track(s)"} • $folderSummary",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = sortMode.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.activeAccent
                )
            }
        }

        // Main Content Body
        when {
            !hasActivePermission -> {
                PermissionFallbackCard(
                    segment = activeSegment,
                    onGrantPermission = { requestMediaPermissions() },
                    onPickSaf = {
                        if (activeSegment == LocalMediaSegment.VIDEO) {
                            onPickVisualSaf()
                        } else {
                            onPickAudioSaf()
                        }
                    }
                )
            }

            isLoadingMedia && rawItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Scanning device ${if (activeSegment == LocalMediaSegment.VIDEO) "videos" else "audio"}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                }
            }

            loadError != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Could not load MediaStore library",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primaryText
                    )
                    Text(
                        text = loadError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.activeAccent)
                                .clickable { refreshTick++ }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = SynkaPalette.ObsidianCharcoal
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                                .clickable {
                                    if (activeSegment == LocalMediaSegment.VIDEO) onPickVisualSaf() else onPickAudioSaf()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Pick File (SAF)",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primaryText
                            )
                        }
                    }
                }
            }

            displayedItems.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedFolder != null) {
                            "No matching files"
                        } else if (activeSegment == LocalMediaSegment.VIDEO) {
                            "No videos found in MediaStore"
                        } else {
                            "No audio files found in MediaStore"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primaryText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searchQuery.isNotBlank() || selectedFolder != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.elevatedSurface)
                                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                                    .clickable {
                                        searchQuery = ""
                                        selectedFolder = null
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Clear Filters",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.activeAccent
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.activeAccent)
                                .clickable {
                                    if (activeSegment == LocalMediaSegment.VIDEO) onPickVisualSaf() else onPickAudioSaf()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Pick File (SAF)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = SynkaPalette.ObsidianCharcoal
                            )
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayedItems, key = { it.cacheKey }) { item ->
                        val binding = engine.perFileBindings[item.uri.toString()]
                        val boundDisplayName = binding?.first?.let { engine.findPresetById(it)?.name }
                        val boundAudioName = binding?.second?.let { engine.findPresetById(it)?.name }

                        val isCurrentlyLoaded = if (item.isVideo) {
                            engine.visualUri == item.uri && engine.visualIsVideo
                        } else {
                            engine.audioUri == item.uri
                        }

                        LocalMediaRow(
                            item = item,
                            isCurrentlyLoaded = isCurrentlyLoaded,
                            isPlaying = isCurrentlyLoaded && engine.isAudioPlaying,
                            boundDisplayName = boundDisplayName,
                            boundAudioName = boundAudioName,
                            onRowClick = {
                                if (item.isVideo) {
                                    engine.setVisualMedia(item.uri, isVideo = true)
                                    onVideoLoaded(item.uri)
                                } else {
                                    engine.setAudioMedia(item.uri, item.title)
                                    onAudioLoaded(item.uri, false)
                                }
                            },
                            onOpenInStudio = {
                                if (item.isVideo) {
                                    engine.setVisualMedia(item.uri, isVideo = true)
                                    onVideoLoaded(item.uri)
                                } else {
                                    engine.setAudioMedia(item.uri, item.title)
                                    onAudioLoaded(item.uri, true)
                                }
                            },
                            onBindPresets = {
                                bindingTargetItem = item
                            },
                            onClearBinding = if (binding != null) {
                                {
                                    engine.bindPresetsToFile(item.uri, null, null)
                                    Toast.makeText(
                                        context,
                                        "Cleared preset bindings for '${item.title}'",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    bindingTargetItem?.let { target ->
        BindFilePresetsDialog(
            item = target,
            engine = engine,
            onDismiss = { bindingTargetItem = null },
            onSaved = { displayId, audioId ->
                engine.bindPresetsToFile(target.uri, displayId, audioId)
                // If this file is currently loaded, immediately apply the updated binding
                val isLoaded = (target.isVideo && engine.visualUri == target.uri) ||
                    (!target.isVideo && engine.audioUri == target.uri)
                if (isLoaded) {
                    engine.applyBoundPresetsForUri(target.uri)
                }
                Toast.makeText(
                    context,
                    if (displayId == null && audioId == null) {
                        "Cleared bindings for '${target.title}'"
                    } else {
                        "Bound presets to '${target.title}'"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                bindingTargetItem = null
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Permission Card + SAF Fallback
// -----------------------------------------------------------------------------

@Composable
private fun PermissionFallbackCard(
    segment: LocalMediaSegment,
    onGrantPermission: () -> Unit,
    onPickSaf: () -> Unit
) {
    val colors = LocalSynkaColors.current
    val mediaLabel = if (segment == LocalMediaSegment.VIDEO) "Video" else "Audio"

    Box(
        modifier = Modifier.fillMaxSize(),
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
                text = "Local $mediaLabel Library Access",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.primaryText
            )
            Text(
                text = "Grant READ_MEDIA_VIDEO and READ_MEDIA_AUDIO to browse your device library, view thumbnails, and bind Arka & Svara presets per file. Everything stays 100% on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText
            )
            Text(
                text = "Prefer not to grant library permission? Use the system file picker below to open any individual file safely via SAF.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText
            )

            Spacer(modifier = Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.activeAccent)
                    .clickable(onClick = onGrantPermission)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Grant Media Permission",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = SynkaPalette.ObsidianCharcoal
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .clickable(onClick = onPickSaf)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Pick File (SAF)",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primaryText
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Media List Row + Async Cached Square Thumbnail + ⋮ Menu
// -----------------------------------------------------------------------------

@Composable
private fun LocalMediaRow(
    item: LocalMediaItem,
    isCurrentlyLoaded: Boolean,
    isPlaying: Boolean,
    boundDisplayName: String?,
    boundAudioName: String?,
    onRowClick: () -> Unit,
    onOpenInStudio: () -> Unit,
    onBindPresets: () -> Unit,
    onClearBinding: (() -> Unit)?
) {
    val colors = LocalSynkaColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(14.dp)

    val bindingSummary = remember(boundDisplayName, boundAudioName) {
        when {
            boundDisplayName != null && boundAudioName != null && boundDisplayName == boundAudioName ->
                "Bound: $boundDisplayName"
            boundDisplayName != null && boundAudioName != null ->
                "D: $boundDisplayName • A: $boundAudioName"
            boundDisplayName != null -> "Display: $boundDisplayName"
            boundAudioName != null -> "Audio: $boundAudioName"
            else -> null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (isCurrentlyLoaded) colors.elevatedSurface else colors.surface)
            .border(
                width = if (isCurrentlyLoaded) 1.4.dp else 1.dp,
                color = if (isCurrentlyLoaded) colors.activeAccent else colors.hairline,
                shape = rowShape
            )
            .clickable(onClick = onRowClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncMediaThumbnail(
            item = item,
            isActive = isCurrentlyLoaded,
            isPlaying = isPlaying
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrentlyLoaded) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isCurrentlyLoaded) colors.activeAccentStrong else colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val metaLine = buildString {
                if (item.subtitle.isNotBlank()) {
                    append(item.subtitle).append(" • ")
                }
                append(formatDurationMs(item.durationMs))
                append(" • ")
                append(formatFileSize(item.sizeBytes))
            }
            Text(
                text = metaLine,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (bindingSummary != null || isCurrentlyLoaded) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrentlyLoaded) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(SynkaPalette.SindoorCrimson.copy(alpha = 0.18f))
                                .border(
                                    0.8.dp,
                                    SynkaPalette.SindoorCrimson.copy(alpha = 0.55f),
                                    RoundedCornerShape(5.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isPlaying) "PLAYING" else "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = SynkaPalette.SindoorCrimson
                            )
                        }
                    }

                    if (bindingSummary != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors.background)
                                .border(
                                    0.8.dp,
                                    colors.activeAccent.copy(alpha = 0.55f),
                                    RoundedCornerShape(5.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = bindingSummary,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = colors.activeAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ⋮ Action Menu
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { menuExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                MoreVerticalIcon(
                    color = colors.secondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(colors.surface)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Bind Display & Audio Presets",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = colors.activeAccentStrong
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onBindPresets()
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (item.isVideo) "Open in Arka Studio" else "Open in Svara EQ",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primaryText
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onOpenInStudio()
                    }
                )

                if (onClearBinding != null) {
                    HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Clear Preset Binding",
                                style = MaterialTheme.typography.bodySmall,
                                color = SynkaPalette.SindoorCrimson
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onClearBinding()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AsyncMediaThumbnail(
    item: LocalMediaItem,
    isActive: Boolean,
    isPlaying: Boolean
) {
    val context = LocalContext.current
    val colors = LocalSynkaColors.current

    var bitmap by remember(item.cacheKey) {
        mutableStateOf(MediaThumbnailCache.get(item.cacheKey))
    }

    LaunchedEffect(item.cacheKey) {
        if (bitmap != null || MediaThumbnailCache.isKnownCorrupt(item.cacheKey)) {
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            loadThumbnailSafely(context, item)
        }
        if (decoded != null) {
            MediaThumbnailCache.put(item.cacheKey, decoded)
            bitmap = decoded
        } else {
            MediaThumbnailCache.markCorrupt(item.cacheKey)
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(colors.background)
            .border(
                width = 1.dp,
                color = if (isActive) colors.activeAccent.copy(alpha = 0.7f) else colors.hairline,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null && !currentBmp.isRecycled) {
            Image(
                bitmap = currentBmp.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            if (item.isVideo) {
                VideoPlaceholderGlyph(
                    color = colors.secondaryText,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                MusicPlaceholderGlyph(
                    color = colors.secondaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SynkaPalette.SindoorCrimson)
                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Per-File Preset Binding Dialog
// -----------------------------------------------------------------------------

@Composable
private fun BindFilePresetsDialog(
    item: LocalMediaItem,
    engine: SynkaEngine,
    onDismiss: () -> Unit,
    onSaved: (displayPresetId: String?, audioPresetId: String?) -> Unit
) {
    val colors = LocalSynkaColors.current
    val existing = engine.perFileBindings[item.uri.toString()]

    var selectedDisplayId by remember(item.uri) { mutableStateOf(existing?.first) }
    var selectedAudioId by remember(item.uri) { mutableStateOf(existing?.second) }

    val allPresets = remember(engine.userPresets, engine.favoriteIds) {
        engine.allPresets().sortedByDescending { it.isUserCreated }
    }
    val displayPresets = remember(allPresets) {
        allPresets.filter { it.domain == PresetDomain.DISPLAY || it.domain == PresetDomain.BOTH }
    }
    val audioPresets = remember(allPresets) {
        allPresets.filter { it.domain == PresetDomain.AUDIO || it.domain == PresetDomain.BOTH }
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
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bind Presets to File",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.activeAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText,
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Display Preset Picker
                Text(
                    text = "DISPLAY PRESET (ARKA)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresetBindOptionRow(
                        label = "None (Keep Current Display Grade)",
                        tag = "UNBOUND",
                        selected = selectedDisplayId == null,
                        onClick = { selectedDisplayId = null }
                    )
                    displayPresets.forEach { preset ->
                        PresetBindOptionRow(
                            label = preset.name,
                            tag = if (preset.isUserCreated) "CUSTOM • ${preset.domain.name}" else preset.domain.name,
                            selected = selectedDisplayId == preset.id,
                            onClick = { selectedDisplayId = preset.id }
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = colors.hairline)

                // Audio Preset Picker
                Text(
                    text = "AUDIO PRESET (SVARA)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondaryText
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresetBindOptionRow(
                        label = "None (Keep Current Audio EQ)",
                        tag = "UNBOUND",
                        selected = selectedAudioId == null,
                        onClick = { selectedAudioId = null }
                    )
                    audioPresets.forEach { preset ->
                        PresetBindOptionRow(
                            label = preset.name,
                            tag = if (preset.isUserCreated) "CUSTOM • ${preset.domain.name}" else preset.domain.name,
                            selected = selectedAudioId == preset.id,
                            onClick = { selectedAudioId = preset.id }
                        )
                    }
                }
            }

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
                        .clickable { onSaved(null, null) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear Both",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primaryText
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1.3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.activeAccent)
                        .clickable { onSaved(selectedDisplayId, selectedAudioId) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Save Binding",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SynkaPalette.ObsidianCharcoal
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetBindOptionRow(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalSynkaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.elevatedSurface else colors.background)
            .border(
                width = if (selected) 1.3.dp else 1.dp,
                color = if (selected) colors.activeAccent else colors.hairline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.activeAccentStrong else colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.activeAccent else colors.secondaryText
        )
    }
}

// -----------------------------------------------------------------------------
// Off-Main-Thread MediaStore Queries & Corrupt-Safe Thumbnail Loading
// -----------------------------------------------------------------------------

private fun hasReadPermissionForSegment(context: Context, segment: LocalMediaSegment): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (segment) {
            LocalMediaSegment.VIDEO -> {
                val full = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val partial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    false
                }
                full || partial
            }
            LocalMediaSegment.MUSIC -> {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun queryVideoMediaStore(context: Context): List<LocalMediaItem> {
    val result = ArrayList<LocalMediaItem>()
    val projection = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.TITLE)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Video.Media.SIZE)
        add(MediaStore.Video.Media.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        }
    }.toTypedArray()

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Video.Media.SIZE} > 0",
        null,
        "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
        val displayCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
        val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
        val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        } else {
            -1
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            val rawDisplay = if (displayCol >= 0) cursor.getString(displayCol) else null
            val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else null
            val title = rawTitle?.takeIf { it.isNotBlank() }
                ?: rawDisplay?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "Video #$id"
            val folder = (if (bucketCol >= 0) cursor.getString(bucketCol) else null)
                ?.takeIf { it.isNotBlank() } ?: "Videos"
            val duration = if (durCol >= 0) cursor.getLong(durCol).coerceAtLeast(0L) else 0L
            val size = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
            val dateMod = if (dateCol >= 0) cursor.getLong(dateCol).coerceAtLeast(0L) else 0L

            result += LocalMediaItem(
                id = id,
                cacheKey = id,
                uri = uri,
                title = title,
                subtitle = folder,
                folderName = folder,
                durationMs = duration,
                sizeBytes = size,
                dateModifiedSec = dateMod,
                isVideo = true
            )
        }
    }
    return result
}

private fun queryAudioMediaStore(context: Context): List<LocalMediaItem> {
    val result = ArrayList<LocalMediaItem>()
    val projection = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        }
    }.toTypedArray()

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.SIZE} > 0",
        null,
        "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val displayCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
        val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
        val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
        val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        } else {
            -1
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val rawDisplay = if (displayCol >= 0) cursor.getString(displayCol) else null
            val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) else null
            val title = rawTitle?.takeIf { it.isNotBlank() }
                ?: rawDisplay?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "Track #$id"
            val rawArtist = if (artistCol >= 0) cursor.getString(artistCol) else null
            val artist = rawArtist
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: "Unknown Artist"
            val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
            val folder = (if (bucketCol >= 0) cursor.getString(bucketCol) else null)
                ?.takeIf { it.isNotBlank() } ?: "Music"
            val duration = if (durCol >= 0) cursor.getLong(durCol).coerceAtLeast(0L) else 0L
            val size = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
            val dateMod = if (dateCol >= 0) cursor.getLong(dateCol).coerceAtLeast(0L) else 0L

            result += LocalMediaItem(
                id = id,
                cacheKey = -id - 1L,
                uri = uri,
                title = title,
                subtitle = artist,
                folderName = folder,
                durationMs = duration,
                sizeBytes = size,
                dateModifiedSec = dateMod,
                isVideo = false,
                albumId = albumId
            )
        }
    }
    return result
}

private fun loadThumbnailSafely(context: Context, item: LocalMediaItem): Bitmap? {
    val thumbSize = AndroidSize(192, 192)
    val direct = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, thumbSize, null)
        } else if (item.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                retriever.frameAtTime
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            null
        }
    }.getOrNull()

    if (direct != null) return direct

    // Secondary attempt for audio tracks whose art is indexed by albumId
    if (!item.isVideo && item.albumId >= 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return runCatching {
            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                item.albumId
            )
            context.contentResolver.loadThumbnail(albumArtUri, thumbSize, null)
        }.getOrNull()
    }

    return null
}

// -----------------------------------------------------------------------------
// Formatting & Minimal Studio Vector Glyphs
// -----------------------------------------------------------------------------

private fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSec = durationMs / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

@Composable
private fun MediaSearchIcon(color: Color, modifier: Modifier = Modifier) {
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
private fun MediaFolderIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.24f)
            lineTo(w * 0.42f, h * 0.24f)
            lineTo(w * 0.52f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.82f)
            lineTo(w * 0.10f, h * 0.82f)
            close()
        }
        drawPath(path = path, color = color, style = stroke)
    }
}

@Composable
private fun MoreVerticalIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = 1.7.dp.toPx()
        val cx = size.width * 0.5f
        drawCircle(color, r, Offset(cx, size.height * 0.24f))
        drawCircle(color, r, Offset(cx, size.height * 0.50f))
        drawCircle(color, r, Offset(cx, size.height * 0.76f))
    }
}

@Composable
private fun VideoPlaceholderGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.10f, h * 0.20f),
            size = Size(w * 0.80f, h * 0.60f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx())
        )
        val play = Path().apply {
            moveTo(w * 0.42f, h * 0.36f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.42f, h * 0.64f)
            close()
        }
        drawPath(play, color = color)
    }
}

@Composable
private fun MusicPlaceholderGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = 1.6.dp.toPx()
        drawLine(color, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.38f, h * 0.72f), strokeW)
        drawLine(color, Offset(w * 0.74f, h * 0.18f), Offset(w * 0.74f, h * 0.66f), strokeW)
        drawLine(color, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.74f, h * 0.18f), strokeW)
        drawCircle(color, w * 0.11f, Offset(w * 0.30f, h * 0.74f))
        drawCircle(color, w * 0.11f, Offset(w * 0.66f, h * 0.68f))
    }
}
