package com.zeekrmate.app

import android.util.Base64
import java.util.concurrent.TimeUnit

class YmControl {

    var lastMessage: String = ""
        private set

    private var privilegedRunner: List<String>? = null
    private var suMissing = false
    private var nextProbeAt = 0L
    private var forceProbe = false

    enum class FileAction {
        EXISTS,
        CREATED,
        MISSING
    }

    fun fileState(): FileAction {
        return if (fileMatches()) FileAction.EXISTS else FileAction.MISSING
    }

    fun createScript(): FileAction {
        forceProbe = true
        if (fileMatches()) {
            exec("chmod 755 '$SCRIPT_PATH'")
            lastMessage = ""
            return FileAction.EXISTS
        }
        val encoded = Base64.encodeToString(SCRIPT.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val write = exec(
            "printf '%s' '$encoded' | base64 -d > '$SCRIPT_PATH' && chmod 755 '$SCRIPT_PATH' && echo ok"
        )
        if (!write.ok) {
            val fallback = exec(
                "cat > '$SCRIPT_PATH' && chmod 755 '$SCRIPT_PATH' && echo ok",
                SCRIPT.toByteArray(Charsets.UTF_8)
            )
            if (!fallback.ok && !fileMatches()) {
                lastMessage = errorText("Не удалось создать скрипт", write, fallback)
                return FileAction.MISSING
            }
        }
        if (!fileMatches()) {
            lastMessage = errorText("Файл записан, но содержимое не совпало", write)
            return FileAction.MISSING
        }
        lastMessage = ""
        return FileAction.CREATED
    }

    fun isRunning(): Boolean {
        val pid = readPid()
        if (pid != null && exec("kill -0 $pid").ok) {
            return true
        }
        val ps = exec("ps -A -f 2>/dev/null || ps -ef 2>/dev/null || ps -A")
        return ps.output.lineSequence().any { line ->
            line.contains("ym-swc") && !line.contains("grep")
        }
    }

    fun start(): Boolean {
        forceProbe = true
        if (isRunning()) {
            lastMessage = ""
            return true
        }
        if (!fileMatches()) {
            lastMessage = "Сначала создайте скрипт"
            return false
        }
        val startCmd =
            "nohup '$SCRIPT_PATH' >/dev/null 2>&1 < /dev/null & echo \$! > '$PID_PATH'; cat '$PID_PATH'"
        val result = exec(startCmd)
        Thread.sleep(250)
        if (!isRunning()) {
            exec(
                "'$SCRIPT_PATH' >/dev/null 2>&1 < /dev/null & echo \$! > '$PID_PATH'; cat '$PID_PATH'"
            )
            Thread.sleep(250)
        }
        if (isRunning()) {
            lastMessage = ""
            return true
        }
        lastMessage = errorText("Не удалось запустить скрипт", result)
        return false
    }

    fun restart(): Boolean {
        forceProbe = true
        stop()
        Thread.sleep(300)
        return start()
    }

    private fun stop() {
        val pids = linkedSetOf<Int>()
        readPid()?.let { pids += it }
        val ps = exec("ps -A -f 2>/dev/null || ps -ef 2>/dev/null || ps -A")
        ps.output.lineSequence().forEach { line ->
            if (!line.contains("ym-swc") || line.contains("grep")) {
                return@forEach
            }
            val parts = line.trim().split(Regex("\\s+"))
            val pid = parts.getOrNull(1)?.toIntOrNull() ?: parts.firstOrNull()?.toIntOrNull()
            if (pid != null) {
                pids += pid
            }
        }
        if (pids.isNotEmpty()) {
            exec("kill ${pids.joinToString(" ")} 2>/dev/null; kill -9 ${pids.joinToString(" ")} 2>/dev/null")
        }
        exec("rm -f '$PID_PATH'")
    }

    fun fileMatches(): Boolean {
        val current = exec("cat '$SCRIPT_PATH'")
        if (!current.ok || current.output.isBlank()) {
            return false
        }
        return normalize(current.output) == normalize(SCRIPT)
    }

    private fun readPid(): Int? {
        return exec("cat '$PID_PATH' 2>/dev/null").output.trim().toIntOrNull()
    }

    private fun normalize(text: String): String {
        return text.replace("\r\n", "\n").replace("\r", "\n").trimEnd()
    }

    private fun errorText(prefix: String, vararg results: ExecResult): String {
        val detail = results
            .map { it.output.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return if (detail.isBlank()) prefix else "$prefix: $detail"
    }

    private fun exec(command: String, input: ByteArray? = null): ExecResult {
        val prefix = runner()
        return runCatching {
            val process = ProcessBuilder(*(prefix + command).toTypedArray())
                .redirectErrorStream(true)
                .start()
            if (input != null) {
                process.outputStream.use { it.write(input) }
            } else {
                process.outputStream.close()
            }
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            }
            reader.start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ExecResult(-1, "timeout")
            } else {
                reader.join(1_000)
                ExecResult(process.exitValue(), output.toString())
            }
        }.getOrElse { error ->
            ExecResult(-1, error.message.orEmpty())
        }
    }

    private fun runner(): List<String> {
        privilegedRunner?.let { return it }
        val now = System.currentTimeMillis()
        if (suMissing || (!forceProbe && now < nextProbeAt)) {
            return listOf("sh", "-c")
        }
        forceProbe = false
        nextProbeAt = now + 30_000
        val options = listOf(
            listOf("su", "-c"),
            listOf("su", "0", "-c")
        )
        for (option in options) {
            when (probe(option)) {
                Probe.OK -> {
                    privilegedRunner = option
                    return option
                }
                Probe.MISSING -> suMissing = true
                Probe.DENIED, Probe.TIMEOUT -> Unit
            }
        }
        return listOf("sh", "-c")
    }

    private fun probe(option: List<String>): Probe {
        return runCatching {
            val process = ProcessBuilder(*(option + "echo ok").toTypedArray())
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            }
            reader.start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Probe.TIMEOUT
            }
            reader.join(1_000)
            when {
                process.exitValue() == 0 && output.contains("ok") -> Probe.OK
                else -> Probe.DENIED
            }
        }.getOrElse { error ->
            val text = error.message.orEmpty()
            if (text.contains("No such file") || text.contains("ENOENT")) {
                Probe.MISSING
            } else {
                Probe.DENIED
            }
        }
    }

    private enum class Probe { OK, MISSING, DENIED, TIMEOUT }

    private data class ExecResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    companion object {
        const val SCRIPT_PATH = "/data/local/tmp/ym-swc.sh"
        const val PID_PATH = "/data/local/tmp/ym-swc.pid"
        const val SCRIPT =
            "#!/system/bin/sh\n" +
                "logcat -c\n" +
                "logcat FDBusClient:I *:S | while IFS= read -r line\n" +
                "do\n" +
                "  echo \"\$line\" | grep -q \"RECEIVE\" || continue\n" +
                "  echo \"\$line\" | grep -q \"MEDIA_UPDATE_MEDIA_STATUS_REQUEST_CONTROL\" || continue\n" +
                "  echo \"\$line\" | grep -q '\"dataType\":5' && input keyevent 87\n" +
                "  echo \"\$line\" | grep -q '\"dataType\":6' && input keyevent 88\n" +
                "  echo \"\$line\" | grep -q '\"dataType\":7' && input keyevent 85\n" +
                "done\n"
    }
}
