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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SentrySettings
    private var ignoreSwitch = false
    private var onStorageGranted: (() -> Unit)? = null
    private var pendingAllFilesForDelete = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshSentryStatus()
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
        binding.appVersion.text = BuildConfig.VERSION_NAME
        binding.paneAbout.readmeText.movementMethod = LinkMovementMethod.getInstance()
        binding.paneAbout.readmeText.text = formatReadme(
            getString(R.string.about_text, BuildConfig.VERSION_NAME)
        )
        bindSentryForm()
        binding.menuSentry.setOnClickListener { showSentry() }
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
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        persistSentryForm()
    }

    private fun bindSentryForm() {
        val form = binding.paneSentry
        form.sentryEnabled.isChecked = settings.enabled
        form.sentryDeleteAfterSend.isChecked = settings.deleteAfterSend
        form.sentryBotToken.setText(settings.botToken)
        form.sentryLink.setText(settings.telegramLink)
        form.sentryFolder.setText(settings.folder)
        form.sentryInterval.setText(settings.intervalMinutes.toString())
        refreshSentryStatus()
        form.sentryDownloadLog.setOnClickListener { downloadSentryLog() }
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

    private fun showSentry() {
        persistSentryForm()
        binding.menuSentry.isSelected = true
        binding.menuAbout.isSelected = false
        binding.paneSentry.root.visibility = View.VISIBLE
        binding.paneAbout.root.visibility = View.GONE
    }

    private fun showAbout() {
        persistSentryForm()
        binding.menuSentry.isSelected = false
        binding.menuAbout.isSelected = true
        binding.paneSentry.root.visibility = View.GONE
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
