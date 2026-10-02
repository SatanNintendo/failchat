package failchat.chat

/**
 * A class serialized to json to be sent to websocket clients.
 */
data class Link(
        val fullUrl: String,
        val domain: String,
        val shortUrl: String
) : MessageElement
