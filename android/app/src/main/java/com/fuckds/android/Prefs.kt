package com.fuckds.android

import android.content.Context

object Prefs {
    private const val NAME = "fuckds-prefs"
    private const val KEY_AUTO_START = "autoStart"
    private const val KEY_KEEP_AWAKE = "keepAwake"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoStart(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    fun keepAwake(context: Context): Boolean = sp(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
    }
}
