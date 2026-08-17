package com.zeekrmate.app

import android.content.Context

class YmSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var telegramLink: String
        get() = prefs.getString(KEY_LINK, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LINK, value.trim()).commit()
        }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    companion object {
        private const val PREFS = "ym_control"
        private const val KEY_LINK = "telegram_link"
        private const val KEY_ENABLED = "enabled"
    }
}
