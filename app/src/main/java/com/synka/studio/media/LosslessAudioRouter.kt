package com.synka.studio.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

data class UsbRouteDecision(
    val usbDacConnected: Boolean,
    val usbDacName: String?,
    val bitPerfectActivated: Boolean,
    val routeLabel: String,
    val statusNote: String,
    val shouldUseFloatOutput: Boolean
)

class LosslessAudioRouter(
    context: Context,
    private val onDevicesChanged: () -> Unit
) {
    companion object {
        private const val TAG = "SynkaAudioRouter"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var activeBitPerfectDevice: AudioDeviceInfo? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val active = activeBitPerfectDevice
            if (active != null && removedDevices.any { it.id == active.id }) {
                clearBitPerfectRoute()
            }
            onDevicesChanged()
        }
    }

    fun startMonitoring() {
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    fun stopMonitoring() {
        clearBitPerfectRoute()
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun findConnectedUsbDac(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { dev ->
            dev.isSink && (
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                )
        }
    }

    fun describeActiveOutputDevice(): String {
        val am = audioManager ?: return "Device Output"
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }

        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }?.let { dev ->
            val name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB Audio"
            return "USB DAC: $name"
        }

        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }?.let { return "Wired Headphones" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }?.let { dev ->
                val name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
                return "LE Audio: $name"
            }
        }

        outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { dev ->
            val name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "A2DP"
            return "Bluetooth: $name"
        }

        return "Phone Speaker"
    }

    fun configureRoute(
        requestedMode: AudioOutputMode,
        trackSampleRateHz: Int,
        trackChannelCount: Int,
        trackIs16BitSource: Boolean
    ): UsbRouteDecision {
        val usbDevice = findConnectedUsbDac()
        val dacName = usbDevice?.productName?.toString()?.takeIf { it.isNotBlank() }

        if (requestedMode == AudioOutputMode.STUDIO_FLOAT_LOSSLESS) {
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = usbDevice != null,
                usbDacName = dacName,
                bitPerfectActivated = false,
                routeLabel = describeActiveOutputDevice(),
                statusNote = "32-bit Float Studio path active (lossless for 16/24-bit; Svara EQ active).",
                shouldUseFloatOutput = true
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = usbDevice != null,
                usbDacName = dacName,
                bitPerfectActivated = false,
                routeLabel = describeActiveOutputDevice(),
                statusNote = "Android 14+ (API 34) required for native Bit-Perfect USB API. Using 32-bit Float.",
                shouldUseFloatOutput = true
            )
        }

        if (usbDevice == null) {
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = false,
                usbDacName = null,
                bitPerfectActivated = false,
                routeLabel = describeActiveOutputDevice(),
                statusNote = "Connect a USB DAC for Bit-Perfect bypass. Currently using 32-bit Float Studio path.",
                shouldUseFloatOutput = true
            )
        }

        return tryConfigureApi34BitPerfect(
            usbDevice = usbDevice,
            dacName = dacName ?: "USB DAC",
            sampleRateHz = trackSampleRateHz.takeIf { it > 0 } ?: 48_000,
            channelCount = trackChannelCount.takeIf { it > 0 } ?: 2,
            trackIs16BitSource = trackIs16BitSource
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tryConfigureApi34BitPerfect(
        usbDevice: AudioDeviceInfo,
        dacName: String,
        sampleRateHz: Int,
        channelCount: Int,
        trackIs16BitSource: Boolean
    ): UsbRouteDecision {
        val am = audioManager ?: return UsbRouteDecision(
            usbDacConnected = true,
            usbDacName = dacName,
            bitPerfectActivated = false,
            routeLabel = "USB DAC: $dacName",
            statusNote = "AudioManager unavailable; using 32-bit Float.",
            shouldUseFloatOutput = true
        )

        val supported = runCatching { am.getSupportedMixerAttributes(usbDevice) }
            .onFailure { Log.w(TAG, "getSupportedMixerAttributes failed", it) }
            .getOrDefault(emptyList())

        val bitPerfectAttrs = supported.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }

        if (bitPerfectAttrs.isEmpty()) {
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = true,
                usbDacName = dacName,
                bitPerfectActivated = false,
                routeLabel = "USB DAC: $dacName (32-bit Float)",
                statusNote = "This phone HAL/USB DAC does not advertise MIXER_BEHAVIOR_BIT_PERFECT. Using lossless 32-bit Float.",
                shouldUseFloatOutput = true
            )
        }

        val desiredMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val floatMatch = bitPerfectAttrs.firstOrNull { attr ->
            val fmt = attr.format
            fmt.encoding == AudioFormat.ENCODING_PCM_FLOAT &&
                fmt.sampleRate == sampleRateHz &&
                fmt.channelMask == desiredMask
        }

        val int16Match = if (floatMatch == null && trackIs16BitSource) {
            bitPerfectAttrs.firstOrNull { attr ->
                val fmt = attr.format
                fmt.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                    fmt.sampleRate == sampleRateHz &&
                    fmt.channelMask == desiredMask
            }
        } else {
            null
        }

        val chosen = floatMatch ?: int16Match
        if (chosen == null) {
            val advertisedRates = bitPerfectAttrs
                .map { "${it.format.sampleRate / 1000.0}kHz/${encodingName(it.format.encoding)}" }
                .distinct()
                .take(4)
                .joinToString(", ")
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = true,
                usbDacName = dacName,
                bitPerfectActivated = false,
                routeLabel = "USB DAC: $dacName (32-bit Float)",
                statusNote = "DAC advertises Bit-Perfect ($advertisedRates) which requires a custom AudioTrack encoding; using bit-exact 32-bit Float.",
                shouldUseFloatOutput = true
            )
        }

        val applied = runCatching {
            am.setPreferredMixerAttributes(mediaAttributes, usbDevice, chosen)
        }.onFailure {
            Log.w(TAG, "setPreferredMixerAttributes failed", it)
        }.getOrDefault(false)

        if (!applied) {
            clearBitPerfectRoute()
            return UsbRouteDecision(
                usbDacConnected = true,
                usbDacName = dacName,
                bitPerfectActivated = false,
                routeLabel = "USB DAC: $dacName (32-bit Float)",
                statusNote = "System rejected preferred mixer attributes; fell back to lossless 32-bit Float.",
                shouldUseFloatOutput = true
            )
        }

        activeBitPerfectDevice = usbDevice
        val useFloat = chosen.format.encoding == AudioFormat.ENCODING_PCM_FLOAT
        return UsbRouteDecision(
            usbDacConnected = true,
            usbDacName = dacName,
            bitPerfectActivated = true,
            routeLabel = "USB Bit-Perfect: $dacName (${encodingName(chosen.format.encoding)})",
            statusNote = "AudioFlinger bypassed at ${sampleRateHz} Hz. System AudioEffect/EQ is bypassed in Bit-Perfect mode.",
            shouldUseFloatOutput = useFloat
        )
    }

    fun clearBitPerfectRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val dev = activeBitPerfectDevice
            if (dev != null) {
                runCatching {
                    audioManager?.clearPreferredMixerAttributes(mediaAttributes, dev)
                }
            }
        }
        activeBitPerfectDevice = null
    }

    private fun encodingName(encoding: Int): String = when {
        encoding == AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        encoding == AudioFormat.ENCODING_PCM_FLOAT -> "32-bit Float"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit Packed"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoding == AudioFormat.ENCODING_PCM_32BIT -> "32-bit Int"
        else -> "PCM($encoding)"
    }
}
