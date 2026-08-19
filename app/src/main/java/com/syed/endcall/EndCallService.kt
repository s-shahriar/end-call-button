package com.syed.endcall

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service. It exists for one reason: it may host a
 * TYPE_ACCESSIBILITY_OVERLAY window, which floats above every app and the
 * keyguard without needing the "draw over other apps" permission.
 *
 * It does no work in onAccessibilityEvent, and does not filter key events.
 */
class EndCallService : AccessibilityService() {

    private var overlay: OverlayController? = null
    private var cellular: CellularWatcher? = null

    private val callListener: (ActiveCall?) -> Unit = { call ->
        overlay?.onCallChanged(call)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        cellular = CellularWatcher(this).also { it.start() }
        CallRegistry.addListener(callListener)
        CallRegistry.note("service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty. Detection is done by the notification listener,
        // which costs nothing; walking the view tree here would not.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        CallRegistry.removeListener(callListener)
        cellular?.stop()
        overlay?.destroy()
        overlay = null
        cellular = null
        return super.onUnbind(intent)
    }
}
