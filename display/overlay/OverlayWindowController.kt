package com.synkastudio.display.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
im[span_115](start_span)[span_115](end_span)port android.os.Looper
import android.provider.Settings
import android.util.Log
import andr[span_64](start_span)[span_64](end_span)[span_66](start_span)[span_66](end_span)oid.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayWindowController(private val appContext: Context) {

    companion object {
        private const val TAG = "SynkaOverlayCtrl"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeWindowManager: WindowManager? = null
    private var overlayView: DisplayOverlayView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var isTrustedA11yWindow: Boolean = false
    private var isAttached: Boolean = false

    fun canDrawStandardOverlay(): Boolean = Settings.canDrawOverlays(appContext)

    /**
     * Attaches or updates the overlay. When [a11yContext] is non-null, upgrades cleanly to
     * TYPE_ACCESSIBILITY_OVERLAY (covers notification shade and bypasses untrusted touch limits).
     */
    fun apply(spec: OverlayRenderSpec, a11yContext: Context?) {
        runOnMain {
            if (!spec.hasVisibleEffect()) {
                detachInternal()
                return@runOnMain
            }

            val wantTrusted = a11yContext != null
            if (!wantTrusted && !canDrawStandardOverlay()) {
                Log.w(TAG, "Overlay requested without SYSTEM_ALERT_WINDOW or AccessibilityService")
                detachInternal()
                return@runOnMain
            }

            if (isAttached && isTrustedA11yWindow != wantTrusted) {
                detachInternal()
            }

            val hostContext = a11yContext ?: appContext
            val maxSafeAlpha = computeSafeWindowAlpha(hostContext, wantTrusted)

            if (!isAttached) {
                attachInternal(hostContext, wantTrusted, maxSafeAlpha, spec)
            } else {
                overlayView?.updateRenderSpec(spec, maxSafeAlpha)
                val params = currentParams
                if (params != null && params.alpha != maxSafeAlpha) {
                    params.alpha = maxSafeAlpha
                    runCatching {
                        activeWindowManager?.updateViewLayout(overlayView, params)
                    }.onFailure { Log.e(TAG, "updateViewLayout failed", it) }
                }
            }
        }
    }

    /**
     * Satisfies Android 15 FGS_SAW_RESTRICTIONS: ensures a visible overlay window is attached
     * on the Main thread before invoking [afterReady] (e.g., ContextCompat.startForegroundService).
     */
    fun ensureVisibleWindowAndRun(a11yContext: Context?, afterReady: () -> Unit) {
        runOnMain {
            val wantTrusted = a11yContext != null
            if (!isAttached && (wantTrusted || canDrawStandardOverlay())) {
                val host = a11yContext ?: appContext
                val safeAlpha = computeSafeWindowAlpha(host, wantTrusted)
                attachInternal(host, wantTrusted, safeAlpha, OverlayRenderSpec(active = true))
            }
            afterReady()
        }
    }

    fun detach() {
        runOnMain { detachInternal() }
    }

    private fun attachInternal(
        hostContext: Context,
        trustedA11y: Boolean,
        windowAlpha: Float,
        initialSpec: OverlayRenderSpec
    ) {
        val wm = hostContext.getSystemService(WindowManager::class.java) ?: return
        val windowType = if (trustedA11y) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        // CRITICAL: Never set FLAG_DIM_BEHIND (breaks touches on Android 16) or FLAG_SECURE.
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = windowAlpha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Prevent Android 11-16 from cropping overlay away from status/navigation bars
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = DisplayOverlayView(hostContext).apply {
            visibility = View.VISIBLE
            updateRenderSpec(initialSpec, windowAlpha)
        }

        runCatching {
            wm.addView(view, params)
            activeWindowManager = wm
            overlayView = view
            currentParams = params
            isTrustedA11yWindow = trustedA11y
            isAttached = true
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach overlay window (trusted=$trustedA11y)", err)
            activeWindowManager = null
            overlayView = null
            currentParams = null
            isAttached = false
        }
    }

    private fun detachInternal() {
        val view = overlayView
        val wm = activeWindowManager
        if (isAttached && view != null && wm != null) {
            runCatching {
                wm.removeViewImmediate(view)
            }.onFailure { err ->
                Log.w(TAG, "removeViewImmediate fallback", err)
                runCatching { wm.removeView(view) }
            }
        }
        overlayView = null
        activeWindowManager = null
        currentParams = null
        isTrustedA11yWindow = false
        isAttached = false
    }

    private fun computeSafeWindowAlpha(context: Context, trustedA11y: Boolean): Float {
        if (trustedA11y) return 1.0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val im = context.getSystemService(InputManager::class.java)
            val maxAllowed = im?.maximumObscuringOpacityForTouch ?: 0.8f
            return (maxAllowed - 0.01f).coerceIn(0.10f, 0.79f)
        }
        return 0.79f
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
