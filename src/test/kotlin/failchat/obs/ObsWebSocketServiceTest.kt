package failchat.obs

import com.fasterxml.jackson.databind.ObjectMapper
import failchat.FailchatServerInfo
import io.kotest.matchers.shouldBe
import org.apache.commons.configuration2.PropertiesConfiguration
import org.junit.Test
import java.util.concurrent.Executors

class ObsWebSocketServiceTest {

    private fun createService(): ObsWebSocketService {
        // createAuthentication() and isFailchatBrowserSourceUrl() do not use
        // the injected dependencies, so lightweight instances are enough here.
        // The executor creates its worker thread lazily and no tasks are ever submitted.
        return ObsWebSocketService(
            config = PropertiesConfiguration(),
            objectMapper = ObjectMapper(),
            executor = Executors.newSingleThreadScheduledExecutor()
        )
    }

    @Test
    fun createsAuthenticationAccordingToProtocol5x() {
        val service = createService()

        service.createAuthentication(
            password = "supersecretpassword",
            salt = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
            challenge = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
        ) shouldBe "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4="
    }

    @Test
    fun recognizesOnlyLocalFailchatBrowserSourceUrlsOnTheActivePort() {
        val oldPort = FailchatServerInfo.port
        FailchatServerInfo.port = 10880
        try {
            val service = createService()

            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10880/chat/old_sc2tv") shouldBe true
            service.isFailchatBrowserSourceUrl("http://localhost:10880/chat") shouldBe true
            service.isFailchatBrowserSourceUrl("http://[::1]:10880/chat") shouldBe true
            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10881/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("https://127.0.0.1:10880/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("http://example.com:10880/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10880/resources/old_sc2tv/old_sc2tv.html") shouldBe false
        } finally {
            FailchatServerInfo.port = oldPort
        }
    }
}
