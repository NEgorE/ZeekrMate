package com.zeekrmate.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SentryLog(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SentrySettings(appContext)
    private val file = File(appContext.filesDir, FILE_NAME)
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun info(message: String) {
        write("I", message, null)
    }

    fun error(message: String, error: Throwable? = null) {
        write("E", message, error)
    }

    private fun write(level: String, message: String, error: Throwable?) {
        val line = "${time.format(Date())} $level $message" +
            (error?.message?.let { " ($it)" } ?: "")
        if (level == "E") {
            Log.e(TAG, message, error)
        } else {
            Log.i(TAG, message)
        }
        settings.lastStatus = line
        runCatching {
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) {
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(keep)
            }
        }
    }

    companion object {
        const val TAG = "ZeekrMate"
        const val FILE_NAME = "sentry.log"
        private const val MAX_BYTES = 80_000L
    }
}
