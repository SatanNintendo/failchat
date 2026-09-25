package failchat.chat

import failchat.tts.TtsService
import kotlinx.coroutines.runBlocking

class OnChatMessageCallback(
        private val filters: List<MessageFilter<ChatMessage>>,
        private val handlers: List<MessageHandler<ChatMessage>>,
        private val messageHistory: ChatMessageHistory,
        private val messageSender: ChatMessageSender,
        private val ttsService: TtsService
) : (ChatMessage) -> Unit {

    override fun invoke(message: ChatMessage) {
        // apply filters and handlers
        filters.forEach {
            if (it.filterMessage(message)) return
        }
        // Keep the original chat text for TTS. Some display handlers replace URLs/emotes
        // in message.text with internal element labels that are not suitable for speech.
        val ttsText = message.text

        handlers.forEach { it.handleMessage(message) }

        runBlocking {
            messageHistory.add(message)
        }

        messageSender.send(message)
        ttsService.enqueue(ttsText)
    }
}
