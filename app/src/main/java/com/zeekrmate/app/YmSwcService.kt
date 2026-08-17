package com.zeekrmate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class YmSwcService : Service() {

    private val log by lazy { YmLog(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val executor = Executors.newSingleThreadExecutor()
    private val stopFlag = AtomicBoolean(false)
    private var logcat: Process? = null
    @Volatile
    private var lastKeyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        active = true
        readerReady = false
        lastError = null
        stopFlag.set(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!YmSettings(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (logcat == null) {
            log.info("Слушатель руля запущен")
            executor.execute { readLogcat() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopFlag.set(true)
        logcat?.destroy()
        logcat = null
        executor.shutdownNow()
        readerReady = false
        active = false
        log.info("Слушатель руля остановлен")
        super.onDestroy()
    }

    private fun readLogcat() {
        val process = runCatching {
            ProcessBuilder("logcat", "-s", "FDBusClient:I")
                .redirectErrorStream(true)
                .start()
        }.onFailure { error ->
            lastError = "не удалось запустить logcat"
            log.error("logcat не стартовал", error)
            YmSettings(this).enabled = false
            stopSelf()
        }.getOrNull() ?: return
        logcat = process
        readerReady = true
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                while (!stopFlag.get()) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
            }
        }.onFailure { error ->
            if (!stopFlag.get()) {
                lastError = error.message?.take(90) ?: "logcat оборвался"
                log.error("Чтение logcat", error)
            }
        }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (!stopFlag.get()) {
            if (lastError == null) {
                lastError = "logcat завершился ($code). Нужен READ_LOGS"
            }
            log.error("logcat exit $code")
            YmSettings(this).enabled = false
            stopSelf()
        }
    }

    private fun handleLine(line: String) {
        if (!line.contains("RECEIVE")) {
            return
        }
        if (!line.contains("MEDIA_UPDATE_MEDIA_STATUS_REQUEST_CONTROL")) {
            return
        }
        val key = when {
            line.contains("\"dataType\":7}") -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            line.contains("\"dataType\":5}") -> KeyEvent.KEYCODE_MEDIA_NEXT
            line.contains("\"dataType\":6}") -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        if (!debounce()) {
            return
        }
        sendMedia(key)
        log.info(
            when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "руль: play/pause"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "руль: next"
                else -> "руль: previous"
            }
        )
    }

    private fun debounce(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyAt < DEBOUNCE_MS) {
            return false
        }
        lastKeyAt = now
        return true
    }

    private fun sendMedia(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ym_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.ym_notification_title))
            .setContentText(getString(R.string.ym_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ym_swc"
        const val NOTIFICATION_ID = 42
        private const val DEBOUNCE_MS = 1000L

        @Volatile
        var active: Boolean = false
            private set

        @Volatile
        var readerReady: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
    }
}
