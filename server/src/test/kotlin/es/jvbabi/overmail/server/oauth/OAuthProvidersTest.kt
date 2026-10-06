package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthProvidersTest {

    private val client = OAuthClientConfig(clientId = "id", clientSecret = "secret")

    @Test
    fun `the redirect uri is the callback route under the base url`() {
        for (baseUrl in listOf("https://overmail.example", "https://overmail.example/")) {
            val providers = OAuthProviders(mapOf("google" to client), baseUrl)
            assertEquals(
                "https://overmail.example/api/oauth/google/callback",
                providers.byId("google")!!.redirectUri,
                "for $baseUrl",
            )
        }
    }

    @Test
    fun `a base url with a path keeps it`() {
        val providers = OAuthProviders(mapOf("microsoft" to client), "https://example.com/overmail")
        assertEquals(
            "https://example.com/overmail/api/oauth/microsoft/callback",
            providers.byId("microsoft")!!.redirectUri,
        )
    }

    @Test
    fun `only providers with a client are usable`() {
        val providers = OAuthProviders(mapOf("microsoft" to client, "unknown" to client), "https://overmail.example")
        assertEquals(listOf(OAuthProvider.MICROSOFT), providers.configured)
        assertNull(providers.byId("google"))
        assertNull(providers.byId("unknown"))
    }
}
