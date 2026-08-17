package com.zeekrmate.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import com.zeekrmate.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SentrySettings
    private lateinit var ymSettings: YmSettings
    private val ym by lazy { YmControl(this) }
    private val ymExecutor = Executors.newSingleThreadExecutor()
    private var ymBusy = false
    private val ymNoticeClear = mutableMapOf<TextView, Runnable>()
    private var ignoreSwitch = false
    private var onStorageGranted: (() -> Unit)? = null
    private var pendingAllFilesForDelete = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshSentryStatus()
            refreshYmRunning()
            statusHandler.postDelayed(this, 1_500)
        }
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMediaAccess()) {
            finishStorageGrant()
        } else {
            requestAllFilesAccess()
        }
    }
    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingAllFilesForDelete) {
            applyAllFilesForDelete()
            return@registerForActivityResult
        }
        if (hasMediaAccess()) {
            finishStorageGrant()
        } else {
            denyStorageAccess()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        applyTransparentScreenInsets()
        settings = SentrySettings(this)
        ymSettings = YmSettings(this)
        binding.appVersion.text = BuildConfig.VERSION_NAME
        binding.paneAbout.readmeText.movementMethod = LinkMovementMethod.getInstance()
        binding.paneAbout.readmeText.text = formatReadme(
            getString(R.string.about_text, BuildConfig.VERSION_NAME)
        )
        bindSentryForm()
        bindYmForm()
        binding.menuSentry.setOnClickListener { showSentry() }
        binding.menuYm.setOnClickListener { showYm() }
        binding.menuAbout.setOnClickListener { showAbout() }
        showSentry()
        statusHandler.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        if (pendingAllFilesForDelete) {
            applyAllFilesForDelete()
        }
        if (onStorageGranted != null && hasMediaAccess()) {
            finishStorageGrant()
        }
        refreshSentryStatus()
        if (binding.paneYm.root.visibility == View.VISIBLE) {
            refreshYmPane()
        }
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusTicker)
        ymNoticeClear.values.forEach { statusHandler.removeCallbacks(it) }
        ymExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        persistSentryForm()
        persistYmForm()
    }

    private fun bindSentryForm() {
        val form = binding.paneSentry
        form.sentryEnabled.isChecked = settings.enabled
        form.sentryDeleteAfterSend.isChecked = settings.deleteAfterSend
        form.sentrySendLogIfNoVideo.isChecked = settings.sendLogIfNoVideo
        form.sentryBotToken.setText(settings.botToken)
        form.sentryLink.setText(settings.telegramLink)
        form.sentryFolder.setText(settings.folder)
        form.sentryInterval.setText(settings.intervalMinutes.toString())
        refreshSentryStatus()
        form.sentryDownloadLog.setOnClickListener { downloadSentryLog() }
        form.sentrySendLog.setOnClickListener { sendSentryLogToTelegram() }
        form.sentrySendLogIfNoVideo.setOnCheckedChangeListener { _, isChecked ->
            settings.sendLogIfNoVideo = isChecked
        }
        form.sentryDeleteAfterSend.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitch) {
                return@setOnCheckedChangeListener
            }
            if (!isChecked) {
                settings.deleteAfterSend = false
                return@setOnCheckedChangeListener
            }
            if (Environment.isExternalStorageManager()) {
                settings.deleteAfterSend = true
                return@setOnCheckedChangeListener
            }
            settings.deleteAfterSend = false
            settings.lastStatus = getString(R.string.sentry_need_all_files)
            refreshSentryStatus()
            pendingAllFilesForDelete = true
            requestAllFilesAccess()
        }
        form.sentryEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitch) {
                return@setOnCheckedChangeListener
            }
            persistSentryForm()
            if (isChecked && !settings.isConfigured()) {
                ignoreSwitch = true
                form.sentryEnabled.isChecked = false
                ignoreSwitch = false
                form.sentryStatus.text = getString(R.string.sentry_error_fields)
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                ensureStorageAccess {
                    settings.enabled = true
                    startSentryService()
                    refreshSentryStatus()
                }
            } else {
                settings.enabled = false
                stopSentryService()
                settings.lastStatus = getString(R.string.sentry_status_idle)
                refreshSentryStatus()
            }
        }
    }

    private fun persistSentryForm() {
        val form = binding.paneSentry
        settings.botToken = form.sentryBotToken.text?.toString().orEmpty()
        settings.telegramLink = form.sentryLink.text?.toString().orEmpty()
        settings.folder = form.sentryFolder.text?.toString().orEmpty()
        if (!pendingAllFilesForDelete) {
            settings.deleteAfterSend = form.sentryDeleteAfterSend.isChecked &&
                Environment.isExternalStorageManager()
        }
        settings.sendLogIfNoVideo = form.sentrySendLogIfNoVideo.isChecked
        settings.intervalMinutes = form.sentryInterval.text?.toString()?.toIntOrNull()
            ?: SentrySettings.DEFAULT_INTERVAL_MINUTES
        if (form.sentryInterval.text.isNullOrBlank()) {
            form.sentryInterval.setText(settings.intervalMinutes.toString())
        }
    }

    private fun refreshSentryStatus() {
        if (!::binding.isInitialized || !::settings.isInitialized) {
            return
        }
        val status = settings.lastStatus.ifBlank {
            if (settings.enabled) {
                getString(R.string.sentry_status_on)
            } else {
                getString(R.string.sentry_status_idle)
            }
        }
        binding.paneSentry.sentryStatus.text = status
        binding.paneSentry.sentryLastScan.text = formatLastScan(settings.lastScanAt)
    }

    private fun formatLastScan(at: Long): String {
        if (at <= 0L) {
            return getString(R.string.sentry_last_scan_never)
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
        return getString(R.string.sentry_last_scan, time)
    }

    private fun downloadSentryLog() {
        val logFile = File(filesDir, SentryLog.FILE_NAME)
        if (!logFile.exists() || logFile.length() == 0L) {
            settings.lastStatus = getString(R.string.sentry_log_missing)
            refreshSentryStatus()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, SentryLog.FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val saved = uri != null && runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                logFile.inputStream().use { input -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        settings.lastStatus = getString(
            if (saved) R.string.sentry_log_saved else R.string.sentry_log_failed
        )
        refreshSentryStatus()
    }

    private fun sendSentryLogToTelegram() {
        persistSentryForm()
        if (settings.botToken.isBlank() || TelegramLink.parse(settings.telegramLink) == null) {
            settings.lastStatus = getString(R.string.sentry_log_send_need_fields)
            refreshSentryStatus()
            return
        }
        val log = SentryLog(this)
        val lines = log.lastLines(1000)
        if (lines.isEmpty()) {
            settings.lastStatus = getString(R.string.sentry_log_missing)
            refreshSentryStatus()
            return
        }
        val target = TelegramLink.parse(settings.telegramLink) ?: return
        val token = settings.botToken
        binding.paneSentry.sentrySendLog.isEnabled = false
        Thread {
            val out = File(cacheDir, "sentry_last1000.log")
            val sent = runCatching {
                out.writeText(lines.joinToString("\n", postfix = "\n"))
                TelegramSender().sendDocument(
                    token,
                    target.chatId,
                    target.topicId,
                    out,
                    filename = "sentry_last1000.log",
                    caption = "sentry_last1000.log (${lines.size})"
                )
            }.getOrElse { Result.failure(it) }
            out.delete()
            val failed = sent.exceptionOrNull()?.message?.take(90)
            statusHandler.post {
                binding.paneSentry.sentrySendLog.isEnabled = true
                settings.lastStatus = if (sent.isSuccess) {
                    getString(R.string.sentry_log_sent, lines.size)
                } else {
                    getString(R.string.sentry_log_send_failed) +
                        (failed?.let { ": $it" } ?: "")
                }
                refreshSentryStatus()
            }
        }.start()
    }

    private fun startSentryService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SentryScanService::class.java).setAction(SentryScanService.ACTION_RESTART)
        )
    }

    private fun ensureStorageAccess(granted: () -> Unit) {
        if (hasMediaAccess()) {
            granted()
            return
        }
        onStorageGranted = granted
        val missing = sentryPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        requestAllFilesAccess()
    }

    private fun finishStorageGrant() {
        val granted = onStorageGranted
        onStorageGranted = null
        granted?.invoke()
    }

    private fun denyStorageAccess() {
        onStorageGranted = null
        settings.enabled = false
        ignoreSwitch = true
        binding.paneSentry.sentryEnabled.isChecked = false
        ignoreSwitch = false
        settings.lastStatus = getString(R.string.sentry_error_permission)
        refreshSentryStatus()
    }

    private fun requestAllFilesAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        val canOpen = intent.resolveActivity(packageManager) != null
        if (!canOpen) {
            if (pendingAllFilesForDelete) {
                applyAllFilesForDelete()
            } else {
                denyStorageAccess()
            }
            return
        }
        allFilesLauncher.launch(intent)
    }

    private fun applyAllFilesForDelete() {
        if (!pendingAllFilesForDelete) {
            return
        }
        pendingAllFilesForDelete = false
        val granted = Environment.isExternalStorageManager()
        settings.deleteAfterSend = granted
        ignoreSwitch = true
        binding.paneSentry.sentryDeleteAfterSend.isChecked = granted
        ignoreSwitch = false
        settings.lastStatus = getString(
            if (granted) R.string.sentry_all_files_ok else R.string.sentry_error_all_files
        )
        refreshSentryStatus()
    }

    private fun hasMediaAccess(): Boolean {
        if (Environment.isExternalStorageManager()) {
            return true
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun sentryPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return permissions
    }

    private fun stopSentryService() {
        stopService(Intent(this, SentryScanService::class.java))
    }

    private fun bindYmForm() {
        val form = binding.paneYm
        form.ymLink.setText(ymSettings.telegramLink)
        form.ymStart.setOnClickListener {
            runYmAction(form.ymStartNotice) { ym.start() }
        }
        form.ymRestart.setOnClickListener {
            runYmAction(form.ymRestartNotice) { ym.restart() }
        }
        form.ymStop.setOnClickListener {
            runYmAction(form.ymStopNotice) { ym.stop() }
        }
        form.ymSendLog.setOnClickListener {
            persistYmForm()
            runYmAction(form.ymSendNotice) {
                ym.sendLastLog(settings.botToken, ymSettings.telegramLink)
            }
        }
    }

    private fun persistYmForm() {
        if (!::binding.isInitialized || !::ymSettings.isInitialized) {
            return
        }
        ymSettings.telegramLink = binding.paneYm.ymLink.text?.toString().orEmpty()
    }

    private fun runYmAction(notice: TextView, action: () -> YmControl.YmOutcome) {
        if (ymBusy) {
            return
        }
        ymBusy = true
        setYmButtonsEnabled(false)
        ymExecutor.execute {
            val outcome = runCatching { action() }.getOrElse { error ->
                YmControl.YmOutcome(false, error.message?.take(90) ?: "Сбой")
            }
            val running = ym.isRunning()
            statusHandler.post {
                ymBusy = false
                setYmButtonsEnabled(true)
                applyYmRunStatus(running)
                showYmNotice(notice, outcome)
            }
        }
    }

    private fun showYmNotice(view: TextView, outcome: YmControl.YmOutcome) {
        view.text = outcome.message
        view.setTextColor(
            ContextCompat.getColor(this, if (outcome.success) R.color.ym_ok else R.color.ym_error)
        )
        ymNoticeClear.remove(view)?.let { statusHandler.removeCallbacks(it) }
        val clear = Runnable { view.text = "" }
        ymNoticeClear[view] = clear
        statusHandler.postDelayed(clear, 20_000)
    }

    private fun refreshYmPane() {
        if (ymBusy) {
            return
        }
        ymExecutor.execute {
            val running = ym.isRunning()
            statusHandler.post {
                applyYmRunStatus(running)
            }
        }
    }

    private fun refreshYmRunning() {
        if (!::binding.isInitialized || binding.paneYm.root.visibility != View.VISIBLE) {
            return
        }
        refreshYmPane()
    }

    private fun applyYmRunStatus(running: Boolean) {
        binding.paneYm.ymRunStatus.setText(
            if (running) R.string.ym_running else R.string.ym_stopped
        )
        binding.paneYm.ymRunStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.ym_ok else R.color.ym_error)
        )
    }

    private fun setYmButtonsEnabled(enabled: Boolean) {
        val form = binding.paneYm
        form.ymStart.isEnabled = enabled
        form.ymRestart.isEnabled = enabled
        form.ymStop.isEnabled = enabled
        form.ymSendLog.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.5f
        form.ymStart.alpha = alpha
        form.ymRestart.alpha = alpha
        form.ymStop.alpha = alpha
        form.ymSendLog.alpha = alpha
    }

    private fun showSentry() {
        persistSentryForm()
        persistYmForm()
        binding.menuSentry.isSelected = true
        binding.menuYm.isSelected = false
        binding.menuAbout.isSelected = false
        binding.paneSentry.root.visibility = View.VISIBLE
        binding.paneYm.root.visibility = View.GONE
        binding.paneAbout.root.visibility = View.GONE
    }

    private fun showYm() {
        persistSentryForm()
        persistYmForm()
        binding.menuSentry.isSelected = false
        binding.menuYm.isSelected = true
        binding.menuAbout.isSelected = false
        binding.paneSentry.root.visibility = View.GONE
        binding.paneYm.root.visibility = View.VISIBLE
        binding.paneAbout.root.visibility = View.GONE
        refreshYmPane()
    }

    private fun showAbout() {
        persistSentryForm()
        persistYmForm()
        binding.menuSentry.isSelected = false
        binding.menuYm.isSelected = false
        binding.menuAbout.isSelected = true
        binding.paneSentry.root.visibility = View.GONE
        binding.paneYm.root.visibility = View.GONE
        binding.paneAbout.root.visibility = View.VISIBLE
    }

    private fun applyTransparentScreenInsets() {
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val topInset = (screenHeight * 0.07f).roundToInt()
        val bottomInset = (screenHeight * 0.05f).roundToInt()
        binding.contentPanel.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL_HORIZONTAL
            topMargin = topInset
            bottomMargin = bottomInset
            leftMargin = 0
            rightMargin = 0
            marginStart = 0
            marginEnd = 0
        }
    }

    private fun formatReadme(markdown: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val bodySizePx = resources.getDimensionPixelSize(R.dimen.readme_body_size)
        val h1SizePx = resources.getDimensionPixelSize(R.dimen.readme_h1_size)
        val h2SizePx = resources.getDimensionPixelSize(R.dimen.readme_h2_size)

        markdown.lineSequence().forEach { raw ->
            val line = cleanInline(raw.trimEnd())
            when {
                line.startsWith("# ") -> appendStyled(
                    builder,
                    line.removePrefix("# "),
                    h1SizePx,
                    bold = true
                )
                line.startsWith("## ") -> appendStyled(
                    builder,
                    line.removePrefix("## "),
                    h2SizePx,
                    bold = true
                )
                line.startsWith("- ") -> appendStyled(
                    builder,
                    "•  ${line.removePrefix("- ")}",
                    bodySizePx
                )
                line.startsWith("```") -> Unit
                else -> appendStyled(builder, line, bodySizePx)
            }
        }
        return linkTelegram(builder)
    }

    private fun linkTelegram(text: SpannableStringBuilder): SpannableStringBuilder {
        val handle = "@Esh_ka"
        val url = "https://t.me/Esh_ka"
        var start = text.indexOf(handle)
        while (start >= 0) {
            val end = start + handle.length
            text.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = text.indexOf(handle, end)
        }
        return text
    }

    private fun cleanInline(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
    }

    private fun appendStyled(
        builder: SpannableStringBuilder,
        text: String,
        sizePx: Int,
        bold: Boolean = false
    ) {
        val start = builder.length
        builder.append(text).append('\n')
        builder.setSpan(
            AbsoluteSizeSpan(sizePx),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (bold) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
