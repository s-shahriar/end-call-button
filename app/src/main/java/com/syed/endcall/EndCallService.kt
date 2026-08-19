package com.syed.endcall

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service. It exists for two reasons only:
 *  - it may host a TYPE_ACCESSIBILITY_OVERLAY window, and
 *  - it may filter key events, which gives us the volume-down shortcut.
 *
 * It does no work in onAccessibilityEvent. Its event subscription is narrowed
 * to the call apps in accessibility_service_config.xml so the process is not
 * woken every time she changes screen.
 */
class EndCallService : AccessibilityService() {

    private var overlay: OverlayController? = null
    private var cellular: CellularWatcher? = null
    private var prefs: Prefs? = null

    private var lastVolumeDown = 0L
    private var swallowNextVolumeUp = false

    private val callListener: (ActiveCall?) -> Unit = { call ->
        overlay?.onCallChanged(call)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
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

    /**
     * Double-press volume-down ends the call without touching the screen at
     * all — the one path that a mis-touch cannot break.
     *
     * The first press is always passed through so normal volume control still
     * works; only the second press of a quick double is swallowed.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (prefs?.volumeShortcut != true) return false
        if (CallRegistry.active == null) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        if (event.action == KeyEvent.ACTION_UP && swallowNextVolumeUp) {
            swallowNextVolumeUp = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()
        val isDouble = now - lastVolumeDown in 1..700
        lastVolumeDown = now

        if (!isDouble) return false

        lastVolumeDown = 0
        swallowNextVolumeUp = true
        CallRegistry.note("volume-down double press")
        overlay?.endCurrentCall()
        return true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        CallRegistry.removeListener(callListener)
        cellular?.stop()
        overlay?.destroy()
        overlay = null
        cellular = null
        return super.onUnbind(intent)
    }
}
