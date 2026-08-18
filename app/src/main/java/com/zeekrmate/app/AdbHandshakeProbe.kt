package com.zeekrmate.app

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One-shot ADB handshake from the app UID. Does not keep a session and does not
 * complete RSA auth — it only reports what adbd answers after CNXN.
 */
object AdbHandshakeProbe {

    data class HostResult(
        val host: String,
        val ok: Boolean,
        val detail: String,
    )

    fun probeKnownHosts(): List<HostResult> = HOSTS.map { host ->
        runCatching { probe(host, PORT) }
            .getOrElse { error ->
                HostResult(host, false, error.message?.take(120) ?: error.javaClass.simpleName)
            }
    }

    private fun probe(host: String, port: Int): HostResult {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
            output.write(header(A_CNXN, A_VERSION_AUTH, MAX_PAYLOAD, banner))
            output.write(banner)
            output.flush()
            val packet = readPacket(input)
            return when (packet.command) {
                A_CNXN -> HostResult(host, true, "ADB CNXN — сессия без запроса ключа")
                A_AUTH -> HostResult(
                    host,
                    true,
                    if (packet.arg0 == AUTH_TOKEN) {
                        "ADB AUTH — нужен ключ, смотри экран (запрос отладки?)"
                    } else {
                        "ADB AUTH arg0=${packet.arg0}"
                    }
                )
                else -> HostResult(
                    host,
                    false,
                    "не ADB: cmd=0x${packet.command.toString(16)}"
                )
            }
        }
    }

    private fun readPacket(input: java.io.InputStream): Packet {
        val header = ByteBuffer.wrap(readFully(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        header.int
        val payloadLen = header.int
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
            throw IOException("странный payloadLen=$payloadLen")
        }
        header.int
        header.int
        if (payloadLen > 0) readFully(input, payloadLen)
        return Packet(command, arg0)
    }

    private fun readFully(input: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = input.read(buf, read, n - read)
            if (got < 0) throw IOException("обрыв после $read из $n байт")
            read += got
        }
        return buf
    }

    private fun header(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        buf.putInt(checksum)
        buf.putInt(command.inv())
        return buf.array()
    }

    private data class Packet(
        val command: Int,
        val arg0: Int,
    )

    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val A_CNXN = 0x4E584E43
    private const val A_AUTH = 0x48545541
    private const val A_VERSION_AUTH = 0x01000001
    private const val MAX_PAYLOAD = 262144
    private const val AUTH_TOKEN = 1

    private val HOSTS = listOf("127.0.0.1", "198.18.34.15", "192.168.55.15")
}
