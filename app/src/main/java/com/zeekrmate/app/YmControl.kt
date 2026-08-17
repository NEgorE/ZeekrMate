package com.zeekrmate.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.File

class YmControl(context: Context) {

    data class YmOutcome(val success: Boolean, val message: String)

    private val app = context.applicationContext
    private val settings = YmSettings(app)
    private val log = YmLog(app)
    private val sender = TelegramSender()

    fun isRunning(): Boolean {
        return YmSwcService.active
    }

    fun start(): YmOutcome {
        if (YmSwcService.active) {
            return ok(warnExternal("Уже работает, повторный старт не нужен"))
        }
        val denied = needReadLogs()
        if (denied != null) {
            return denied
        }
        settings.enabled = true
        YmSwcService.lastError = null
        ContextCompat.startForegroundService(app, Intent(app, YmSwcService::class.java))
        if (!waitUntil { YmSwcService.active }) {
            settings.enabled = false
            return fail(YmSwcService.lastError ?: "Сервис не поднялся")
        }
        waitUntil { YmSwcService.readerReady || YmSwcService.lastError != null || !YmSwcService.active }
        val error = YmSwcService.lastError
        if (error != null || !YmSwcService.active) {
            settings.enabled = false
            return fail(error ?: "logcat не запустился")
        }
        return ok(warnExternal("Слушатель руля запущен"))
    }

    fun restart(): YmOutcome {
        val denied = needReadLogs()
        if (denied != null) {
            return denied
        }
        if (YmSwcService.active) {
            settings.enabled = false
            app.stopService(Intent(app, YmSwcService::class.java))
            if (!waitUntil { !YmSwcService.active }) {
                return fail("Не удалось остановить слушатель")
            }
        }
        return start().let { started ->
            if (started.success) {
                ok(warnExternal("Слушатель руля перезапущен"))
            } else {
                started
            }
        }
    }

    fun stop(): YmOutcome {
        settings.enabled = false
        if (!YmSwcService.active) {
            return ok("Слушатель и так не работал")
        }
        app.stopService(Intent(app, YmSwcService::class.java))
        if (!waitUntil { !YmSwcService.active }) {
            return fail("Не остановился: сервис ещё жив")
        }
        return ok("Слушатель руля остановлен")
    }

    fun sendLastLog(botToken: String, telegramLink: String): YmOutcome {
        if (botToken.isBlank()) {
            return fail("Нет API-ключа бота. Укажите его во вкладке SentryMod Sender")
        }
        val target = TelegramLink.parse(telegramLink)
        if (target == null) {
            return fail("Укажите ссылку на чат или топик Telegram")
        }
        val lines = log.lastLines(1000)
        if (lines.isEmpty()) {
            return fail("Лог пуст, нечего отправлять")
        }
        val out = File(app.cacheDir, "ym_last1000.log")
        val sent = runCatching {
            out.writeText(lines.joinToString("\n", postfix = "\n"))
            sender.sendDocument(
                botToken,
                target.chatId,
                target.topicId,
                out,
                filename = "ym_last1000.log",
                caption = "ym_last1000.log (${lines.size})"
            )
        }.getOrElse { Result.failure(it) }
        out.delete()
        if (sent.isFailure) {
            val reason = sent.exceptionOrNull()?.message.orEmpty().take(90)
            return fail(if (reason.isBlank()) "Telegram не принял" else "Telegram не принял: $reason")
        }
        return ok("Отправлен файл ym_last1000.log (${lines.size} строк)")
    }

    private fun needReadLogs(): YmOutcome? {
        val granted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.READ_LOGS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            return null
        }
        return fail(
            "Нет READ_LOGS. С ПК: adb shell pm grant com.zeekrmate.app android.permission.READ_LOGS"
        )
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(20) {
            if (condition()) {
                return true
            }
            Thread.sleep(100)
        }
        return condition()
    }

    private fun warnExternal(message: String): String {
        return if (externalScriptAlive()) {
            "$message. ADB-скрипт ещё жив — возможны двойные нажатия"
        } else {
            message
        }
    }

    private fun externalScriptAlive(): Boolean {
        val ps = runCatching {
            val process = ProcessBuilder("sh", "-c", "ps -A -o pid,args 2>/dev/null || ps -A")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return ps.lineSequence().any { line ->
            line.contains("ym-swc") && !line.contains("grep")
        }
    }

    private fun ok(message: String): YmOutcome {
        log.info(message)
        return YmOutcome(true, message)
    }

    private fun fail(message: String): YmOutcome {
        log.error(message)
        return YmOutcome(false, message)
    }
}
