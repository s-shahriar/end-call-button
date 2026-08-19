package com.syed.endcall

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for "is a call running right now".
 *
 * Deliberately has no timers, no polling and no background thread: it is driven
 * purely by push callbacks from the notification listener and the telephony
 * callback. When no call is running this object costs literally nothing.
 */
object CallRegistry {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ActiveCall?) -> Unit>()

    @Volatile
    var active: ActiveCall? = null
        private set

    fun addListener(l: (ActiveCall?) -> Unit) {
        listeners += l
        val current = active
        main.post { l(current) }
    }

    fun removeListener(l: (ActiveCall?) -> Unit) {
        listeners -= l
    }

    fun onCallStarted(call: ActiveCall) {
        val prev = active

        // ALWAYS take the newest intents. WhatsApp reuses one notification id
        // (23) for the ring and for the answered call, so the record we hold
        // from the ring carries Answer/Decline actions, not "End call". Skipping
        // the update because the key matched is what left us firing a stale
        // ringing action at a live call.
        active = call

        val sameCall = prev != null && prev.key == call.key && prev.packageName == call.packageName
        if (!sameCall) {
            note("call started: ${call.packageName} via ${call.source} ${call.debug}")
            publish()
            return
        }

        // Same call, refreshed data. Only tell listeners if what they render
        // changed, so a per-second duration tick does not churn the overlay.
        if ((prev?.hangUpIntent != null) != (call.hangUpIntent != null)) {
            note("call updated: ${call.packageName} ${call.debug}")
            publish()
        }
    }

    fun onCallEnded(key: String) {
        if (active?.key != key) return
        note("call ended: $key")
        active = null
        publish()
    }

    /** Telephony went idle: drop the call only if telephony is what put it there. */
    fun onCellularIdle() {
        if (active?.source != CallSource.CELLULAR) return
        note("cellular idle")
        active = null
        publish()
    }

    private fun publish() {
        val current = active
        main.post { listeners.forEach { it(current) } }
    }

    /** Logcat only (tag EndCallDiag) — nothing is surfaced in the UI. */
    fun note(line: String) = Log.i("EndCallDiag", line).let { }
}
