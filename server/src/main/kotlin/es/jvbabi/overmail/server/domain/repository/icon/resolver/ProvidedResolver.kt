package es.jvbabi.overmail.server.domain.repository.icon.resolver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.toByteArray

/**
 * The logos we simply know, because nobody publishes them: a hand-kept list of senders mapped to
 * the url their mark sits at. Asked after BIMI, since a sender that states its own logo is more
 * right about it than this list is.
 */
class ProvidedResolver(client: HttpClient): EmailIconResolver(client) {

    override val identifier: String = "provided"

    companion object {

        /**
         * Logos we know about, keyed by who they belong to. A key is one of:
         *
         * - a domain — `email.openai.com`
         * - a wildcard domain — `*.myhpi.de`, matching any subdomain of it
         * - a full address — `notifications@github.com`
         * - the two mixed — `notifications@*.server.com`
         *
         * The wildcard only works at the start of the domain and has to be followed by a dot.
         * Matching ignores case, and the most specific key wins: a full address beats a domain,
         * and an exact domain beats a wildcard.
         */
        val addresses = mapOf(
            "email.openai.com" to "https://svgl.app/library/openai.svg",
            "*.myhpi.de" to "https://hpi.de/_assets/7b3ba8bb3871137dffa4d9cccacf730f/Images/FavAndTouchIcons/favicon-32x32.png",
            "kontakt@manos-dresden.lernsax.de" to "https://manos-dresden.de/wp-content/uploads/2026/07/M-1.svg",
            "github.com" to "https://github.githubassets.com/favicons/favicon.svg",
            "spaceship.com" to "https://spaceship-cdn.com/static/spaceship/favicon/spaceship-icon.svg",
            "noreply@auth.beste.schule" to "https://beste.schule/favicon.svg",
            "paypal.de" to "https://www.paypalobjects.com/webstatic/icon/pp258.png",
            "*.paypal.com" to "https://www.paypalobjects.com/webstatic/icon/pp258.png",
            "email.mentimeter.com" to "https://static.mentimeter.com/assets/logotype/favicon-192x192.png?v=2",
            "bugzilla-daemon@webkit.org" to "https://webkit.org/favicon.ico",
            "ovh.de" to "https://www.ovhcloud.com/icon.svg",
            "spamrats.com" to "https://spamrats.com/img/favicon.png",
            "schulverwalter.de" to "https://schulverwalter.de/favicon-32x32.png",
            "uni-potsdam.de" to "https://www.uni-potsdam.de/_assets/f823c47d2218337f4f76c4a855364aab/Icons/BrowserIcons/favicon.ico",
            "spotify.com" to "https://svgl.app/library/spotify.svg",
            "legal.spotify.com" to "https://svgl.app/library/spotify.svg",
            "blacksmith.sh" to "https://cdn.prod.website-files.com/681bfb0c9a4601bc6e288ec4/684a16c003e68ef866f5f528_logo-blacksmith-favicon-light.png",
            "commonmain.dev" to "https://storage.ghost.io/c/57/59/57597180-415a-44ab-aa77-38b4467c31d5/content/images/size/w256h256/2026/01/-Icon--Gemini-icon-squircle-1.png",
            "ebay.com" to "https://api.svgl.app/svg/ebay.svg",
            "*.ebay.com" to "https://api.svgl.app/svg/ebay.svg",
            "ebay.de" to "https://api.svgl.app/svg/ebay.svg",
            "*.ebay.de" to "https://api.svgl.app/svg/ebay.svg",
            "slack.com" to "https://api.svgl.app/svg/slack.svg",
            "*.slack.com" to "https://api.svgl.app/svg/slack.svg",
            "playpartners-noreply@google.com" to "https://svgl.app/library/googleplay.svg",
            "googleplay-noreply@google.com" to "https://svgl.app/library/googleplay.svg",
            "accounts.google.com" to "https://svgl.app/library/google.svg",
            "cloudflare.com" to "https://svgl.app/library/cloudflare.svg",
            "*.cloudflare.com" to "https://svgl.app/library/cloudflare.svg",
            "account.netflix.com" to "https://svgl.app/library/netflix-icon.svg",
            "steampowered.com" to "https://svgl.app/library/steam.svg",
            "mail.instagram.com" to "https://svgl.app/library/instagram-icon.svg",
            "noreply@melious.ai" to "https://cdn.melious.ai/favicon/favicon-96x96.png",
            "slothbytes@mail.beehiiv.com" to "https://media.beehiiv.com/cdn-cgi/image/fit=scale-down,format=auto,onerror=redirect,quality=80/uploads/publication/logo/785df2cf-6a8b-4daa-b929-7bf60c7f57cb/thumb_sloth_bytes_logo.png"
        )

        /** @return the logo for [address], or null when no key covers it. */
        internal fun findLogoUrl(address: String): String? {
            val local = address.substringBefore('@', missingDelimiterValue = "").lowercase()
            val domain = address.substringAfter('@', missingDelimiterValue = "").lowercase()
            if (domain.isEmpty()) return null

            return addresses.entries
                .filter { (key, _) -> key.covers(local, domain) }
                .maxByOrNull { (key, _) -> key.specificity() }
                ?.value
        }

        private fun String.covers(local: String, domain: String): Boolean {
            // A key without an "@" says nothing about the local part, so it matches any.
            val keyLocal = substringBefore('@', missingDelimiterValue = "").lowercase()
            if (keyLocal.isNotEmpty() && keyLocal != local) return false

            val keyDomain = substringAfterLast('@').lowercase()

            // removePrefix("*") keeps the leading dot, so this cannot match the bare domain —
            // `*.myhpi.de` is subdomains only, as that syntax means everywhere else. Add the
            // apex as its own key if you want it too.
            return if (keyDomain.startsWith("*.")) domain.endsWith(keyDomain.removePrefix("*"))
            else domain == keyDomain
        }

        /** Higher wins, so mail to a listed address is not answered with its domain's logo. */
        private fun String.specificity(): Int {
            val hasLocal = '@' in this
            val isWildcard = substringAfterLast('@').startsWith("*.")

            return when {
                hasLocal && !isWildcard -> 3
                hasLocal -> 2
                !isWildcard -> 1
                else -> 0
            }
        }
    }

    override suspend fun handle(address: String, name: String?): ByteArray? {
        val logoUrl = findLogoUrl(address) ?: return null

        val response = client.get(logoUrl)

        // A configured logo that answers anything else is treated as "not right now" rather than
        // as an error: the address keeps no record of the miss, so the next refresh asks again,
        // and the resolvers after this one still get their turn in the meantime.
        return if (response.status == HttpStatusCode.OK) response.bodyAsChannel().toByteArray()
        else null
    }
}
