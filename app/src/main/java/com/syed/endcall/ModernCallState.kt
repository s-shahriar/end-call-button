package com.syed.endcall

import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * The Android 12+ telephony listener, kept in its own class on purpose.
 *
 * [TelephonyCallback] does not exist before API 31. Naming it anywhere in
 * [CellularWatcher] — even in a field type that a version check guards — makes
 * ART resolve it while verifying that class, which threw NoClassDefFoundError
 * on Android 11 and killed the accessibility service the moment it connected,
 * so the overlay could never be drawn. Isolating it here means the class is
 * only ever loaded on a device that has it.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object ModernCallState {

    /** Returns the registered callback as Any, so the caller need not name the type. */
    fun register(ctx: Context, tm: TelephonyManager, onState: (Int) -> Unit): Any? {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        return runCatching { tm.registerTelephonyCallback(ctx.mainExecutor, cb) }
            .map { cb as Any }
            .getOrNull()
    }

    fun unregister(tm: TelephonyManager, cb: Any) {
        if (cb is TelephonyCallback) runCatching { tm.unregisterTelephonyCallback(cb) }
    }
}
