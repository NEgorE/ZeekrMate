package com.zeekrmate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SentryScanService : Service() {

    private val settings by lazy { SentrySettings(this) }
    private val log by lazy { SentryLog(this) }
    private val sender = TelegramSender()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var scheduled: ScheduledFuture<*>? = null

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
        startSchedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            startSchedule()
        }
        if (!settings.enabled || !settings.isConfigured()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduled?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startSchedule() {
        scheduled?.cancel(false)
        log.info("Сервис запущен, интервал ${settings.intervalMinutes} мин")
        executor.execute { runCatching { scanOnce() }.onFailure { log.error("Скан упал", it) } }
        val minutes = settings.intervalMinutes.toLong()
        scheduled = executor.scheduleWithFixedDelay(
            { runCatching { scanOnce() }.onFailure { log.error("Скан упал", it) } },
            minutes,
            minutes,
            TimeUnit.MINUTES
        )
    }

    private fun scanOnce() {
        settings.lastScanAt = System.currentTimeMillis()
        if (!settings.enabled) {
            log.info("Выключено, сервис останавливается")
            stopSelf()
            return
        }
        val folder = File(settings.folder)
        if (!folder.exists()) {
            log.error("Папка не существует: ${folder.path}")
            return
        }
        if (!folder.isDirectory) {
            log.error("Это не папка: ${folder.path}")
            return
        }
        val listed = folder.listFiles()
        if (listed == null && !folder.canRead()) {
            log.error("Нет доступа к папке: ${folder.path}")
            return
        }
        val target = TelegramLink.parse(settings.telegramLink)
        if (target == null) {
            log.error("Не разобрал ссылку Telegram")
            return
        }
        val now = System.currentTimeMillis()
        val fromFolder = listed
            ?.filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
            .orEmpty()
        val fromStore = videosFromMediaStore(folder)
        val allVideos = (fromFolder + fromStore).distinctBy { it.absolutePath }
        log.info(
            "Папка ${folder.path}: записей ${listed?.size ?: 0}, видео с диска ${fromFolder.size}, из MediaStore ${fromStore.size}"
        )
        val ready = allVideos.filter { now - it.lastModified() > SETTLE_MS }
        val waiting = allVideos.size - ready.size
        val sent = settings.sentKeys.toMutableSet()
        var uploaded = 0
        var failed = 0
        ready.forEach { file ->
            val key = fileKey(file)
            if (key in sent) {
                return@forEach
            }
            if (file.length() > MAX_TELEGRAM_BYTES) {
                log.info("Пропуск ${file.name}: больше 49 МБ")
                sent += key
                settings.sentKeys = sent
                return@forEach
            }
            log.info("Отправка ${file.name} → ${target.chatId} / ${target.topicId}")
            val result = sender.sendVideo(
                settings.botToken,
                target.chatId,
                target.topicId,
                file
            )
            result.fold(
                onSuccess = {
                    uploaded += 1
                    sent += key
                    settings.sentKeys = sent
                    log.info("Отправлено ${file.name}")
                    if (settings.deleteAfterSend) {
                        deleteSentVideo(file)
                    } else {
                        log.info("Удаление выключено, ${file.name} оставлен")
                    }
                },
                onFailure = { error ->
                    failed += 1
                    log.error("Не отправил ${file.name}", error)
                }
            )
        }
        log.info(
            "Скан: видео ${allVideos.size}, ждут записи $waiting, отправлено $uploaded, ошибок $failed, чат ${target.chatId}, топик ${target.topicId.ifBlank { "нет" }}"
        )
    }

    private fun videosFromMediaStore(folder: File): List<File> {
        val prefix = runCatching { folder.canonicalFile.absolutePath }
            .getOrDefault(folder.absolutePath)
            .trimEnd('/')
        val found = LinkedHashMap<String, File>()
        runCatching {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DATA),
                "${MediaStore.Video.Media.DATA} LIKE ?",
                arrayOf("$prefix/%"),
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (idx < 0) {
                    return@use
                }
                while (cursor.moveToNext()) {
                    val path = cursor.getString(idx) ?: continue
                    val file = File(path)
                    val parent = file.parent ?: continue
                    val parentPath = parent.trimEnd('/')
                    if (parentPath == prefix && file.extension.lowercase() in VIDEO_EXTENSIONS) {
                        found[file.absolutePath] = file
                    }
                }
            }
        }.onFailure { log.error("MediaStore не открылся", it) }
        return found.values.toList()
    }

    private fun deleteSentVideo(file: File) {
        if (!Environment.isExternalStorageManager()) {
            log.error("Не удалил ${file.name}: нет доступа ко всем файлам")
            return
        }
        log.info("Удаляю ${file.name}")
        if (!file.exists()) {
            log.info("Файл уже отсутствует: ${file.name}")
            return
        }
        if (file.delete() && !file.exists()) {
            log.info("Удалено с диска: ${file.name}")
            return
        }
        log.info("File.delete не сработал для ${file.name}, пробую MediaStore")
        val removed = deleteViaMediaStore(file)
        if (removed || !file.exists()) {
            log.info("Удалено через MediaStore: ${file.name}")
        } else {
            log.error("Не удалось удалить ${file.name}")
        }
    }

    private fun deleteViaMediaStore(file: File): Boolean {
        val id = videoIdFor(file) ?: run {
            log.error("MediaStore не нашёл ${file.name}")
            return false
        }
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val removed = runCatching {
            contentResolver.delete(uri, null, null)
        }.onFailure { error ->
            log.error("MediaStore.delete ${file.name}", error)
        }.getOrDefault(0)
        return removed > 0
    }

    private fun videoIdFor(file: File): Long? {
        return runCatching {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA}=?",
                arrayOf(file.absolutePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun fileKey(file: File): String {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sentry_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.sentry_notification_title))
            .setContentText(getString(R.string.sentry_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RESTART = "com.zeekrmate.app.SENTRY_RESTART"
        private const val CHANNEL_ID = "sentry_mod_sender"
        private const val NOTIFICATION_ID = 41
        private const val SETTLE_MS = 15_000L
        private const val MAX_TELEGRAM_BYTES = 49L * 1024L * 1024L
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "m4v", "ts")
    }
}
