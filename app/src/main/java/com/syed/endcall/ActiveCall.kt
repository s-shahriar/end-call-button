package com.syed.endcall

import android.app.PendingIntent

enum class CallSource { NOTIFICATION, CELLULAR }

/**
 * A call that is currently connected. We only ever track answered calls —
 * ringing/incoming notifications are deliberately ignored so the button can
 * never be used to reject a call by accident.
 */
data class ActiveCall(
    val key: String,
    val packageName: String,
    val source: CallSource,
    /** The call app's own hang-up action, if it published one. Most reliable way to end it. */
    val hangUpIntent: PendingIntent? = null,
    /** Jumps back to the call screen. Usable from the background, unlike startActivity. */
    val contentIntent: PendingIntent? = null,
    /** Kept for diagnostics so we can see what the real device actually published. */
    val debug: String = "",
    /** Test calls from the setup screen must never fire a real hang-up. */
    val isTest: Boolean = false
)
