package failchat.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Parser for YouTube's current bootstrap data.
 *
 * YouTube changes whitespace, JavaScript wrappers and the exact position of
 * liveChatRenderer regularly.  Regexes that require the whole JSON object to be
 * on one line or a single fixed JSON path are therefore intentionally avoided.
 */
class YoutubeHtmlParser(private val mapper: ObjectMapper = ObjectMapper()) {

    fun parseYoutubeConfig(html: String): ObjectNode = parseYoutubeConfigInternal(html)

    fun parseInitialData(html: String): ObjectNode = parseInitialDataInternal(html)

    fun extractInnertubeApiKey(config: JsonNode): String = extractInnertubeApiKeyInternal(config)

    fun extractInitialContinuation(initialData: JsonNode): String = extractInitialContinuationInternal(initialData)

    fun extractChannelName(initialData: JsonNode): String = extractChannelNameInternal(initialData)

    companion object {
        private val defaultParser = YoutubeHtmlParser()

        fun parseYoutubeConfig(html: String): ObjectNode = defaultParser.parseYoutubeConfig(html)
        fun parseInitialData(html: String): ObjectNode = defaultParser.parseInitialData(html)
        fun extractInnertubeApiKey(config: JsonNode): String = defaultParser.extractInnertubeApiKey(config)
        fun extractInitialContinuation(initialData: JsonNode): String = defaultParser.extractInitialContinuation(initialData)
        fun extractChannelName(initialData: JsonNode): String = defaultParser.extractChannelName(initialData)
    }

    private val ytcfgMarkers = listOf(
        "ytcfg.set(",
        "ytcfg .set(",
        "ytcfg.set (",
        "ytcfg . set(",
        "ytcfg . set ("
    )

    private val initialDataMarkers = listOf(
        "window[\"ytInitialData\"]",
        "window['ytInitialData']",
        "var ytInitialData",
        "ytInitialData =",
        "ytInitialData="
    )

    private val continuationKeys = listOf(
        "invalidationContinuationData",
        "timedContinuationData",
        "reloadContinuationData",
        "liveChatReplayContinuationData",
        "nextContinuationData"
    )

    private fun parseYoutubeConfigInternal(html: String): ObjectNode {
        var firstConfig: ObjectNode? = null
        var configWithApiKey: ObjectNode? = null

        for (marker in ytcfgMarkers) {
            var searchFrom = 0
            while (searchFrom < html.length) {
                val markerIndex = html.indexOf(marker, searchFrom)
                if (markerIndex < 0) break
                val openBrace = html.indexOf('{', markerIndex + marker.length)
                if (openBrace >= 0) {
                    val objectText = extractBalancedObject(html, openBrace)
                    if (!objectText.isNullOrBlank()) {
                        val parsed = runCatching { mapper.readTree(objectText) as? ObjectNode }.getOrNull()
                        if (parsed != null) {
                            if (firstConfig == null) firstConfig = parsed
                            // Every ytcfg.set block can contain a different part of
                            // the current request context, so feed all of them into
                            // the runtime context extractor before choosing the API-key block.
                            LiveChatRequestContext.update(parsed)
                            if (configWithApiKey == null &&
                                findTextByKeys(parsed, setOf("INNERTUBE_API_KEY")) != null) {
                                configWithApiKey = parsed
                            }
                        }
                    }
                }
                searchFrom = markerIndex + marker.length
            }
        }

        return configWithApiKey ?: firstConfig
            ?: throw IllegalStateException("Unable to locate ytcfg.set() in YouTube response")
    }

    private fun parseInitialDataInternal(html: String): ObjectNode {
        val json = extractObjectAfterMarkers(html, initialDataMarkers)
            ?: throw IllegalStateException("Unable to locate ytInitialData in YouTube response")
        return mapper.readTree(json) as? ObjectNode
            ?: throw IllegalStateException("YouTube ytInitialData is not a JSON object")
    }

    private fun extractInnertubeApiKeyInternal(config: JsonNode): String {
        return findTextByKeys(config, setOf("INNERTUBE_API_KEY"))
            ?: throw IllegalStateException("YouTube INNERTUBE_API_KEY was not found")
    }

    private fun extractInitialContinuationInternal(initialData: JsonNode): String {
        // Prefer a continuation belonging to a liveChatRenderer, but do not
        // assume YouTube keeps the renderer at one fixed JSON path.
        findLiveChatContinuation(initialData)?.let { return it }

        // Some page variants expose the live-chat continuation through a
        // continuationEndpoint instead of the renderer's continuations array.
        findContinuation(initialData)?.let { return it }

        throw IllegalStateException("YouTube live chat continuation was not found")
    }

