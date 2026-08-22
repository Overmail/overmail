package es.jvbabi.overmail.server.domain.repository.icon.resolver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.Lookup
import org.xbill.DNS.Type

/**
 * BIMI, the sender's own answer to "what do you look like": a TXT record at
 * `default._bimi.<domain>` naming an SVG logo. Covers every domain that bothered to publish one,
 * which is what makes it the resolver for senders nobody put on a list.
 */
class BimiResolver(
    client: HttpClient
) : EmailIconResolver(client) {

    override val identifier: String = "bimi"

    override suspend fun handle(
        address: String,
        name: String?
    ): ByteArray? {
        val domain = address.substringAfter("@")

        // dnsjava resolves blocking, and this runs inside the same scope the http calls do.
        val records = withContext(Dispatchers.IO) {
            Lookup("default._bimi.$domain", Type.TXT).run()
        } ?: return null

        for (record in records) {
            val txtRecord = record.rdataToString()

            val tags = txtRecord
                .split(";")
                .mapNotNull {
                    val parts = it.trim().split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0] to parts[1]
                    } else {
                        null
                    }
                }
                .toMap()

            if (tags["v"] != "BIMI1") {
                continue
            }

            val logoUrl = tags["l"] ?: continue

            val response = client.get(logoUrl)
            if (response.status.value == 200) {
                return response.bodyAsChannel().toByteArray()
            }
        }

        return null
    }
}
