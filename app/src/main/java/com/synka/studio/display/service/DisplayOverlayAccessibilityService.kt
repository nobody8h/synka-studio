package com.synka.studio.display.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.synka.studio.display.engine.DisplayEqEngine
import java.lang.ref.WeakReference

class DisplayOverlayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var serviceRef: WeakReference<DisplayOverlayAccessibilityService>? = null

        fun currentInstance(): DisplayOverlayAccessibilityService? = serviceRef?.get()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        DisplayEqEngine.getInstance(applicationContext).reapplyNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        serviceRef = null
        DisplayEqEngine.getInstance(applicationContext).reapplyNow()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceRef = null
        super.onDestroy()
    }
}
