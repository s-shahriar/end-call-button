package com.syed.endcall

import android.content.Context

/** Tiny wrapper over SharedPreferences — button position and behaviour. */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("endcall", Context.MODE_PRIVATE)

    /** Which edge the button is parked against. Drives the mirrored layout. */
    var dockedRight: Boolean
        get() = sp.getBoolean("dockedRight", true)
        set(v) = sp.edit().putBoolean("dockedRight", v).apply()

    var posY: Int
        get() = sp.getInt("posY", -1)
        set(v) = sp.edit().putInt("posY", v).apply()

    /** Seconds the confirm pill stays open before folding back to a circle. */
    var confirmSeconds: Int
        get() = sp.getInt("confirmSeconds", 5)
        set(v) = sp.edit().putInt("confirmSeconds", v).apply()

    var buttonDp: Int
        get() = sp.getInt("buttonDp", 64)
        set(v) = sp.edit().putInt("buttonDp", v).apply()

    var lastMethod: String
        get() = sp.getString("lastMethod", "") ?: ""
        set(v) = sp.edit().putString("lastMethod", v).apply()
}
