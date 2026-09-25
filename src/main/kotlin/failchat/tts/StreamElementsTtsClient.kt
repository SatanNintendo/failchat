package failchat.tts

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

class StreamElementsTtsClient(
    httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val MAX_AUDIO_BYTES = 5L * 1024L * 1024L
    }

    private val requestClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads synthesized speech to a temporary file.
     *
     * The returned file belongs to the caller and must be deleted after playback.
     * The key may be either the StreamElements authToken or a complete JWT containing
     * the authToken in its payload.
     */
    fun synthesize(text: String, voice: String, key: String, apiUrl: String): Path {
        val resolvedKey = resolveAuthToken(key)
        val url = HttpUrl.parse(apiUrl)?.newBuilder()
            ?.addQueryParameter("voice", voice)
            ?.addQueryParameter("text", text)
            ?.addQueryParameter("key", resolvedKey)
            ?.build()
            ?: throw IllegalArgumentException("Invalid TTS API URL")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        requestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TTS service returned HTTP ${response.code()}")
            }

            val body = response.body()
                ?: throw IOException("TTS service returned an empty response")

            val contentLength = body.contentLength()
            if (contentLength == 0L) {
                throw IOException("TTS service returned an empty response")
            }
            if (contentLength > MAX_AUDIO_BYTES) {
                throw IOException("TTS response is too large")
            }

            val tempFile = Files.createTempFile("failchat-tts-", ".mp3")
            try {
                body.byteStream().use { input ->
                    Files.newOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break

                            total += read
                            if (total > MAX_AUDIO_BYTES) {
                                throw IOException("TTS response is too large")
                            }

                            output.write(buffer, 0, read)
                        }
                    }
                }

                if (Files.size(tempFile) == 0L) {
                    throw IOException("TTS service returned an empty response")
                }

                return tempFile
            } catch (t: Throwable) {
                try {
                    Files.deleteIfExists(tempFile)
                } catch (_: Throwable) {
                }
                throw t
            }
        }
    }

    private fun resolveAuthToken(key: String): String {
        val trimmedKey = key.trim()
        val jwtParts = trimmedKey.split('.')
        if (jwtParts.size != 3) return trimmedKey

        return try {
            val payload = Base64.getUrlDecoder().decode(jwtParts[1])
            val authToken = objectMapper.readTree(payload).path("authToken").asText()
            if (authToken.isBlank()) trimmedKey else authToken
        } catch (_: Throwable) {
            // If the value is not a JWT, use it as a raw authToken.
            trimmedKey
        }
    }
}
