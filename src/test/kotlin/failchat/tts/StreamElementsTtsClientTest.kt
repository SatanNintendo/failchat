package failchat.tts

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import failchat.okHttpClient
import failchat.testObjectMapper
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertTrue

class StreamElementsTtsClientTest {

    private lateinit var wireMock: WireMockServer

    @Before
    fun setUp() {
        wireMock = WireMockServer(options().dynamicPort())
        wireMock.start()
    }

    @After
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun extractsAuthTokenFromJwtBeforeSendingRequest() {
        val payload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("""{"authToken":"test-auth-token"}""".toByteArray())
        val jwt = "header.$payload.signature"

        wireMock.stubFor(
            get(urlPathEqualTo("/speech"))
                .withQueryParam("key", equalTo("test-auth-token"))
                .withQueryParam("voice", equalTo("Alena"))
                .withQueryParam("text", equalTo("Hello"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "audio/mpeg")
                        .withBody(byteArrayOf(1, 2, 3, 4))
                )
        )

        val client = StreamElementsTtsClient(okHttpClient, testObjectMapper)
        val audioFile = client.synthesize(
            text = "Hello",
            voice = "Alena",
            key = jwt,
            apiUrl = wireMock.baseUrl() + "/speech"
        )

        try {
            assertTrue(Files.size(audioFile) > 0)
        } finally {
            Files.deleteIfExists(audioFile)
        }

        wireMock.verify(
            get(urlPathEqualTo("/speech"))
                .withQueryParam("key", equalTo("test-auth-token"))
                .withQueryParam("voice", equalTo("Alena"))
                .withQueryParam("text", equalTo("Hello"))
        )
    }
}
