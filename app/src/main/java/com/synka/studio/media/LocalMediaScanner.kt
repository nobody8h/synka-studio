package com.synka.studio.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class LocalMediaLibrary(
    val audioTracks: List<LocalMediaTrack> = emptyList(),
    val videoTracks: List<LocalMediaTrack> = emptyList()
) {
    val losslessAudioTracks: List<LocalMediaTrack>
        get() = audioTracks.filter { it.isLosslessContainer }
}

object LocalMediaScanner {

    private val LOSSLESS_EXTENSIONS = setOf(
        "flac", "wav", "wave", "alac", "aiff", "aif", "wv", "ape", "dsf", "dff", "pcm"
    )

    private val LOSSLESS_MIMES = setOf(
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
        "audio/vnd.wave", "audio/alac", "audio/aiff", "audio/x-aiff"
    )

    suspend fun scanLibrary(context: Context): LocalMediaLibrary = withContext(Dispatchers.IO) {
        val audio = queryAudio(context)
        val video = queryVideo(context)
        LocalMediaLibrary(audioTracks = audio, videoTracks = video)
    }

    private fun queryAudio(context: Context): List<LocalMediaTrack> {
        val result = mutableListOf<LocalMediaTrack>()
        val hasBitrateCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (hasBitrateCol) add(MediaStore.Audio.Media.BITRATE)
        }.toTypedArray()

        // Do NOT require IS_MUSIC != 0: OEM MediaStore indexers often mark downloaded FLAC/WAV as 0.
        val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0"
        val selectionArgs = arrayOf("5000")
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val brCol = if (hasBitrateCol) cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val displayName = cursor.getString(nameCol).orEmpty()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val artist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown Artist"
                    val album = cursor.getString(albumCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Local Library"
                    val durationMs = cursor.getLong(durCol).coerceAtLeast(0L)
                    val mime = cursor.getString(mimeCol).orEmpty()
                    val bitrate = if (brCol >= 0) cursor.getInt(brCol).coerceAtLeast(0) else 0

                    result += LocalMediaTrack(
                        id = id,
                        uri = uri,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        mimeType = mime,
                        displayName = displayName,
                        bitrateBps = bitrate,
                        kind = MediaKind.AUDIO,
                        isLosslessContainer = isLosslessFile(mime, displayName)
                    )
                }
            }
        }
        return result
    }

    private fun queryVideo(context: Context): List<LocalMediaTrack> {
        val result = mutableListOf<LocalMediaTrack>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.DURATION} >= ?",
                arrayOf("1000"),
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val displayName = cursor.getString(nameCol).orEmpty()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    result += LocalMediaTrack(
                        id = id,
                        uri = uri,
                        title = title,
                        artist = "Local Video",
                        album = "${cursor.getInt(wCol)}×${cursor.getInt(hCol)}",
                        durationMs = cursor.getLong(durCol).coerceAtLeast(0L),
                        mimeType = cursor.getString(mimeCol).orEmpty(),
                        displayName = displayName,
                        bitrateBps = 0,
                        kind = MediaKind.VIDEO,
                        isLosslessContainer = false,
                        videoWidth = cursor.getInt(wCol),
                        videoHeight = cursor.getInt(hCol)
                    )
                }
            }
        }
        return result
    }

    fun isLosslessFile(mimeType: String, displayName: String): Boolean {
        val m = mimeType.lowercase(Locale.US)
        if (m in LOSSLESS_MIMES || m.contains("flac") || m.contains("wav") || m.contains("alac")) {
            return true
        }
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in LOSSLESS_EXTENSIONS
    }
}
