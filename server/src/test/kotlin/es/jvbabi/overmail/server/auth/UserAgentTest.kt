package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.models.Session
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a session list shows for the browsers people actually sign in with. */
class UserAgentTest {

    @Test
    fun `desktop browsers`() {
        assertEquals(
            Session.Client.Web("Firefox 131", "Mac", "macOS"),
            webClientOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:131.0) Gecko/20100101 Firefox/131.0"),
        )
        assertEquals(
            Session.Client.Web("Edge 129", "Desktop", "Windows"),
            webClientOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0"),
        )
        assertEquals(
            Session.Client.Web("Safari 18.0", "Mac", "macOS"),
            webClientOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"),
        )
    }

    @Test
    fun `mobile browsers`() {
        assertEquals(
            Session.Client.Web("Safari 17.4", "iPhone", "iOS 17.4"),
            webClientOf("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"),
        )
        assertEquals(
            Session.Client.Web("Chrome 129", "Android phone", "Android 10"),
            webClientOf("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"),
        )
        assertEquals(
            Session.Client.Web("Samsung Internet 25", "SM-S918B", "Android 14"),
            webClientOf("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36"),
        )
    }

    @Test
    fun `no user agent is unknown`() {
        assertEquals(Session.Client.Web("unknown", "unknown", "unknown"), webClientOf(null))
        assertEquals(Session.Client.Web("unknown", "unknown", "unknown"), webClientOf(" "))
    }
}
