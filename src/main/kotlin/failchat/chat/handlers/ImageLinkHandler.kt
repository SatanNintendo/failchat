package failchat.chat.handlers

import failchat.chat.ChatMessage
import failchat.chat.Image
import failchat.chat.Link
import failchat.chat.MessageHandler

/**
 * Replaces [Link] elements with [Image] ones depending on the configuration.
 * */
class ImageLinkHandler : MessageHandler<ChatMessage> {

    private companion object {
        val imageFormats = listOf(".jpg", ".jpeg", ".png", ".gif")
    }

    @Volatile
    var replaceImageLinks = false

    override fun handleMessage(message: ChatMessage) {
        if (!replaceImageLinks) return

        message.elements.forEachIndexed { index, element ->
            if (element !is Link) return@forEachIndexed

            val imageFormat = imageFormats.firstOrNull {
                element.fullUrl.endsWith(it, ignoreCase = true)
            }

            if (imageFormat != null) {
                message.replaceElement(index, Image(element))
            }
        }
    }

}
