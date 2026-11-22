package com.synka.studio.media

import android.net.Uri
import java.util.Locale

enum class MediaKind {
    AUDIO,
    VIDEO
}

enum class AudioOutputMode(val label: String) {
    /** 24-bit integer -> 32-bit float (0 bit errors), up to 192 kHz, Svara EQ active */
    STUDIO_FLOAT_LOSSLESS("Studio 32-bit Float (EQ On)"),

    /** API 34+ USB DAC mixer bypass when HAL + sink encoding match exactly (bypasses EQ) */
    USB_BIT_PERFECT_DIRECT("USB Bit-Perfect Direct (API 34+)")
}

data class LocalMediaTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String,
    val displayName: String,
    val bitrateBps: Int,
    val kind: MediaKind,
    val isLosslessContainer: Boolean,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
) {
    val formatTag: String
        get() {
            val ext = displayName.substringAfterLast('.', "").uppercase(Locale.US)
            return when {
                ext.isNotBlank() && ext.length <= 5 -> ext
                mimeType.contains("flac", ignoreCase = true) -> "FLAC"
                mimeType.contains("wav", ignoreCase = true) -> "WAV"
                mimeType.contains("alac", ignoreCase = true) -> "ALAC"
                mimeType.contains("mp4", ignoreCase = true) -> if (kind == MediaKind.VIDEO) "MP4" else "M4A"
                mimeType.contains("matroska", ignoreCase = true) -> "MKV"
                else -> if (kind == MediaKind.VIDEO) "VIDEO" else "AUDIO"
            }
        }
}

data class AudioTelemetry(
    val codecLabel: String = "Idle",
    val sampleRateHz: Int = 0,
    val bitDepthLabel: String = "--",
    val channelCount: Int = 0,
    val bitrateKbps: Int = 0,
    val isLosslessSource: Boolean = false,
    val isBitPerfectActive: Boolean = false,
    val outputRouteLabel: String = "Device Output",
    val usbDacName: String? = null,
    val audioSessionId: Int = 0,
    val statusNote: String = "32-bit Float pipeline preserves 24-bit lossless audio with 0 bit errors."
) {
    fun formattedSpecLine(): String {
        val parts = mutableListOf<String>()
        if (codecLabel.isNotBlank() && codecLabel != "Idle") parts += codecLabel
        if (bitDepthLabel != "--") parts += bitDepthLabel
        if (sampleRateHz > 0) {
            val khz = sampleRateHz / 1000.0
            val formatted = if (sampleRateHz % 1000 == 0) {
                "${sampleRateHz / 1000} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
            parts += formatted
        }
        if (bitrateKbps > 0) parts += "$bitrateKbps kbps"
        return if (parts.isEmpty()) "No track loaded" else parts.joinToString(" • ")
    }
}
