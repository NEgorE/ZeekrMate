package com.zeekrmate.app

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to the shell-uid helper on TCP [PORT]. Not ADB, not port 5555.
 */
object SplitHelperClient {

    fun splitNaviMusic(): String = request("SPLIT_NAVI_MUSIC", READ_TIMEOUT_MS)

    fun ping(): String = request("PING", PING_TIMEOUT_MS)

    private fun request(command: String, readTimeoutMs: Int): String {
        val failures = mutableListOf<String>()
        for (host in HOSTS) {
            val reply = runCatching { send(host, command, readTimeoutMs) }
                .getOrElse { error ->
                    failures += "$host:${error.javaClass.simpleName} ${error.message?.take(80)}"
                    null
                }
            if (reply != null) {
                return "$host $reply"
            }
        }
        error(
            "хелпер не отвечает (${failures.joinToString("; ")}). " +
                "Запусти его с ноутбука: helper/HelperStart.txt"
        )
    }

    private fun send(host: String, command: String, readTimeoutMs: Int): String {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs
            val output = socket.getOutputStream()
            output.write("$command\n".toByteArray(Charsets.UTF_8))
            output.flush()
            val reply = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                ?: error("пустой ответ")
            if (reply.startsWith("ERR")) {
                error(reply.removePrefix("ERR ").ifBlank { reply })
            }
            return reply
        }
    }

    const val PORT = 18790
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val PING_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 25_000
    private val HOSTS = listOf("127.0.0.1", "198.18.34.15", "192.168.55.15")
}
