package com.zeekrmate.app

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelegramSender {

    fun sendVideo(
        token: String,
        chatId: String,
        topicId: String,
        file: File,
        caption: String = file.name,
        filename: String = file.name
    ): Result<Unit> {
        val videoResult = postFile(
            token,
            chatId,
            topicId,
            file,
            method = "sendVideo",
            fileField = "video",
            filename = filename,
            caption = caption,
            mimeType = "video/mp4"
        )
        if (videoResult.isSuccess) {
            return videoResult
        }
        return postFile(
            token,
            chatId,
            topicId,
            file,
            method = "sendDocument",
            fileField = "document",
            filename = filename,
            caption = caption,
            mimeType = "video/mp4"
        )
    }

    fun sendDocument(
        token: String,
        chatId: String,
        topicId: String,
        file: File,
        filename: String = file.name,
        caption: String = filename
    ): Result<Unit> {
        return postFile(
            token,
            chatId,
            topicId,
            file,
            method = "sendDocument",
            fileField = "document",
            filename = filename,
            caption = caption,
            mimeType = "text/plain"
        )
    }

    fun sendText(
        token: String,
        chatId: String,
        topicId: String,
        text: String
    ): Result<Unit> {
        val query = buildString {
            append("chat_id=").append(URLEncoder.encode(chatId, Charsets.UTF_8.name()))
            append("&text=").append(URLEncoder.encode(text, Charsets.UTF_8.name()))
            if (topicId.isNotBlank()) {
                append("&message_thread_id=")
                    .append(URLEncoder.encode(topicId, Charsets.UTF_8.name()))
            }
        }
        val url = URL("https://api.telegram.org/bot$token/sendMessage")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        return try {
            connection.outputStream.use { output ->
                output.write(query.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader()
                .use { it.readText() }
            if (code in 200..299 && body.contains("\"ok\":true")) {
                Result.success(Unit)
            } else {
                val description = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?: body.take(180)
                Result.failure(IllegalStateException("Telegram sendMessage HTTP $code: $description"))
            }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun postFile(
        token: String,
        chatId: String,
        topicId: String,
        file: File,
        method: String,
        fileField: String,
        filename: String,
        caption: String,
        mimeType: String
    ): Result<Unit> {
        val boundary = "----ZeekrMate${System.currentTimeMillis()}"
        val url = URL("https://api.telegram.org/bot$token/$method")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 600_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            connection.outputStream.use { output ->
                writeField(output, boundary, "chat_id", chatId)
                if (topicId.isNotBlank()) {
                    writeField(output, boundary, "message_thread_id", topicId)
                }
                writeField(output, boundary, "caption", caption)
                if (method == "sendVideo") {
                    writeField(output, boundary, "supports_streaming", "true")
                }
                writeFile(output, boundary, fileField, file, filename, mimeType)
                output.write("--$boundary--\r\n".toByteArray())
                output.flush()
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader()
                .use { it.readText() }
            if (code in 200..299 && body.contains("\"ok\":true")) {
                Result.success(Unit)
            } else {
                val description = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?: body.take(180)
                Result.failure(IllegalStateException("Telegram $method HTTP $code: $description"))
            }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeField(
        output: OutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        output.write(value.toByteArray())
        output.write("\r\n".toByteArray())
    }

    private fun writeFile(
        output: OutputStream,
        boundary: String,
        field: String,
        file: File,
        filename: String,
        mimeType: String
    ) {
        output.write("--$boundary\r\n".toByteArray())
        output.write(
            "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n".toByteArray()
        )
        output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        output.write("\r\n".toByteArray())
    }

    companion object {
        const val CHUNK_BYTES = 45L * 1024L * 1024L
    }
}
