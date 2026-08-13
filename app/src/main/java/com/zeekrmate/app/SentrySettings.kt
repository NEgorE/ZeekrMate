package com.zeekrmate.app

import android.content.Context

class SentrySettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    var deleteAfterSend: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AFTER_SEND, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DELETE_AFTER_SEND, value).commit()
        }

    var botToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).commit()
        }

    var telegramLink: String
        get() = prefs.getString(KEY_LINK, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LINK, value.trim()).commit()
        }

    var folder: String
        get() = prefs.getString(KEY_FOLDER, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_FOLDER, value.trim()).commit()
        }

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(1)
        set(value) {
            prefs.edit().putInt(KEY_INTERVAL, value.coerceAtLeast(1)).commit()
        }

    var sentKeys: Set<String>
        get() = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty().toSet()
        set(value) {
            prefs.edit().putStringSet(KEY_SENT, value.toSet()).apply()
        }

    var lastStatus: String
        get() = prefs.getString(KEY_STATUS, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_STATUS, value).commit()
        }

    var lastScanAt: Long
        get() = prefs.getLong(KEY_LAST_SCAN, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SCAN, value).commit()
        }

    fun isConfigured(): Boolean {
        return botToken.isNotBlank() && folder.isNotBlank() && TelegramLink.parse(telegramLink) != null
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 5
        private const val PREFS = "sentry_mod_sender"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DELETE_AFTER_SEND = "delete_after_send"
        private const val KEY_TOKEN = "bot_token"
        private const val KEY_LINK = "telegram_link"
        private const val KEY_FOLDER = "folder"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_SENT = "sent_keys"
        private const val KEY_STATUS = "last_status"
        private const val KEY_LAST_SCAN = "last_scan_at"
    }
}
