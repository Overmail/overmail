package es.jvbabi.overmail.server.data.avatar.resolver

import es.jvbabi.overmail.server.data.avatar.AvatarResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.toByteArray

/**
 * The logos we simply know, because nobody publishes them: a hand-kept list of senders mapped to
 * the url their mark sits at. Asked before BIMI -- that is what the list is for: a sender whose
 * published logo is generic or wrong is fixed by putting the right url in here.
 */
class ProvidedResolver(client: HttpClient) : AvatarResolver(client) {

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
            "tm.openai.com" to "https://svgl.app/library/openai.svg",
            "*.myhpi.de" to "https://hpi.de/_assets/7b3ba8bb3871137dffa4d9cccacf730f/Images/FavAndTouchIcons/apple-touch-icon.png",
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
            "noreply-accounts@google.com" to "https://svgl.app/library/google.svg",
            "sc-noreply@google.com" to "https://svgl.app/library/google.svg",
            "cloudflare.com" to "https://svgl.app/library/cloudflare.svg",
            "*.cloudflare.com" to "https://svgl.app/library/cloudflare.svg",
            "account.netflix.com" to "https://svgl.app/library/netflix-icon.svg",
            "steampowered.com" to "https://svgl.app/library/steam.svg",
            "mail.instagram.com" to "https://svgl.app/library/instagram-icon.svg",
            "noreply@melious.ai" to "https://cdn.melious.ai/favicon/favicon-96x96.png",
            "slothbytes@mail.beehiiv.com" to "https://media.beehiiv.com/cdn-cgi/image/fit=scale-down,format=auto,onerror=redirect,quality=80/uploads/publication/logo/785df2cf-6a8b-4daa-b929-7bf60c7f57cb/thumb_sloth_bytes_logo.png",
            "mail.baseten.co" to "https://www.baseten.co/brand/mark/svg/Baseten_Symbol-9.svg",
            "apple.com" to "https://svgl.app/library/apple.svg",
            "email.apple.com" to "https://svgl.app/library/apple.svg",
            "insideapple.apple.com" to "https://svgl.app/library/apple.svg",
            "sa.noreply@samsung-mail.com" to "https://v3.account.samsung.com/favicon.ico",
            "noreply@t3.codes" to "https://raw.githubusercontent.com/pingdotgg/t3code/refs/heads/main/apps/marketing/public/icon.png",
            "figma.com" to "https://svgl.app/library/figma.svg",
            "tools@youngfounders.network" to "https://tools@youngfounders.network/yfn-logo.svg",
            "traderepublic.com" to "https://companieslogo.com/img/orig/traderepublic-4820badb.svg?t=1720244494&download=true",
            "finanzguru.de" to "https://cdn.brandfetch.io/idbbPE_Vqt/theme/dark/symbol.svg?c=1dxbfHSJFAPEGdCLU4o5B",
            "linkedin.com" to "https://svgl.app/library/linkedin.svg",
            "posthog.com" to "https://svgl.app/library/posthog.svg",
            "frame.work" to "https://frame.work/favicon-192x192.png",
            "ovh.com" to "https://www.ovhcloud.com/icon.svg",
            "services.ovhcloud.com" to "https://www.ovhcloud.com/icon.svg",
            "jetbrains.com" to "https://svgl.app/library/jetbrainsSolid.svg",
            "microsoft.com" to "https://svgl.app/library/microsoft.svg",
            "communication.microsoft.com" to "https://svgl.app/library/microsoft.svg",
            "windowsinsiderprogram@e-mails.microsoft.com" to "https://svgl.app/library/windows.svg",
            "e-mails.microsoft.com" to "https://svgl.app/library/microsoft.svg",
            "mail.threads.net" to "https://svgl.app/library/threads.svg",
            "reddit.com" to "https://svgl.app/library/reddit.svg",
            "notion.com" to "https://svgl.app/library/notion.svg",
            "updates.notion.so" to "https://svgl.app/library/notion.svg",
            "updates.notion.com" to "https://svgl.app/library/notion.svg",
            "gopass.travel" to "https://production-brandfetch-assets.s3.amazonaws.com/id4sWmjd2t/idGLMNmDO3.png?AWSAccessKeyId=ASIAXC6OOL7LD664Y3OZ&Expires=1788513873&Signature=RRCcKlZySDy8DYc0n3mO7EAp%2FKI%3D&X-Amzn-Trace-Id=Root%3D1-6a9a8e14-3fac5d130031d1ae68897d0c%3BParent%3D716cd11668e5095a%3BSampled%3D0%3BLineage%3D2%3A85acc70c%3A0&response-content-disposition=attachment%3B%20filename%20%3D%22idR7bpBfnp_logos.png%22&response-expires=Fri%2C%2004%20Sep%202026%2009%3A24%3A33%20GMT&x-amz-security-token=IQoJb3JpZ2luX2VjECkaCXVzLWVhc3QtMSJHMEUCIQDm6iSQv9LzDuKHmXz4zSkp8TxmyyV3yKmwkS00T1TSyQIgF%2FiR6hRb5ruCn%2FIrrMKhPFYHVLBR%2FxY5Cv5gTLXZpmgqkAQI8v%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARADGgw0ODczNzQ4NzI1MzQiDErd2cvik6oaKYk3xSrkA5jRtfjD7d74s7zDhTZb6%2BU6PtPW%2BzBZhm7uCPgWQQ2SlY7oxMvkhsXttugr4zJaa%2FacPNv6%2F%2FJvVPzd%2B4tyz2Upend%2Fu8Ghr%2BNXJ3hGGqeOSgQAEwhjG%2FxWQLQiMkQoscx5%2Fzu0S%2B1P5JTDW3RutHr2xy6cOQm2LpR%2F2jWlz%2BTMZZqUYC%2BXxQf7d%2B1JmJ0PVP0HtZuavSSrbBbYyYAY0hFwGWP40yOtoMKTdXeIZuvXm%2FTNYAW%2FP%2F2OxEOpi8Ozg6Q69fKiCUz7hWPi4OMdVV8pBhaqBgD4i4oj4dDBYhhvPuOu%2BsVSz1IugJqupk0CjAE1%2FiyAQReHKsu5vmPZf3MRDV3MG4BpnAqL5Ng2YLpq5hCuCHhbuT1czhL%2FuuPQnRobiZsnB%2BS5%2BgGYAnPUimMSs0EHuaSRTy1FvVuTvNgeb8YnXPvJTxpICZ%2BPwMIz6qHEofVfH13DJU2WCrLCMKu2YaxBVFsGfoPoS8KoTfIHC6lD8seKSuFUC%2FYFTVTTQ39uXVYu0zFLkpSJ1BhmBAd1ZjDEfmu3r%2FoC4kWN79Sa%2F1NGrDYNv8N76VRhCvM9vQncXFpAYw2xef87Cik9pnohkNRKKBfc9WPmHKgvbOaHtNCtva4tJ8xuOq1ks7H4UrUowDAw2Ivq1AY6ogG%2BFx8g%2FWKB54KN1mnqh3yKViiM0IrneMq1sMVgrAKuYM8HyIyw3ndQgmS49kr1WBwoLVbvZE5%2ByZcwnk9bBQSUmwEZQhJ1nXaq8ftI9uYQgQvoDK%2FgBVMWk0BkB9qHoXdCtnX36rPzR5i9ZzPy1XSJO%2FnyOGGriVqs8%2FJMLlo5s35RdPkw04%2Fe7JbO3lNjzfWOMaDpHd6UjTZmtC9J5PY976k%3D",
            "zap-hosting.com" to "https://zap-hosting.biz/download/763/?tmstv=1692119072",
            "commerzbank.com" to "https://companieslogo.com/img/orig/CBK.F-b8815dc3.svg?t=1747071979&download=true",
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
