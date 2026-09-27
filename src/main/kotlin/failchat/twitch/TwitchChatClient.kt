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
import org.pircbotx.cap.EnableCapHandler
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
import kotlin.random.Random
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

        fun normalizeChannel(channel: String): String {
            return channel.trim().removePrefix("#").lowercase(Locale.ROOT)
        }

        fun normalizeBotName(name: String): String {
            return name.trim().removePrefix("@").lowercase(Locale.ROOT)
        }

        fun normalizeOAuthPassword(password: String): String {
            val normalized = password.trim()
            if (normalized.isEmpty()) return normalized
            return if (normalized.startsWith("oauth:", ignoreCase = true)) {
                "oauth:" + normalized.substringAfter(':').trim()
            } else {
                "oauth:$normalized"
            }
        }

        fun looksLikeAuthenticationFailure(line: String): Boolean {
            val normalized = line.lowercase(Locale.ROOT)
            return normalized.contains("login authentication failed") ||
                    normalized.contains("improperly formatted auth") ||
                    normalized.contains("authentication failed") ||
                    normalized.contains("invalid nick") ||
                    normalized.contains("not authorized") ||
                    normalized.contains("login unsuccessful")
        }

        fun anonymousNick(): String = "justinfan${Random.nextInt(10000, 99999)}"
    }

    override val origin = Origin.TWITCH
    override val status: ChatClientStatus get() = atomicStatus.get()

    private val twitchIrcClient: PircBotX
    private val normalizedChannel = normalizeChannel(userName)
    private val normalizedBotName = normalizeBotName(botName)
    private val normalizedBotPassword = normalizeOAuthPassword(botPassword)
    private val anonymousMode = normalizedBotName.isEmpty() || normalizedBotPassword.isEmpty()
    private val ircNick = if (anonymousMode) anonymousNick() else normalizedBotName
    private val serverEntries = listOf(Configuration.ServerEntry(ircAddress.trim(), ircPort))
    private val atomicStatus: AtomicReference<ChatClientStatus> = AtomicReference(ChatClientStatus.READY)
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
        if (normalizedChannel.isEmpty()) {
            logger.error("Twitch channel name is empty; Twitch IRC cannot connect")
        }

        logger.info(
            "Preparing Twitch IRC connection: account='{}', channel='#{}', server='{}:{}', mode={}",
            ircNick,
            normalizedChannel,
            ircAddress.trim(),
            ircPort,
            if (anonymousMode) "anonymous" else "oauth"
        )

        val builder = Configuration.Builder()
                .setName(ircNick)
                .setLogin(ircNick)
                .setServers(serverEntries)
                .setAutoNickChange(false)
                .setOnJoinWhoEnabled(false)
                .setCapEnabled(true)
                .addCapHandler(EnableCapHandler("twitch.tv/tags"))
                .addCapHandler(EnableCapHandler("twitch.tv/membership"))
                .addCapHandler(EnableCapHandler("twitch.tv/commands"))
                .addAutoJoinChannel("#$normalizedChannel")
                .addListener(TwitchIrcListener())
                .setSocketFactory(UtilSSLSocketFactory.getDefault())
                .setAutoReconnect(true)
                .setAutoReconnectDelay(reconnectTimeout.toMillis().toInt())
                .setAutoReconnectAttempts(5)
                .setEncoding(Charset.forName("UTF-8"))

        if (!anonymousMode) {
            builder.setServerPassword(normalizedBotPassword)
        }

        twitchIrcClient = PircBotX(builder.buildConfiguration())
    }

    override fun start() {
        if (normalizedChannel.isEmpty()) {
            atomicStatus.set(ChatClientStatus.ERROR)
            callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
            return
        }

        val statusChanged = atomicStatus.compareAndSet(ChatClientStatus.READY, ChatClientStatus.CONNECTING)
        if (!statusChanged) throw IllegalStateException("Expected status: ${ChatClientStatus.READY}")

        thread(start = true, name = "TwitchIrcClientThread") {
            try {
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
            logger.info("Twitch IRC authentication completed; joined channel '#{}'", normalizedChannel)
            atomicStatus.set(ChatClientStatus.CONNECTED)
            callbacks.onStatusUpdate(StatusUpdate(TWITCH, CONNECTED))
        }

        override fun onDisconnect(event: DisconnectEvent) {
            when (atomicStatus.get()) {
                ChatClientStatus.OFFLINE,
                ChatClientStatus.ERROR -> return
                else -> {
                    atomicStatus.set(ChatClientStatus.CONNECTING)
                    logger.info("Twitch IRC client disconnected; automatic reconnect is enabled")
                    callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
                }
            }
        }

        override fun onMessage(event: MessageEvent) {
            logger.debug {
                "Message was received from Twitch. ${event.user}. Message: '${event.message}'. Tags: '${event.v3Tags}'"
            }

            val message = parseOrdinaryMessage(event)
            messageHandlers.forEach { it.handleMessage(message) }
            callbacks.onChatMessage(message)
        }

        override fun onListenerException(event: ListenerExceptionEvent) {
            logger.warn("Twitch IRC listener exception", event.exception)
        }

        /**
         * Handle "/me" messages.
         */
        override fun onAction(event: ActionEvent) {
            val message = parseMeMessage(event)
            messageHandlers.forEach { it.handleMessage(message) }
            callbacks.onChatMessage(message)
        }

        override fun onUnknown(event: UnknownEvent) {
            val line = event.line ?: ""
            logger.debug("Twitch IRC server line: {}", line)

            if (looksLikeAuthenticationFailure(line)) {
                atomicStatus.set(ChatClientStatus.ERROR)
                logger.error(
                    "Twitch IRC authentication failed. Verify that twitch.bot-name is the login name of the account that created the token and that twitch.bot-password is a valid OAuth access token with chat:read."
                )
                callbacks.onStatusUpdate(StatusUpdate(TWITCH, DISCONNECTED))
                runCatching { twitchIrcClient.stopBotReconnect() }
                return
            }

            val matcher = banMessagePattern.matcher(line)
            if (!matcher.find()) return

            val author = matcher.group(1)
            val messagesToDelete = runBlocking {
                history.findTyped<TwitchMessage> { it.author.id.equals(author, ignoreCase = true) }
            }
            messagesToDelete.forEach {
                callbacks.onChatMessageDeleted(it)
            }
        }
    }

    private fun parseOrdinaryMessage(event: MessageEvent): TwitchMessage {
        val displayedName = event.v3Tags[TwitchIrcTags.displayName]
        val author: String = if (displayedName.isNullOrEmpty()) {
            event.userHostmask.nick.capitalize()
        } else {
            displayedName
        }
        return TwitchMessage(
                id = messageIdGenerator.generate(),
                author = author,
                text = event.message,
                tags = event.v3Tags
        )
    }

    private fun parseMeMessage(event: ActionEvent): TwitchMessage {
        return TwitchMessage(
                id = messageIdGenerator.generate(),
                author = event.userHostmask.nick,
                text = event.message,
                tags = mapOf()
        )
    }

}
