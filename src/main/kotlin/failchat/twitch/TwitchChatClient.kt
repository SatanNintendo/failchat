package failchat.twitch

import failchat.Origin
import failchat.Origin.TWITCH
import failchat.chat.ChatClient
import failchat.chat.ChatClientCallbacks
import failchat.chat.ChatClientStatus
import failchat.chat.ChatMessage
import failchat.chat.ChatMessageHistory
import failchat.chat.MessageHandler
import failchat.chat.MessageIdGenerator
import failchat.chat.OriginStatus.CONNECTED
import failchat.chat.OriginStatus.DISCONNECTED
import failchat.chat.StatusUpdate
import failchat.chat.findTyped
import failchat.chat.handlers.BraceEscaper
import failchat.chat.handlers.ElementLabelEscaper
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.pircbotx.Configuration
import org.pircbotx.PircBotX
import org.pircbotx.UtilSSLSocketFactory
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.ActionEvent
import org.pircbotx.hooks.events.ConnectEvent
import org.pircbotx.hooks.events.DisconnectEvent
import org.pircbotx.hooks.events.ListenerExceptionEvent
import org.pircbotx.hooks.events.MessageEvent
import org.pircbotx.hooks.events.UnknownEvent
import java.nio.charset.Charset
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.concurrent.thread

