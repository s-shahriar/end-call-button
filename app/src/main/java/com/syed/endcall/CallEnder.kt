package com.syed.endcall

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Ends the current call, most reliable mechanism first.
 *
 * No root anywhere. Everything here runs on ordinary permissions:
 *  1. the call app's own hang-up action, taken from its notification
 *  2. the telecom framework
 *  3. clicking the app's own on-screen hang-up button
 *
 * Order matters. The app's own PendingIntent survives WhatsApp reshuffling its
 * obfuscated view IDs every release, which a UI click does not.
 */
object CallEnder {

    private val worker = Executors.newSingleThreadExecutor()

    /** Node hints checked against resource ids — cheap and locale-independent. */
    private val END_CALL_IDS = listOf(
        "end_call_btn", "end_call_button", "endCallButton", "hangup", "hang_up",
        "call_end", "endCall", "decline_btn", "footer_end_call_btn"
    )

    private val END_CALL_WORDS = listOf(
        "end call", "hang up", "hangup", "end", "disconnect",
        "কল কাটুন", "কল শেষ", "শেষ করুন"
    )

    /** Runs off the main thread and reports which method worked, or null. */
    fun endAsync(service: AccessibilityService, call: ActiveCall, onResult: (String?) -> Unit) {
        worker.execute {
            val method = end(service, call)
            service.mainExecutor.execute { onResult(method) }
        }
    }

    private fun end(service: AccessibilityService, call: ActiveCall): String? {
        // The setup screen's test call is not a real call. Run nothing.
        if (call.isTest) {
            Thread.sleep(500)
            return log("dry run (test button — no real call)")
        }

        val audioWasInCall = CallNotifications.isAudioInCall(service)

        // 1. The call app's own hang-up action. Version-proof, no permissions.
        call.hangUpIntent?.let {
            if (runCatching { it.send() }.isSuccess && confirmEnded(service, call, audioWasInCall)) {
                return log("notification action")
            }
        }

        // 2. The telecom framework. Solid for cellular calls, and works for VoIP
        //    apps that register a self-managed ConnectionService.
        if (endViaTelecom(service)) return log("telecom endCall()")

        // 3. Click the app's own hang-up button. Needs the call UI to actually
        //    be on screen, so it only helps some of the time.
        if (endViaAccessibility(service, call.packageName) &&
            confirmEnded(service, call, audioWasInCall)
        ) return log("accessibility click")

        CallRegistry.note("hang-up FAILED for ${call.packageName} (${call.debug})")
        return null
    }

    /**
     * Firing an intent or clicking a button tells us the ACTION was delivered,
     * not that the call actually dropped. Without this the app reports success
     * while the call carries on — the silent failure that would leave her
     * tapping a dead button.
     *
     * The audio signal is only trusted when audio WAS routed for a call to
     * begin with; checking "audio is not in a call" unconditionally makes every
     * method report success whenever there is no call.
     */
    private fun confirmEnded(ctx: Context, call: ActiveCall, audioWasInCall: Boolean): Boolean {
        repeat(8) {
            Thread.sleep(150)
            if (CallRegistry.active?.key != call.key) return true
            if (audioWasInCall && !CallNotifications.isAudioInCall(ctx)) return true
        }
        return false
    }

    private fun log(method: String): String {
        CallRegistry.note("hang-up via $method")
        return method
    }

    private fun endViaTelecom(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        @Suppress("DEPRECATION")
        return runCatching { tm.endCall() }.getOrDefault(false)
    }

    private fun endViaAccessibility(service: AccessibilityService, pkg: String): Boolean {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != pkg) continue

            findEndCallNode(root, 0)?.let { node ->
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    private fun findEndCallNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null

        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()

        val looksRight = (id != null && END_CALL_IDS.any { id.contains(it.lowercase()) }) ||
            END_CALL_WORDS.any { desc.contains(it) }

        if (looksRight && node.isClickable && node.isVisibleToUser) return node

        for (i in 0 until node.childCount) {
            findEndCallNode(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }
}
