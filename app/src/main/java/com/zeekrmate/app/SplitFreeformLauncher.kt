package com.zeekrmate.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager

class SplitFreeformLauncher(private val context: Context) {

    data class PairLaunch(
        val maps: Intent,
        val music: Intent,
        val mapsBounds: Rect,
        val musicBounds: Rect,
    )

    fun prepare(): PairLaunch {
        val maps = launchIntent(MAPS_PACKAGES) ?: error("Нет Яндекс Навигатора")
        val music = launchIntent(MUSIC_PACKAGES) ?: error("Нет Яндекс Музыки")
        val area = contentArea()
        val splitX = area.left + (area.width() * MAPS_WIDTH_PERCENT / 100)
        return PairLaunch(
            maps,
            music,
            Rect(area.left, area.top, splitX, area.bottom),
            Rect(splitX, area.top, area.right, area.bottom)
        )
    }

    fun startFreeform(intent: Intent, bounds: Rect) {
        val options = ActivityOptions.makeBasic()
        options.launchBounds = bounds
        applyWindowingMode(options, WINDOWING_FREEFORM)
        context.startActivity(intent, options.toBundle())
    }

    private fun launchIntent(packages: List<String>): Intent? {
        val pm = context.packageManager
        for (pkg in packages) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            return intent
        }
        return null
    }

    private fun contentArea(): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val screen = metrics.bounds
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        return Rect(
            screen.left + bars.left,
            screen.top + bars.top,
            screen.right - bars.right,
            screen.bottom - bars.bottom
        )
    }

    private fun applyWindowingMode(options: ActivityOptions, mode: Int) {
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, mode)
        }
    }

    companion object {
        const val MAPS_WIDTH_PERCENT = 70
        const val SECOND_PANE_DELAY_MS = 450L
        const val WINDOWING_FREEFORM = 5
        val MAPS_PACKAGES = listOf("ru.yandex.yandexnavi")
        val MUSIC_PACKAGES = listOf("ru.yandex.music", "com.yandex.music")
    }
}