    private fun extractChannelNameInternal(initialData: JsonNode): String {
        val liveChatRenderer = findObjectByKey(initialData, "liveChatRenderer")
        if (liveChatRenderer != null) {
            findTextByKeys(liveChatRenderer, setOf("channelName"))?.let { return it }
        }

        findObjectByKey(initialData, "videoOwnerRenderer")?.let { owner ->
            extractDisplayText(owner.path("title"))?.let { return it }
            extractDisplayText(owner.path("shortBylineText"))?.let { return it }
        }

        findTextByKeys(initialData, setOf("ownerText", "channelName"))?.let { return it }
        return "YouTube"
    }

    private fun findLiveChatContinuation(node: JsonNode?): String? {
        if (node == null || node.isMissingNode || node.isNull) return null
        if (node.isObject) {
            val renderer = node.get("liveChatRenderer")
            if (renderer != null) {
                findContinuation(renderer)?.let { return it }
            }
            val fields = node.fields()
            while (fields.hasNext()) {
                findLiveChatContinuation(fields.next().value)?.let { return it }
            }
        } else if (node.isArray) {
            for (child in node) {
                findLiveChatContinuation(child)?.let { return it }
            }
        }
        return null
    }

    private fun findContinuation(node: JsonNode?): String? {
        if (node == null || node.isMissingNode || node.isNull) return null

        if (node.isObject) {
            for (key in continuationKeys) {
                val candidate = node.get(key)
                if (candidate != null && candidate.isObject) {
                    val token = candidate.path("continuation").asText(null)
                    if (!token.isNullOrBlank()) return token
                }
            }

            val direct = node.path("continuation").asText(null)
            if (!direct.isNullOrBlank()) return direct

            val continuationCommand = node.get("continuationCommand")
            val commandToken = continuationCommand?.path("token")?.asText(null)
            if (!commandToken.isNullOrBlank()) return commandToken

            val token = node.path("token").asText(null)
            if (!token.isNullOrBlank() && node.has("commandMetadata")) return token

            val fields = node.fields()
            while (fields.hasNext()) {
                val found = findContinuation(fields.next().value)
                if (!found.isNullOrBlank()) return found
            }
        } else if (node.isArray) {
            for (child in node) {
                val found = findContinuation(child)
                if (!found.isNullOrBlank()) return found
            }
        }
        return null
    }

    private fun findObjectByKey(node: JsonNode?, key: String): JsonNode? {
        if (node == null || node.isMissingNode || node.isNull) return null

        if (node.isObject) {
            val direct = node.get(key)
            if (direct != null && direct.isObject) return direct
            val fields = node.fields()
            while (fields.hasNext()) {
                val found = findObjectByKey(fields.next().value, key)
                if (found != null) return found
            }
        } else if (node.isArray) {
            for (child in node) {
                val found = findObjectByKey(child, key)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findTextByKeys(node: JsonNode?, keys: Set<String>): String? {
        if (node == null || node.isMissingNode || node.isNull) return null

        if (node.isObject) {
            for (key in keys) {
                val direct = node.get(key)
                if (direct != null) {
                    extractDisplayText(direct)?.let { return it }
                    if (direct.isValueNode) {
                        val text = direct.asText()
                        if (text.isNotBlank()) return text
                    }
                }
            }

            val fields = node.fields()
            while (fields.hasNext()) {
                val found = findTextByKeys(fields.next().value, keys)
                if (!found.isNullOrBlank()) return found
            }
        } else if (node.isArray) {
            for (child in node) {
                val found = findTextByKeys(child, keys)
                if (!found.isNullOrBlank()) return found
            }
        }
        return null
    }

    private fun extractDisplayText(node: JsonNode?): String? {
        if (node == null || node.isMissingNode || node.isNull) return null
        if (node.isTextual) {
            val text = node.asText(null)
            if (!text.isNullOrBlank()) return text
        }
        val simpleText = node.path("simpleText").asText(null)
        if (!simpleText.isNullOrBlank()) return simpleText

        val runs = node.path("runs")
        if (runs.isArray) {
            val text = buildString {
                for (run in runs) append(run.path("text").asText(""))
            }
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun extractObjectAfterMarkers(html: String, markers: List<String>): String? {
        for (marker in markers) {
            var searchFrom = 0
            while (searchFrom < html.length) {
                val markerIndex = html.indexOf(marker, searchFrom)
                if (markerIndex < 0) break
                val openBrace = html.indexOf('{', markerIndex + marker.length)
                if (openBrace >= 0) {
                    val objectText = extractBalancedObject(html, openBrace)
                    if (!objectText.isNullOrBlank()) {
                        runCatching { mapper.readTree(objectText) }.onSuccess { return objectText }
                    }
                }
                searchFrom = markerIndex + marker.length
            }
        }
        return null
    }

    /** Extract one JSON object while respecting quoted strings and escaped quotes. */
    private fun extractBalancedObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }
}
