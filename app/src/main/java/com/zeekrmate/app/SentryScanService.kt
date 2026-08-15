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
        if (!settings.enabled) {
            log.info("Выключено, сервис останавливается")
            stopSelf()
            return
        }
        settings.lastScanAt = System.currentTimeMillis()
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
        val eventFolders = listed
            ?.filter { it.isDirectory && EVENT_FOLDER.matches(it.name) }
            .orEmpty()
            .sortedBy { it.name }
        log.info("SentryMod: папок ${eventFolders.size} формата дата-время")
        val sent = settings.sentKeys.toMutableSet()
        var uploaded = 0
        var failed = 0
        var waiting = 0
        var deletedFolders = 0
        eventFolders.forEach { eventFolder ->
            val result = processEventFolder(eventFolder, target, sent)
            uploaded += result.uploaded
            failed += result.failed
            waiting += result.waiting
            if (result.deleted) {
                deletedFolders += 1
            }
        }
        settings.sentKeys = sent
        log.info(
            "Скан: папок ${eventFolders.size}, ждут записи $waiting, отправлено $uploaded, ошибок $failed, удалено папок $deletedFolders, чат ${target.chatId}, топик ${target.topicId.ifBlank { "нет" }}"
        )
        if (settings.sendLogIfNoVideo && uploaded == 0) {
            val line = settings.lastStatus
            if (line.isNotBlank()) {
                sender.sendText(settings.botToken, target.chatId, target.topicId, line).fold(
                    onSuccess = { log.info("Строка лога отправлена в чат") },
                    onFailure = { error -> log.error("Не отправил строку лога", error) }
                )
            }
        }
    }

    private data class FolderScanResult(
        val uploaded: Int,
        val failed: Int,
        val waiting: Int,
        val deleted: Boolean
    )

    private fun processEventFolder(
        eventFolder: File,
        target: TelegramTarget,
        sent: MutableSet<String>
    ): FolderScanResult {
        val now = System.currentTimeMillis()
        if (!isSettled(eventFolder, now)) {
            log.info("Папка ${eventFolder.name} ещё пишется, пропуск")
            return FolderScanResult(0, 0, 1, false)
        }
        val fromFolder = eventFolder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
            .orEmpty()
        val fromStore = videosFromMediaStore(eventFolder)
        val videos = (fromFolder + fromStore).distinctBy { it.absolutePath }
        if (videos.isEmpty()) {
            log.info("В ${eventFolder.name} нет видео")
            return FolderScanResult(0, 0, 0, false)
        }
        log.info("Папка ${eventFolder.name}: видео ${videos.size}")
        var uploaded = 0
        var failed = 0
        val partsRoot = File(cacheDir, "sentry_parts")
        val partCounts = mutableMapOf<String, Int>()
        videos.forEach { file ->
            val splitDir = File(partsRoot, "${eventFolder.name}_${file.nameWithoutExtension}")
            val parts = playableParts(file, splitDir)
            if (parts.isEmpty()) {
                failed += 1
                log.error("Не нарезал ${eventFolder.name}/${file.name} на проигрываемые части")
                return@forEach
            }
            partCounts[file.absolutePath] = parts.size
            if (parts.size > 1) {
                log.info("${eventFolder.name}/${file.name}: ${file.length()} байт, частей ${parts.size}")
            }
            parts.forEachIndexed { index, part ->
                val key = partKey(file, index)
                if (key in sent) {
                    return@forEachIndexed
                }
                val caption = if (parts.size == 1) {
                    file.name
                } else {
                    "${file.name}\nчасть ${index + 1} из ${parts.size}"
                }
                val filename = if (parts.size == 1) {
                    file.name
                } else {
                    "${file.nameWithoutExtension}_part${index + 1}of${parts.size}.mp4"
                }
                log.info(
                    "Отправка ${eventFolder.name}/${file.name} часть ${index + 1}/${parts.size} → ${target.chatId} / ${target.topicId}"
                )
                val sendResult = sender.sendVideo(
                    settings.botToken,
                    target.chatId,
                    target.topicId,
                    part,
                    caption,
                    filename
                )
                sendResult.fold(
                    onSuccess = {
                        uploaded += 1
                        sent += key
                        settings.sentKeys = sent
                        log.info("Отправлено ${eventFolder.name}/${file.name} часть ${index + 1}/${parts.size}")
                    },
                    onFailure = { error ->
                        failed += 1
                        log.error(
                            "Не отправил ${eventFolder.name}/${file.name} часть ${index + 1}/${parts.size}",
                            error
                        )
                    }
                )
                if (sendResult.isFailure) {
                    return@forEach
                }
            }
        }
        runCatching { partsRoot.deleteRecursively() }
        val allSent = videos.all { file ->
            val count = partCounts[file.absolutePath] ?: return@all false
            count > 0 && (0 until count).all { index -> partKey(file, index) in sent }
        }
        val canDelete = failed == 0 && allSent
        if (!settings.deleteAfterSend) {
            log.info("Удаление выключено, папка ${eventFolder.name} оставлена")
            return FolderScanResult(uploaded, failed, 0, false)
        }
        if (!canDelete) {
            log.info("Папка ${eventFolder.name} не удалена: отправка не завершена")
            return FolderScanResult(uploaded, failed, 0, false)
        }
        val deleted = deleteEventFolder(eventFolder, videos)
        return FolderScanResult(uploaded, failed, 0, deleted)
    }

    private fun playableParts(file: File, outDir: File): List<File> {
        if (file.length() <= TelegramSender.CHUNK_BYTES) {
            return listOf(file)
        }
        return runCatching {
            VideoPartSplitter.split(file, outDir)
        }.onFailure { error ->
            log.error("Нарезка ${file.name}", error)
        }.getOrDefault(emptyList())
    }

    private fun isSettled(folder: File, now: Long): Boolean {
        if (now - folder.lastModified() <= SETTLE_MS) {
            return false
        }
        val children = folder.listFiles() ?: return false
        return children.all { now - it.lastModified() > SETTLE_MS }
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

    private fun deleteEventFolder(eventFolder: File, videos: List<File>): Boolean {
        if (!Environment.isExternalStorageManager()) {
            log.error("Не удалил папку ${eventFolder.name}: нет доступа ко всем файлам")
            return false
        }
        log.info("Удаляю папку ${eventFolder.name}")
        videos.forEach { file ->
            if (file.exists() && !file.delete()) {
                deleteViaMediaStore(file)
            }
        }
        eventFolder.listFiles()?.forEach { child ->
            if (child.isFile && !child.delete()) {
                log.error("Не удалось удалить ${eventFolder.name}/${child.name}")
            } else if (child.isDirectory) {
                child.deleteRecursively()
            }
        }
        val gone = !eventFolder.exists() || eventFolder.delete()
        if (gone || !eventFolder.exists()) {
            log.info("Удалена папка ${eventFolder.name}")
            return true
        }
        log.error("Не удалось удалить папку ${eventFolder.name}")
        return false
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

    private fun partKey(file: File, partIndex: Int): String {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}|$partIndex"
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
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "m4v", "ts")
        private val EVENT_FOLDER = Regex("""^\d{4}[-_]\d{2}[-_]\d{2}[-_]\d{2}[-_]\d{2}[-_]\d{2}$""")
    }
}
