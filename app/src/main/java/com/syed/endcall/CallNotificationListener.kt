package com.syed.endcall

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Call detection. Every callback here is pushed to us by the system, so this
 * service costs nothing at all while idle: no timers, no wakelocks, no polling.
 */
class CallNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        // A call may already have been running when we were bound (e.g. after a
        // reboot or an app update). Catch up once, then go back to pure push.
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // The call app pulling its ongoing notification IS the end-of-call signal.
        CallRegistry.onCallEnded(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return

        // Record every call-shaped notification, answered or not. dumpsys does
        // not print action titles, but as a listener we read them directly --
        // and those titles are how we find the real "End call" action.
        if (CallNotifications.isCallish(n)) {
            CallRegistry.note(
                "notif ${sbn.packageName} ongoing=${n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0}" +
                    " fsi=${n.fullScreenIntent != null}" +
                    " audio=${CallNotifications.isAudioInCall(this)}" +
                    " actions=${CallNotifications.describeActions(n)}"
            )
        }

        // Record every call-shaped notification, answered or not. dumpsys does
        // not print action titles, but as a listener we can read them directly
        // — and those titles are how we find the real "End call" action.
        if (CallNotifications.isAnsweredCall(this, n)) {
            register(sbn, "tagged call")
            return
        }

        if (CallNotifications.looksLikeCallByAudio(this, sbn.packageName, n)) {
            register(sbn, "known app + audio in call")
            return
        }

        // Known call app, ongoing notification, but the audio system hasn't
        // switched over yet — this races at the very start of a call. Re-check
        // ONCE after a moment. A single one-shot, never a repeating timer.
        if (isPendingCandidate(sbn, n)) {
            main.postDelayed({
                if (CallNotifications.looksLikeCallByAudio(this, sbn.packageName, n)) {
                    register(sbn, "known app + audio in call (delayed)")
                }
            }, 1500)
            return
        }

        // Not a call any more (or never was): make sure we aren't still holding it.
        CallRegistry.onCallEnded(sbn.key)
    }

    private fun isPendingCandidate(sbn: StatusBarNotification, n: android.app.Notification): Boolean =
        n.fullScreenIntent == null &&
            n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 &&
            CallNotifications.isKnownCallApp(sbn.packageName) &&
            CallRegistry.active == null

    private fun register(sbn: StatusBarNotification, why: String) {
        val n = sbn.notification
        val hangUp = CallNotifications.findHangUp(n)
        CallRegistry.onCallStarted(
            ActiveCall(
                key = sbn.key,
                packageName = sbn.packageName,
                source = CallSource.NOTIFICATION,
                hangUpIntent = hangUp.intent,
                contentIntent = n.contentIntent,
                debug = "$why; hang-up: ${hangUp.how}"
            )
        )
    }
}
