@file:JvmName("HelperDaemon")

package com.zeekrmate.app.helper

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Shell-uid daemon. Spawned from a laptop via ADB:
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=zeekrmate_helper
 *     com.zeekrmate.app.helper.HelperDaemon
 *
 * The app cannot reach adbd:5555. It talks to this process on [PORT] instead.
 */
fun main(args: Array<String>) {
    log("BOOT uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
    val server = ServerSocket()
    server.reuseAddress = true
    try {
        server.bind(InetSocketAddress("0.0.0.0", PORT))
    } catch (error: Exception) {
        log("ALREADY_RUNNING or bind failed: ${error.message}")
        exitProcess(1)
    }
    log("LISTEN 0.0.0.0:$PORT")
    while (true) {
        val client = try {
            server.accept()
        } catch (error: Exception) {
            log("accept: ${error.message}")
            continue
        }
        try {
            handleClient(client)
        } catch (error: Exception) {
            log("client: ${error.javaClass.simpleName} ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}

private fun handleClient(client: Socket) {
    client.soTimeout = 20_000
    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
    val cmd = reader.readLine()?.trim().orEmpty()
    log("cmd from ${client.inetAddress.hostAddress}: $cmd")
    val reply = when (cmd) {
        "PING" -> "OK"
        "SPLIT_NAVI_MUSIC" -> runCatching { splitNaviMusic() }
            .fold(onSuccess = { "OK $it" }, onFailure = { "ERR ${it.message?.take(240)}" })
        else -> "ERR unknown command"
    }
    log("reply $reply")
    client.getOutputStream().write("$reply\n".toByteArray(Charsets.UTF_8))
    client.getOutputStream().flush()
}

private fun splitNaviMusic(): String {
    val navi = resolveLaunch(NAVI_PACKAGES)
    val music = resolveLaunch(MUSIC_PACKAGES)
    val area = contentArea()
    val splitX = area[0] + (area[2] - area[0]) * NAVI_WIDTH_PERCENT / 100
    val naviBounds = intArrayOf(area[0], area[1], splitX, area[3])
    val musicBounds = intArrayOf(splitX, area[1], area[2], area[3])

    startFreeform(navi)
    Thread.sleep(LAUNCH_GAP_MS)
    startFreeform(music)
    Thread.sleep(RESIZE_WAIT_MS)

    val naviTask = waitTaskId(packageOf(navi))
        ?: error("нет task id для ${packageOf(navi)}")
    val musicTask = waitTaskId(packageOf(music))
        ?: error("нет task id для ${packageOf(music)}")

    resizeTask(naviTask, naviBounds)
    resizeTask(musicTask, musicBounds)
    return "navi#$naviTask ${naviBounds[0]},${naviBounds[1]}-${naviBounds[2]},${naviBounds[3]} " +
        "music#$musicTask ${musicBounds[0]},${musicBounds[1]}-${musicBounds[2]},${musicBounds[3]}"
}

private fun resolveLaunch(packages: List<String>): String {
    for (pkg in packages) {
        val out = shell("cmd", "package", "resolve-activity", "--brief", pkg)
        val line = out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains('/') && !it.startsWith("priority") && !it.startsWith("Error") }
        if (!line.isNullOrEmpty()) {
            log("resolve $pkg -> $line")
            return line
        }
    }
    error("нет приложения: ${packages.joinToString()}")
}

private fun startFreeform(component: String) {
    val out = shell(
        "am", "start",
        "--windowingMode", "5",
        "--display", "0",
        "-f", "0x18000000",
        "-n", component
    )
    if (out.contains("Error") || out.contains("Exception")) {
        error("am start $component: ${out.trim().take(200)}")
    }
    log("start $component ${out.trim().take(120)}")
}

private fun resizeTask(taskId: Int, bounds: IntArray) {
    val out = shell(
        "am", "task", "resize",
        taskId.toString(),
        bounds[0].toString(),
        bounds[1].toString(),
        bounds[2].toString(),
        bounds[3].toString()
    )
    log("resize $taskId ${bounds.joinToString(",")} ${out.trim().take(80)}")
}

private fun waitTaskId(pkg: String): Int? {
    repeat(8) {
        findTaskId(pkg)?.let { return it }
        Thread.sleep(400)
    }
    return null
}

private fun findTaskId(pkg: String): Int? {
    val dump = shell("sh", "-c", "dumpsys activity activities | grep ${shellQuote(pkg)}")
    val tagged = Regex("""#(\d+)\b[^\n]*${Regex.escape(pkg)}""")
    tagged.findAll(dump).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val record = Regex("""Task\{[^\s]+ #(\d+)[^\n]*${Regex.escape(pkg)}""")
    return record.findAll(dump).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
}

private fun contentArea(): IntArray {
    val sizeOut = shell("wm", "size")
    val size = Regex("""(\d+)x(\d+)""").findAll(sizeOut).lastOrNull()
    val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 3200
    val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 2000
    return intArrayOf(0, 133, width, height - 170)
}

private fun shellQuote(value: String): String {
    require(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
        "плохой пакет: $value"
    }
    return value
}

private fun packageOf(component: String): String = component.substringBefore('/')

private fun shell(vararg args: String): String {
    val process = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return out
}

private fun log(message: String) {
    val line = "${System.currentTimeMillis()} $message\n"
    print(line)
    System.out.flush()
    runCatching { File(LOG_PATH).appendText(line) }
}

private const val PORT = 18790
private const val LOG_PATH = "/data/local/tmp/zeekrmate-helper.log"
private const val NAVI_WIDTH_PERCENT = 70
private const val LAUNCH_GAP_MS = 450L
private const val RESIZE_WAIT_MS = 800L
private val NAVI_PACKAGES = listOf("ru.yandex.yandexnavi")
private val MUSIC_PACKAGES = listOf("ru.yandex.music", "com.yandex.music")
