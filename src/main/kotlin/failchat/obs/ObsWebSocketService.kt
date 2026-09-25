package failchat.obs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import failchat.ConfigKeys
import failchat.FailchatServerInfo
import mu.KotlinLogging
import org.apache.commons.configuration2.Configuration
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Optional OBS Studio integration.
 *
 * Uses obs-websocket protocol 5.x through the Java-WebSocket dependency already present
 * in failchat. OBS is never contacted unless automatic integration is enabled or a manual
 * refresh is requested from the settings window.
 */
class ObsWebSocketService(
    private val config: Configuration,
    private val objectMapper: ObjectMapper,
    private val executor: ScheduledExecutorService
) {

    enum class Status {
        DISABLED,
        CONNECTING,
        CONNECTED,
        SEARCHING,
        REFRESHED,
        NOT_FOUND,
        ERROR,
        DISCONNECTED
    }

    data class RefreshResult(
        val matchingSources: Int,
        val refreshedSources: Int,
        val error: String? = null
    )

    private companion object {
        val logger = KotlinLogging.logger {}
        const val RECONNECT_SECONDS = 5L
        const val MANUAL_TIMEOUT_SECONDS = 15L
        const val PROPERTY_REFRESH_NO_CACHE = "refreshnocache"
        const val BROWSER_SOURCE_KIND = "browser_source"
    }

    private data class PendingRefresh(
        val callback: (RefreshResult) -> Unit
    )

    private class RefreshOperation(
        val callbacks: List<PendingRefresh>,
        var remainingSettings: Int = 0,
        var remainingRefreshes: Int = 0,
        var matchingSources: Int = 0,
        var refreshedSources: Int = 0,
        var error: String? = null,
        var completed: Boolean = false,
        var timeoutFuture: ScheduledFuture<*>? = null
    )

    private val stateLock = Any()
    private val statusListeners = mutableListOf<(Status) -> Unit>()
    private val pendingRequests = mutableMapOf<String, (JsonNode) -> Unit>()
    private val pendingRefreshes = mutableListOf<PendingRefresh>()

    private var stopped = false
    private var persistentConnectionEnabled = false
    private var automaticRefreshEnabled = true
    private var temporaryConnectionRequested = false
    private var connecting = false
    private var identified = false
    private var currentClient: WebSocketClient? = null
    private var reconnectTask: ScheduledFuture<*>? = null
    private var refreshOperation: RefreshOperation? = null
    @Volatile
    private var currentStatus = Status.DISCONNECTED

    private var connectedUri: URI? = null
    private var connectedPassword: String? = null

    fun start() {
        synchronized(stateLock) {
            stopped = false
        }
        executor.execute {
            if (!stopped) {
                applyConfigurationInternal()
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            stopped = true
        }

        reconnectTask?.cancel(false)
        reconnectTask = null

        val client = currentClient
        currentClient = null
        connectedUri = null
        connectedPassword = null
        if (client != null) {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }

        executor.execute {
            completePendingRefreshes(RefreshResult(0, 0, "OBS integration was stopped"))
            publishStatus(Status.DISCONNECTED)
        }
    }

    /** Re-read OBS settings after the settings window changes them. */
    fun applyConfiguration() {
        executor.execute {
            if (!stopped) {
                applyConfigurationInternal()
            }
        }
    }

    /**
     * Refresh all Browser Sources that point to this failchat instance.
     * Works even when automatic OBS integration is disabled; in that case the connection is one-shot.
     */
    fun refreshNow(callback: (RefreshResult) -> Unit) {
        executor.execute {
            if (stopped) {
                callback(RefreshResult(0, 0, "OBS integration is not available"))
                return@execute
            }

            val pending = PendingRefresh(callback)
            pendingRefreshes += pending
            temporaryConnectionRequested = !persistentConnectionEnabled

            executor.schedule({
                if (pendingRefreshes.remove(pending)) {
                    callback(RefreshResult(0, 0, "Timed out connecting to OBS"))
                    closeTemporaryConnectionIfUnused()
                }
            }, MANUAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            ensureConnectionInternal()
            beginRefreshIfPossible()
        }
    }

    fun currentStatus(): Status = currentStatus

    fun addStatusListener(listener: (Status) -> Unit) {
        synchronized(statusListeners) {
            statusListeners += listener
        }
        listener(currentStatus)
    }

    fun removeStatusListener(listener: (Status) -> Unit) {
        synchronized(statusListeners) {
            statusListeners.remove(listener)
        }
    }

    private fun applyConfigurationInternal() {
        val newPersistentConnectionEnabled = readBoolean(ConfigKeys.Obs.enabled, false)
        val newAutomaticRefreshEnabled = readBoolean(ConfigKeys.Obs.autoRefresh, true)
        val newUri = try { buildWebSocketUri() } catch (_: Throwable) { null }
        val newPassword = config.getString(ConfigKeys.Obs.password, "")

        val connectionSettingsChanged =
            persistentConnectionEnabled && newPersistentConnectionEnabled &&
                (newUri != connectedUri || newPassword != connectedPassword)

        persistentConnectionEnabled = newPersistentConnectionEnabled
        automaticRefreshEnabled = newAutomaticRefreshEnabled

        if (connectionSettingsChanged) {
            val client = currentClient
            currentClient = null
            identified = false
            connecting = false
            connectedUri = null
            connectedPassword = null
            pendingRequests.clear()
            try {
                client?.close()
            } catch (_: Throwable) {
            }
        }

        if (persistentConnectionEnabled) {
            ensureReconnectTask()
            ensureConnectionInternal()
            beginRefreshIfPossible()
        } else {
            reconnectTask?.cancel(false)
            reconnectTask = null
            closeTemporaryConnectionIfUnused()
            if (pendingRefreshes.isEmpty() && refreshOperation == null) {
                publishStatus(Status.DISABLED)
            }
        }
    }

    private fun ensureReconnectTask() {
        if (reconnectTask?.isCancelled == false) return

        reconnectTask = executor.scheduleWithFixedDelay(
            {
                if (persistentConnectionEnabled && !stopped) {
                    ensureConnectionInternal()
                }
            },
            0L,
            RECONNECT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun ensureConnectionInternal() {
        if (stopped || identified || connecting) return

        val existing = currentClient
        if (existing != null && existing.isOpen) return

        val uri = try {
            buildWebSocketUri()
        } catch (t: Throwable) {
            publishStatus(Status.ERROR)
            completePendingRefreshes(RefreshResult(0, 0, "Invalid OBS WebSocket address"))
            logger.debug("Invalid OBS WebSocket address", t)
            return
        }

        publishStatus(Status.CONNECTING)
        connecting = true

        val client = createClient(uri)
        currentClient = client
        connectedUri = uri
        connectedPassword = config.getString(ConfigKeys.Obs.password, "")
        try {
            client.connect()
        } catch (t: Throwable) {
            connecting = false
            currentClient = null
            connectedUri = null
            connectedPassword = null
            publishStatus(Status.ERROR)
            logger.debug("Failed to connect to OBS WebSocket at {}", uri, t)
            if (!persistentConnectionEnabled) {
                completePendingRefreshes(RefreshResult(0, 0, "Failed to connect to OBS"))
            }
        }
    }

    private fun createClient(uri: URI): WebSocketClient {
        lateinit var client: WebSocketClient
        client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                executor.execute { handleOpen(this) }
            }

            override fun onMessage(message: String?) {
                if (message != null) {
                    executor.execute { handleMessage(this, message) }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                executor.execute { handleClose(this, code, reason) }
            }

            override fun onError(ex: Exception?) {
                executor.execute { handleError(this, ex) }
            }
        }
        client.setConnectionLostTimeout(15)
        return client
    }

    private fun handleOpen(client: WebSocketClient) {
        if (client !== currentClient || stopped) return
        connecting = false
    }

    private fun handleMessage(client: WebSocketClient, message: String) {
        if (client !== currentClient || stopped) return

        val root = try {
            objectMapper.readTree(message)
        } catch (t: Throwable) {
            logger.debug("Ignoring malformed OBS WebSocket message", t)
            return
        }

        when (root.path("op").asInt(-1)) {
            0 -> handleHello(client, root.path("d"))
            2 -> handleIdentified()
            7 -> handleRequestResponse(root.path("d"))
            else -> Unit
        }
    }

    private fun handleHello(client: WebSocketClient, data: JsonNode) {
        val serverRpcVersion = data.path("rpcVersion").asInt(0)
        if (serverRpcVersion < 1) {
            publishStatus(Status.ERROR)
            client.close()
            completePendingRefreshes(RefreshResult(0, 0, "OBS WebSocket protocol 5.x is required"))
            return
        }

        val identify = objectMapper.createObjectNode()
        identify.put("op", 1)
        val identifyData = identify.putObject("d")
        identifyData.put("rpcVersion", minOf(1, serverRpcVersion))
        identifyData.put("eventSubscriptions", 0)

        val authentication = data.path("authentication")
        if (!authentication.isMissingNode && !authentication.isNull) {
            val password = config.getString(ConfigKeys.Obs.password, "")
            if (password.isEmpty()) {
                publishStatus(Status.ERROR)
                client.close()
                completePendingRefreshes(RefreshResult(0, 0, "OBS WebSocket password is required"))
                return
            }

            val salt = authentication.path("salt").asText("")
            val challenge = authentication.path("challenge").asText("")
            if (salt.isEmpty() || challenge.isEmpty()) {
                publishStatus(Status.ERROR)
                client.close()
                completePendingRefreshes(RefreshResult(0, 0, "OBS returned invalid authentication data"))
                return
            }

            identifyData.put("authentication", createAuthentication(password, salt, challenge))
        }

        try {
            client.send(identify.toString())
        } catch (t: Throwable) {
            logger.debug("Failed to send OBS identify request", t)
            client.close()
        }
    }

    private fun handleIdentified() {
        identified = true
        publishStatus(Status.CONNECTED)
        beginRefreshIfPossible()
    }

    private fun beginRefreshIfPossible() {
        if (stopped || !identified || refreshOperation != null) return

        val callbacks = pendingRefreshes.toList()
        pendingRefreshes.clear()

        if (callbacks.isEmpty() && !automaticRefreshEnabled) return

        refreshOperation = RefreshOperation(callbacks)
        publishStatus(Status.SEARCHING)
        sendRequest("GetInputList") { root -> handleInputListResponse(root) }
        refreshOperation?.timeoutFuture = executor.schedule({
            val operation = refreshOperation ?: return@schedule
            if (operation.completed) return@schedule
            operation.error = operation.error ?: "Timed out while refreshing OBS Browser Sources"
            finishRefresh(operation)
        }, MANUAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun handleInputListResponse(root: JsonNode) {
        val operation = refreshOperation ?: return
        if (!requestSucceeded(root)) {
            operation.error = requestError(root, "Failed to get OBS input list")
            finishRefresh(operation)
            return
        }

        val inputs = root.path("responseData").path("inputs")
        val browserSources = inputs.elements().asSequence()
            .filter { it.path("inputKind").asText() == BROWSER_SOURCE_KIND }
            .toList()

        operation.remainingSettings = browserSources.size
        if (browserSources.isEmpty()) {
            finishRefresh(operation)
            return
        }

        browserSources.forEach { input ->
            val inputUuid = input.path("inputUuid").asText("")
            if (inputUuid.isEmpty()) {
                operation.remainingSettings--
                operation.error = operation.error ?: "OBS returned a Browser Source without UUID"
                checkRefreshComplete(operation)
                return@forEach
            }

            val requestData = objectMapper.createObjectNode()
            requestData.put("inputUuid", inputUuid)
            sendRequest("GetInputSettings", requestData) { response ->
                handleInputSettingsResponse(operation, inputUuid, response)
            }
        }

        checkRefreshComplete(operation)
    }

    private fun handleInputSettingsResponse(
        operation: RefreshOperation,
        inputUuid: String,
        root: JsonNode
    ) {
        if (operation !== refreshOperation || operation.completed) return

        operation.remainingSettings--
        if (!requestSucceeded(root)) {
            operation.error = operation.error ?: requestError(root, "Failed to get OBS Browser Source settings")
            checkRefreshComplete(operation)
            return
        }

        val url = root.path("responseData").path("inputSettings").path("url").asText("")
        if (isFailchatBrowserSourceUrl(url)) {
            operation.matchingSources++
            operation.remainingRefreshes++

            val requestData = objectMapper.createObjectNode()
            requestData.put("inputUuid", inputUuid)
            requestData.put("propertyName", PROPERTY_REFRESH_NO_CACHE)
            sendRequest("PressInputPropertiesButton", requestData) { response ->
                operation.remainingRefreshes--
                if (requestSucceeded(response)) {
                    operation.refreshedSources++
                } else {
                    operation.error = operation.error ?: requestError(
                        response,
                        "Failed to refresh an OBS Browser Source"
                    )
                }
                checkRefreshComplete(operation)
            }
        }

        checkRefreshComplete(operation)
    }

    private fun checkRefreshComplete(operation: RefreshOperation) {
        if (operation.remainingSettings <= 0 && operation.remainingRefreshes <= 0) {
            finishRefresh(operation)
        }
    }

    private fun finishRefresh(operation: RefreshOperation) {
        if (operation.completed || operation !== refreshOperation) return
        operation.completed = true
        operation.timeoutFuture?.cancel(false)
        refreshOperation = null

        val result = RefreshResult(
            matchingSources = operation.matchingSources,
            refreshedSources = operation.refreshedSources,
            error = operation.error
        )

        when {
            result.refreshedSources > 0 -> publishStatus(Status.REFRESHED)
            result.error != null -> publishStatus(Status.ERROR)
            else -> publishStatus(Status.NOT_FOUND)
        }

        operation.callbacks.forEach { pending ->
            try {
                pending.callback(result)
            } catch (t: Throwable) {
                logger.debug("OBS refresh callback failed", t)
            }
        }

        closeTemporaryConnectionIfUnused()
        if (pendingRefreshes.isNotEmpty()) {
            beginRefreshIfPossible()
        }
    }

    private fun closeTemporaryConnectionIfUnused() {
        if (persistentConnectionEnabled || refreshOperation != null || pendingRefreshes.isNotEmpty()) return
        temporaryConnectionRequested = false
        val client = currentClient ?: return
        currentClient = null
        identified = false
        connecting = false
        connectedUri = null
        connectedPassword = null
        pendingRequests.clear()
        try {
            client.close()
        } catch (_: Throwable) {
        }
        publishStatus(Status.DISCONNECTED)
    }

    private fun completePendingRefreshes(result: RefreshResult) {
        val callbacks = pendingRefreshes.toList()
        pendingRefreshes.clear()
        val operation = refreshOperation
        refreshOperation = null
        operation?.completed = true
        operation?.timeoutFuture?.cancel(false)

        val allCallbacks = callbacks + (operation?.callbacks ?: emptyList())
        allCallbacks.forEach { pending ->
            try {
                pending.callback(result)
            } catch (t: Throwable) {
                logger.debug("OBS refresh callback failed", t)
            }
        }
    }

    private fun handleClose(client: WebSocketClient, code: Int, reason: String?) {
        if (client !== currentClient) return
        currentClient = null
        identified = false
        connecting = false
        pendingRequests.clear()
        publishStatus(Status.DISCONNECTED)

        if (!persistentConnectionEnabled && temporaryConnectionRequested) {
            val closeError = reason?.takeIf { it.isNotBlank() } ?: "OBS connection closed"
            val operation = refreshOperation
            if (operation != null) {
                operation.error = operation.error ?: closeError
                finishRefresh(operation)
            } else {
                completePendingRefreshes(RefreshResult(0, 0, closeError))
                temporaryConnectionRequested = false
            }
        }

        logger.debug("OBS WebSocket closed: code={}, reason={}", code, reason)
    }

    private fun handleError(client: WebSocketClient, error: Throwable?) {
        if (client !== currentClient) return
        logger.debug("OBS WebSocket error", error)
        if (!persistentConnectionEnabled) {
            publishStatus(Status.ERROR)
        }
    }

    private fun sendRequest(
        requestType: String,
        requestData: JsonNode? = null,
        handler: (JsonNode) -> Unit
    ) {
        val client = currentClient
        if (client == null || !identified || !client.isOpen) {
            handler(
                objectMapper.createObjectNode().apply {
                    putObject("requestStatus").apply {
                        put("result", false)
                        put("comment", "OBS WebSocket is not connected")
                    }
                }
            )
            return
        }

        val requestId = UUID.randomUUID().toString()
        pendingRequests[requestId] = handler

        val root = objectMapper.createObjectNode()
        root.put("op", 6)
        val data = root.putObject("d")
        data.put("requestType", requestType)
        data.put("requestId", requestId)
        requestData?.let { data.set<JsonNode>("requestData", it) }

        try {
            client.send(root.toString())
        } catch (t: Throwable) {
            pendingRequests.remove(requestId)
            handler(
                objectMapper.createObjectNode().apply {
                    putObject("requestStatus").apply {
                        put("result", false)
                        put("comment", "Failed to send OBS WebSocket request")
                    }
                }
            )
            logger.debug("Failed to send OBS WebSocket request {}", requestType, t)
        }
    }

    private fun handleRequestResponse(data: JsonNode) {
        val requestId = data.path("requestId").asText("")
        val handler = pendingRequests.remove(requestId) ?: return
        handler(data)
    }

    private fun requestSucceeded(root: JsonNode): Boolean =
        root.path("requestStatus").path("result").asBoolean(false)

    private fun requestError(root: JsonNode, fallback: String): String =
        root.path("requestStatus").path("comment").asText("").takeIf { it.isNotBlank() } ?: fallback

    private fun buildWebSocketUri(): URI {
        val host = config.getString(ConfigKeys.Obs.host, "127.0.0.1").trim().ifEmpty { "127.0.0.1" }
        val port = config.getInt(ConfigKeys.Obs.port, 4455).coerceIn(1, 65535)
        val uriHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return URI("ws://$uriHost:$port")
    }

    private fun publishStatus(status: Status) {
        currentStatus = status
        val listeners = synchronized(statusListeners) { statusListeners.toList() }
        listeners.forEach { listener ->
            try {
                listener(status)
            } catch (t: Throwable) {
                logger.debug("OBS status listener failed", t)
            }
        }
    }

    private fun readBoolean(key: String, default: Boolean): Boolean = try {
        config.getBoolean(key, default)
    } catch (_: Throwable) {
        default
    }

    internal fun isFailchatBrowserSourceUrl(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: Throwable) {
            return false
        }

        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        if (uri.port != FailchatServerInfo.port) return false

        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host !in setOf("localhost", "127.0.0.1", "::1")) return false

        val path = uri.path?.trimEnd('/') ?: return false
        return path == "/chat" || path.startsWith("/chat/")
    }

    internal fun createAuthentication(password: String, salt: String, challenge: String): String {
        val secret = base64(sha256((password + salt).toByteArray(StandardCharsets.UTF_8)))
        return base64(sha256((secret + challenge).toByteArray(StandardCharsets.UTF_8)))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)
}
