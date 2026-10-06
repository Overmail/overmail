package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthProvidersTest {

    private val client = OAuthClientConfig(clientId = "id", clientSecret = "secret")

    @Test
    fun `only providers with a client are usable`() {
        val providers = OAuthProviders(mapOf("microsoft" to client, "unknown" to client), "https://overmail.example")
        assertEquals(listOf(OAuthProvider.MICROSOFT), providers.configured)
        assertEquals(listOf(OAuthProvider.MICROSOFT), providers.clients.map { it.provider })
        assertNull(providers.byId("google"))
        assertNull(providers.byId("unknown"))
    }

    @Test
    fun `a client reaches its provider at the provider's own endpoints`() {
        val providers = OAuthProviders(mapOf("google" to client), "https://overmail.example")
        assertEquals(OAuthProvider.GOOGLE.endpoints, providers.byId("google")!!.endpoints)
    }
}
