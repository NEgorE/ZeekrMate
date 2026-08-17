package com.zeekrmate.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class ZeekrMateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = SentrySettings(this)
        if (settings.enabled && settings.isConfigured()) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SentryScanService::class.java)
            )
        }
        val ym = YmSettings(this)
        if (ym.enabled) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, YmSwcService::class.java)
            )
        }
    }
}
