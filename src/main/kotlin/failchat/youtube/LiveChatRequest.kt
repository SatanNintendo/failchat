package failchat.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Values extracted from the current YouTube page are copied here before the
 * first Innertube request is created.  The old project hard-coded a 2020
 * clientVersion and visitorData, which is no longer a reliable request context.
 */
object LiveChatRequestContext {
    @Volatile var hl: String = "en-US"
    @Volatile var gl: String = "US"
    @Volatile var visitorData: String = ""
    @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    @Volatile var clientVersion: String = "2.20250101.00.00"

    fun update(config: JsonNode) {
        findText(config, "INNERTUBE_CLIENT_VERSION")?.takeIf { it.isNotBlank() }?.let { clientVersion = it }
        findText(config, "VISITOR_DATA")?.takeIf { it.isNotBlank() }?.let { visitorData = it }
        findText(config, "INNERTUBE_USER_AGENT")?.takeIf { it.isNotBlank() }?.let { userAgent = it }

        val context = findObject(config, "INNERTUBE_CONTEXT")
        val client = context?.path("client")
        if (client != null && client.isObject) {
            client.path("hl").asText(null)?.takeIf { it.isNotBlank() }?.let { hl = it }
            client.path("gl").asText(null)?.takeIf { it.isNotBlank() }?.let { gl = it }
            client.path("visitorData").asText(null)?.takeIf { it.isNotBlank() }?.let { visitorData = it }
            client.path("userAgent").asText(null)?.takeIf { it.isNotBlank() }?.let { userAgent = it }
            client.path("clientVersion").asText(null)?.takeIf { it.isNotBlank() }?.let { clientVersion = it }
        }
    }

    private fun findText(node: JsonNode?, key: String): String? {
        if (node == null || node.isMissingNode || node.isNull) return null
        if (node.isObject) {
            val direct = node.get(key)
            if (direct != null && direct.isValueNode) return direct.asText()
            val fields = node.fields()
            while (fields.hasNext()) {
                val child = fields.next().value
                val found = findText(child, key)
                if (found != null) return found
            }
        } else if (node.isArray) {
            for (child in node) {
                val found = findText(child, key)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findObject(node: JsonNode?, key: String): JsonNode? {
        if (node == null || node.isMissingNode || node.isNull) return null
        if (node.isObject) {
            val direct = node.get(key)
            if (direct != null && direct.isObject) return direct
            val fields = node.fields()
            while (fields.hasNext()) {
                val found = findObject(fields.next().value, key)
                if (found != null) return found
            }
        } else if (node.isArray) {
            for (child in node) {
                val found = findObject(child, key)
                if (found != null) return found
            }
        }
        return null
    }
}

data class LiveChatRequest(
    val context: Context = Context(),
    val continuation: String
) {

    data class Context(
        val client: Client = Client(),
        val request: Request = Request(),
        val user: ObjectNode = JsonNodeFactory.instance.objectNode(),
        val clientScreenNonce: String = ""
    )

    data class Client(
        val hl: String = LiveChatRequestContext.hl,
        val gl: String = LiveChatRequestContext.gl,
        val visitorData: String = LiveChatRequestContext.visitorData,
        val userAgent: String = LiveChatRequestContext.userAgent + ",gzip(gfe)",
        val clientName: String = "WEB",
        val clientVersion: String = LiveChatRequestContext.clientVersion,
        val osName: String = "Windows",
        val osVersion: String = "10.0",
        val browserName: String = "Chrome",
        val browserVersion: String = "131.0.0.0",
        val screenWidthPoints: Int = 1920,
        val screenHeightPoints: Int = 1080,
        val screenPixelDensity: Int = 1,
        val utcOffsetMinutes: Int = 0,
        val userInterfaceTheme: String = "USER_INTERFACE_THEME_LIGHT"
    )

    data class Request(
        val internalExperimentFlags: ArrayNode = JsonNodeFactory.instance.arrayNode(),
        val consistencyTokenJars: ArrayNode = JsonNodeFactory.instance.arrayNode()
    )
}
