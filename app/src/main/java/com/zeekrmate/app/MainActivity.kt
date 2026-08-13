package com.zeekrmate.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import com.zeekrmate.app.databinding.ActivityMainBinding
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        applyTransparentScreenInsets()
        binding.readmeText.text = formatReadme(loadReadme())
    }

    private fun applyTransparentScreenInsets() {
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val insetY = (screenHeight * 0.05f).roundToInt()
        binding.contentPanel.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL_HORIZONTAL
            topMargin = insetY
            bottomMargin = insetY
            leftMargin = 0
            rightMargin = 0
            marginStart = 0
            marginEnd = 0
        }
    }

    private fun loadReadme(): String {
        return runCatching {
            assets.open("README.md").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.getOrElse {
            getString(R.string.readme_missing)
        }
    }

    private fun formatReadme(markdown: String): CharSequence {
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
        return builder
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
