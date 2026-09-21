package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.models.Session

/**
 * What a session list shows for a browser: "Firefox 131", "macOS", "Mac". Deliberately coarse --
 * it only has to let somebody recognize their own sessions, not identify anything exactly.
 */
internal fun webClientOf(userAgent: String?): Session.Client.Web {
    val ua = userAgent?.takeIf { it.isNotBlank() }
        ?: return Session.Client.Web(Session.Client.UNKNOWN, Session.Client.UNKNOWN, Session.Client.UNKNOWN)
    return Session.Client.Web(browser = browserOf(ua), device = deviceOf(ua), os = osOf(ua))
}

/** First match wins: Edge, Opera and Samsung Internet all claim to be Chrome as well, and Chrome to be Safari. */
private val BROWSERS = listOf(
    "Edge" to Regex("""Edg(?:e|A|iOS)?/(\d+)"""),
    "Opera" to Regex("""(?:OPR|OPT)/(\d+)"""),
    "Samsung Internet" to Regex("""SamsungBrowser/(\d+)"""),
    "Firefox" to Regex("""(?:Firefox|FxiOS)/(\d+)"""),
    "Chrome" to Regex("""(?:Chrome|CriOS)/(\d+)"""),
    "Safari" to Regex("""Version/(\d+(?:\.\d+)?).*Safari/"""),
)

private fun browserOf(ua: String): String = BROWSERS.firstNotNullOfOrNull { (name, regex) ->
    regex.find(ua)?.let { "$name ${it.groupValues[1]}" }
} ?: Session.Client.UNKNOWN

private fun osOf(ua: String): String {
    // Before macOS: both iOS flavours say "like Mac OS X".
    Regex("""iPhone OS (\d+(?:_\d+)?)""").find(ua)?.let { return "iOS ${it.groupValues[1].replace('_', '.')}" }
    Regex("""iPad.*? OS (\d+(?:_\d+)?)""").find(ua)?.let { return "iPadOS ${it.groupValues[1].replace('_', '.')}" }
    Regex("""Android (\d+(?:\.\d+)?)""").find(ua)?.let { return "Android ${it.groupValues[1]}" }
    return when {
        "Windows" in ua -> "Windows"
        "CrOS" in ua -> "ChromeOS"
        // Frozen at 10_15_7 by every browser, so the version says nothing.
        "Mac OS X" in ua -> "macOS"
        "Linux" in ua -> "Linux"
        else -> Session.Client.UNKNOWN
    }
}

private fun deviceOf(ua: String): String {
    if ("iPhone" in ua) return "iPhone"
    if ("iPad" in ua) return "iPad"
    if ("Android" in ua) {
        // Chrome reduces the model to "K"; Firefox leaves it out altogether.
        val model = Regex("""Android [^;)]*; ([^;)]+?)(?: Build/[^;)]*)?\)""").find(ua)?.groupValues?.get(1)
        if (model != null && model != "K" && !model.startsWith("rv:")) return model
        return if ("Mobile" in ua) "Android phone" else "Android tablet"
    }
    if ("Macintosh" in ua) return "Mac"
    if ("Windows" in ua || "Linux" in ua || "CrOS" in ua) return "Desktop"
    return Session.Client.UNKNOWN
}
