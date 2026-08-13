package com.zeekrmate.app

data class TelegramTarget(
    val chatId: String,
    val topicId: String
)

object TelegramLink {

    fun parse(raw: String): TelegramTarget? {
        val link = raw.trim()
        if (link.isEmpty()) {
            return null
        }

        Regex("""(?:https?://)?t\.me/c/(\d+)(?:/(\d+))?(?:/\d+)?""", RegexOption.IGNORE_CASE)
            .find(link)
            ?.let { match ->
                val chatId = "-100${match.groupValues[1]}"
                val topicId = match.groupValues[2]
                return TelegramTarget(chatId, topicId)
            }

        Regex("""(?:https?://)?t\.me/([A-Za-z0-9_]+)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(link)
            ?.let { match ->
                return TelegramTarget("@${match.groupValues[1]}", match.groupValues[2])
            }

        Regex("""(?:https?://)?t\.me/([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
            .find(link)
            ?.let { match ->
                return TelegramTarget("@${match.groupValues[1]}", "")
            }

        Regex("""web\.telegram\.org/[^#]*#(-?\d+)(?:_(\d+))?""")
            .find(link)
            ?.let { match ->
                return TelegramTarget(match.groupValues[1], match.groupValues[2])
            }

        return null
    }
}
