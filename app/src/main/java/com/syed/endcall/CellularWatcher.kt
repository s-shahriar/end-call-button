package com.syed.endcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Detects ordinary cellular calls. Like the notification listener this is a
 * push callback from the telephony stack — we never poll the call state.
 *
 * Ringing is ignored on purpose: only OFFHOOK (answered) shows the button.
 */
class CellularWatcher(private val ctx: Context) {

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var callback: TelephonyCallback? = null

    fun start() {
        val tm = this.tm ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handle(state)
        }
        callback = cb
        runCatching { tm.registerTelephonyCallback(ctx.mainExecutor, cb) }
    }

    fun stop() {
        val tm = this.tm ?: return
        callback?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        callback = null
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