class TwitchChatClient(
        private val userName: String,
        ircAddress: String,
        ircPort: Int,
        botName: String,
        botPassword: String,
        twitchEmoticonHandler: TwitchEmoticonHandler,
        private val messageIdGenerator: MessageIdGenerator,
        bttvEmoticonHandler: BttvEmoticonHandler,
        ffzEmoticonHandler: FfzEmoticonHandler,
        sevenTvGlobalEmoticonHandler: MessageHandler<ChatMessage>,
        sevenTvChannelEmoticonHandler: MessageHandler<ChatMessage>,
        twitchBadgeHandler: TwitchBadgeHandler,
        private val history: ChatMessageHistory,
        override val callbacks: ChatClientCallbacks
) : ChatClient {
    private companion object {
        val logger = KotlinLogging.logger {}
        val reconnectTimeout: Duration = Duration.ofSeconds(10)
        val banMessagePattern: Pattern = Pattern.compile("""^:tmi\\.twitch\\.tv CLEARCHAT #.+ :(.+)""")

        fun normalizeChannel(channel: String): String = channel.trim().removePrefix("#").lowercase(Locale.ROOT)

        fun normalizeBotName(name: String): String = name.trim().removePrefix("@").lowercase(Locale.ROOT)

        fun normalizeOAuthPassword(password: String): String {
            val value = password.trim()
            if (value.isEmpty()) return value
            return if (value.startsWith("oauth:", ignoreCase = true)) {
                "oauth:" + value.substringAfter(':').trim()
            } else {
                "oauth:$value"
            }
        }

        fun isAuthenticationFailure(line: String): Boolean {
            val normalized = line.lowercase(Locale.ROOT)
            return normalized.contains("login authentication failed") ||
                    normalized.contains("improperly formatted auth") ||
                    normalized.contains("authentication failed") ||
                    normalized.contains("login unsuccessful") ||
                    normalized.contains("invalid nick")
        }
    }

    override val origin = Origin.TWITCH
    override val status: ChatClientStatus get() = atomicStatus.get()

    private val twitchIrcClient: PircBotX
    private val normalizedChannel = normalizeChannel(userName)
    private val normalizedBotName = normalizeBotName(botName)
    private val normalizedBotPassword = normalizeOAuthPassword(botPassword)
    private val serverEntries = listOf(Configuration.ServerEntry(ircAddress.trim(), ircPort))
    private val atomicStatus = AtomicReference(ChatClientStatus.READY)
    private val messageHandlers: List<MessageHandler<TwitchMessage>> = listOf(
            ElementLabelEscaper(),
            twitchEmoticonHandler,
            bttvEmoticonHandler,
            ffzEmoticonHandler,
            sevenTvGlobalEmoticonHandler,
            sevenTvChannelEmoticonHandler,
            BraceEscaper(),
            TwitchHighlightHandler(userName),
            TwitchRewardHandler(),
            TwitchHighlightByPointsHandler(),
            twitchBadgeHandler,
            TwitchAuthorColorHandler()
    )

    init {
        logger.info(
                "Preparing Twitch IRC connection: account='{}', channel='#{}', server='{}:{}', tokenPresent={}",
                normalizedBotName,
                normalizedChannel,
                ircAddress.trim(),
                ircPort,
                normalizedBotPassword.isNotEmpty()
        )

        twitchIrcClient = PircBotX(
                Configuration.Builder()
                        .setName(normalizedBotName)
                        .setServerPassword(normalizedBotPassword)
                        .setServers(serverEntries)
                        .addAutoJoinChannel("#$normalizedChannel")
                        .addListener(TwitchIrcListener())
                        .setSocketFactory(UtilSSLSocketFactory.getDefault())
                        .setAutoReconnect(false)
                        .setAutoReconnectDelay(reconnectTimeout.toMillis().toInt())
                        .setAutoReconnectAttempts(Int.MAX_VALUE)
                        .setEncoding(Charset.forName("UTF-8"))
                        .buildConfiguration()
        )
    }

    override fun start() {
        if (normalizedChannel.isEmpty() || normalizedBotName.isEmpty() || normalizedBotPassword.isEmpty()) {
            atomicStatus.set(ChatClientStatus.ERROR)
            logger.error("Twitch IRC is not started because channel, bot name, or OAuth token is missing")
            callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
            return
        }

        if (!atomicStatus.compareAndSet(ChatClientStatus.READY, ChatClientStatus.CONNECTING)) {
            throw IllegalStateException("Expected status: ${ChatClientStatus.READY}")
        }

        thread(start = true, name = "TwitchIrcClientThread") {
            try {
                // PircBotX performs DNS/TLS/IRC authentication here. Never run it
                // on the JavaFX application thread.
                twitchIrcClient.startBot()
            } catch (e: Exception) {
                atomicStatus.set(ChatClientStatus.ERROR)
                logger.error("Failed to start Twitch IRC client", e)
                callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
            }
        }
    }

    override fun stop() {
        atomicStatus.set(ChatClientStatus.OFFLINE)
        runCatching { twitchIrcClient.stopBotReconnect() }
        runCatching { twitchIrcClient.close() }
    }

    private inner class TwitchIrcListener : ListenerAdapter() {
        override fun onConnect(event: ConnectEvent) {
            atomicStatus.set(ChatClientStatus.CONNECTED)
            logger.info("Twitch IRC connected and authenticated; joined channel '#{}'", normalizedChannel)
            callbacks.onStatusUpdate(StatusUpdate(TWITCH, CONNECTED))
        }

        override fun onDisconnect(event: DisconnectEvent) {
            if (atomicStatus.get() == ChatClientStatus.OFFLINE) return
            atomicStatus.set(ChatClientStatus.ERROR)
            logger.warn("Twitch IRC disconnected")
            callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
        }

        override fun onMessage(event: MessageEvent) {
            logger.debug { "Message received from Twitch. ${event.user}: '${event.message}'" }
            val message = parseOrdinaryMessage(event)
            messageHandlers.forEach { it.handleMessage(message) }
            callbacks.onChatMessage(message)
        }

        override fun onListenerException(event: ListenerExceptionEvent) {
            logger.warn("Twitch IRC listener exception", event.exception)
        }

        override fun onAction(event: ActionEvent) {
            val message = parseMeMessage(event)
            messageHandlers.forEach { it.handleMessage(message) }
            callbacks.onChatMessage(message)
        }

        override fun onUnknown(event: UnknownEvent) {
            val line = event.line ?: ""
            logger.debug("Twitch IRC server line: {}", line)

            if (isAuthenticationFailure(line)) {
                atomicStatus.set(ChatClientStatus.ERROR)
                logger.error("Twitch IRC authentication failed. Check twitch.bot-name and twitch.bot-password (oauth token with chat:read).")
                callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
                return
            }

            val matcher = banMessagePattern.matcher(line)
            if (!matcher.find()) return
            val author = matcher.group(1)
            val messagesToDelete = runBlocking {
                history.findTyped<TwitchMessage> { it.author.id.equals(author, ignoreCase = true) }
            }
            messagesToDelete.forEach { callbacks.onChatMessageDeleted(it) }
        }
    }

    private fun parseOrdinaryMessage(event: MessageEvent): TwitchMessage {
        val displayedName = event.v3Tags[TwitchIrcTags.displayName]
        val author = if (displayedName.isNullOrEmpty()) event.userHostmask.nick.capitalize() else displayedName
        return TwitchMessage(
                id = messageIdGenerator.generate(),
                author = author,
                text = event.message,
                tags = event.v3Tags
        )
    }

    private fun parseMeMessage(event: ActionEvent): TwitchMessage = TwitchMessage(
            id = messageIdGenerator.generate(),
            author = event.userHostmask.nick,
            text = event.message,
            tags = mapOf()
    )
}
