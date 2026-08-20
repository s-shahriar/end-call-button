package com.syed.endcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Detects ordinary cellular calls. Like the notification listener this is a
 * push callback from the telephony stack — we never poll the call state.
 *
 * Ringing is ignored on purpose: only OFFHOOK (answered) shows the button.
 *
 * Two registration paths. Android 12 replaced PhoneStateListener with
 * TelephonyCallback, and that class does not exist before API 31 — see
 * [ModernCallState] for why it cannot be named in this file at all.
 */
class CellularWatcher(private val ctx: Context) {

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /** The API 31+ callback. Held as Any so loading this class never resolves it. */
    private var modern: Any? = null

    @Suppress("DEPRECATION")
    private var legacy: PhoneStateListener? = null

    fun start() {
        val tm = this.tm ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modern = ModernCallState.register(ctx, tm, ::handle)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
            }
            legacy = listener
            @Suppress("DEPRECATION")
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
        }
    }

    fun stop() {
        val tm = this.tm ?: return
        // Guarded even though `modern` is only ever non-null on 31+: an unguarded
        // reference is exactly the shape of the bug this class exists to avoid.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modern?.let { ModernCallState.unregister(tm, it) }
        }
        modern = null
        @Suppress("DEPRECATION")
        legacy?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
        legacy = null
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK ->
                CallRegistry.onCallStarted(
                    ActiveCall(
                        key = "cellular",
                        packageName = "telecom",
                        source = CallSource.CELLULAR,
                        debug = "telephony OFFHOOK"
                    )
                )
            TelephonyManager.CALL_STATE_IDLE -> CallRegistry.onCellularIdle()
            // CALL_STATE_RINGING is deliberately ignored: answered calls only.
        }
    }
}
