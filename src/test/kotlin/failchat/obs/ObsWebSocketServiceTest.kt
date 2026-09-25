package failchat.obs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ObsWebSocketServiceTest : StringSpec({
    "creates obs-websocket authentication according to protocol 5.x" {
        val service = ObsWebSocketService(
            config = throw UnsupportedOperationException("not required"),
            objectMapper = throw UnsupportedOperationException("not required"),
            executor = throw UnsupportedOperationException("not required")
        )

        service.createAuthentication(
            password = "supersecretpassword",
            salt = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
            challenge = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
        ) shouldBe "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4="
    }

    "recognizes only local failchat Browser Source URLs on the active port" {
        val oldPort = failchat.FailchatServerInfo.port
        failchat.FailchatServerInfo.port = 10880
        try {
            val service = ObsWebSocketService(
                config = throw UnsupportedOperationException("not required"),
                objectMapper = throw UnsupportedOperationException("not required"),
                executor = throw UnsupportedOperationException("not required")
            )

            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10880/chat/old_sc2tv") shouldBe true
            service.isFailchatBrowserSourceUrl("http://localhost:10880/chat") shouldBe true
            service.isFailchatBrowserSourceUrl("http://[::1]:10880/chat") shouldBe true
            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10881/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("https://127.0.0.1:10880/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("http://example.com:10880/chat") shouldBe false
            service.isFailchatBrowserSourceUrl("http://127.0.0.1:10880/resources/old_sc2tv/old_sc2tv.html") shouldBe false
        } finally {
            failchat.FailchatServerInfo.port = oldPort
        }
    }
})
