package com.syed.endcall

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager

/**
 * Works out whether a notification represents an ANSWERED call, and finds the
 * app's own hang-up action inside it.
 *
 * Everything here is matched on structure (category, template, ongoing flag,
 * audio mode) rather than on English text, because her phone may well be in
 * Bengali and every visible string would then be different.
 */
object CallNotifications {

    /** Apps we accept the weaker "ongoing + audio in call" evidence from. */
    private val KNOWN_CALL_APPS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.imo.android.imoim",
        "com.imo.android.imoimbeta",
        "com.facebook.orca",
        "com.facebook.mlite",
        "org.telegram.messenger",
        "com.viber.voip",
        "com.skype.raider",
        "com.microsoft.teams",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.apps.tachyon" // Meet / Duo
    )

    /**
     * Titles are the LAST resort for finding the hang-up action, so the list
     * covers English plus the Bengali forms WhatsApp uses. Structure-based
     * detection above should normally win before we get here.
     */
    private val HANGUP_WORDS = listOf(
        "hang up", "hangup", "end call", "end", "disconnect",
        "কল কাটুন", "কল শেষ", "শেষ করুন", "কেটে দিন"
    )

    /**
     * Actions that mean the call is still RINGING. Observed on a real call:
     * at the instant of answering, WhatsApp re-posts with the full-screen
     * intent already cleared and audio already routed, but still carrying
     * Decline/Answer. Treating "Decline" as a hang-up in that window would
     * fire a decline at a live call.
     */
    private val ANSWER_WORDS = listOf(
        "answer", "accept", "pick up",
        "উত্তর", "গ্রহণ", "ধরুন", "রিসিভ"
    )

    /**
     * Structural ring test, independent of language: a ringing notification
     * offers a choice (2+ actions), an answered one offers only "Hang Up".
     */
    fun looksLikeRinging(n: Notification): Boolean {
        val actions = n.actions?.toList().orEmpty()
        if (actions.any { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                ANSWER_WORDS.any { t.contains(it) }
            }
        ) return true
        // 2+ actions with no explicit hang-up intent is a choice, i.e. a ring.
        return actions.size >= 2 && hangUpExtra(n) == null
    }

    private fun hangUpExtra(n: Notification): PendingIntent? {
        @Suppress("DEPRECATION")
        return n.extras.getParcelable("android.hangUpIntent")
    }

    /**
     * True only for a call that has actually been ANSWERED.
     *
     * Measured on a real WhatsApp call, a single incoming ring posts TWO
     * notifications, both with category=call and both ongoing:
     *   - voip_notification_16   CallStyle, callType=1, has a fullScreenIntent
     *   - silent_notifications_15 BigTextStyle, NO callType, NO fullScreenIntent
     *
     * So neither "category is call" nor "has no fullScreenIntent" is sufficient
     * on its own — the second record passes both tests while the phone is still
     * ringing. The audio mode is the reliable discriminator: ringing is
     * MODE_RINGTONE, an answered call is MODE_IN_COMMUNICATION.
     */
    fun isAnsweredCall(ctx: Context, n: Notification): Boolean {
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        if (!isCallish(n)) return false

        // Explicitly incoming: a ring screen, or CallStyle saying so.
        if (n.fullScreenIntent != null) return false
        if (callType(n) == CALL_TYPE_INCOMING) return false

        // Still offering Answer/Decline: the call is not answered yet, however
        // the audio system may already have switched over.
        if (looksLikeRinging(n)) return false

        // CallStyle telling us outright that the call is up is trustworthy.
        if (callType(n) == CALL_TYPE_ONGOING) return true

        // Otherwise the audio system decides. Ringing never reaches this state.
        return isAudioInCall(ctx)
    }

    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2

    private fun callType(n: Notification): Int = n.extras.getInt("android.callType", 0)

    fun isCallish(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_CALL) return true
        val template = n.extras.getString("android.template").orEmpty() +
            n.extras.getString("androidx.core.app.extra.COMPAT_TEMPLATE").orEmpty()
        return template.contains("CallStyle")
    }

    /** Renders a notification's actions so a real call tells us their real titles. */
    fun describeActions(n: Notification): String {
        val actions = n.actions?.toList().orEmpty()
        if (actions.isEmpty()) return "no actions"
        return actions.mapIndexed { i, a -> "[$i]'${a.title}'" }.joinToString(" ")
    }

    /**
     * Weaker evidence for older builds that don't tag the notification as a
     * call: a known call app holding an ongoing notification WHILE the audio
     * system is routed for a voice call.
     *
     * The audio mode is read on demand, only when a candidate notification
     * arrives — never polled.
     */
    fun looksLikeCallByAudio(ctx: Context, pkg: String, n: Notification): Boolean {
        if (n.fullScreenIntent != null) return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        if (callType(n) == CALL_TYPE_INCOMING) return false
        if (pkg !in KNOWN_CALL_APPS) return false
        return isAudioInCall(ctx)
    }

    fun isKnownCallApp(pkg: String): Boolean = pkg in KNOWN_CALL_APPS

    fun isAudioInCall(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL
    }

    /** Result of looking for a hang-up action, with a note for diagnostics. */
    data class HangUp(val intent: PendingIntent?, val how: String)

    fun findHangUp(n: Notification): HangUp {
        // Best case (Android 12+): the platform hands us the intent directly.
        // Verified working against a live WhatsApp call.
        hangUpExtra(n)?.let { return HangUp(it, "CallStyle hangUpIntent") }

        // Never take a hang-up out of a ringing notification: its "Decline"
        // is not the same thing as ending an answered call.
        if (looksLikeRinging(n)) return HangUp(null, "ringing actions — no hang-up taken")

        val actions = n.actions?.toList().orEmpty()
        if (actions.isEmpty()) return HangUp(null, "no actions on notification")

        // An ongoing call notification that exposes exactly one action — that
        // action is the hang-up. True of WhatsApp on older Android.
        if (actions.size == 1) {
            return HangUp(actions[0].actionIntent, "sole action '${actions[0].title}'")
        }

        actions.forEachIndexed { i, a ->
            val title = a.title?.toString()?.lowercase().orEmpty()
            if (HANGUP_WORDS.any { title.contains(it) }) {
                return HangUp(a.actionIntent, "action[$i] '${a.title}'")
            }
        }

        return HangUp(null, "no hang-up match in [${actions.joinToString { it.title?.toString() ?: "?" }}]")
    }
}
