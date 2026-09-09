package es.jvbabi.overmail.server.http.users.me.views

/**
 * The cookie the web writes its ui language into, see `web/src/lib/i18n/index.ts`. It is set on
 * `path=/`, so it reaches this server through the same Caddy that serves the app.
 */
const val LOCALE_COOKIE = "overmail_locale"

/**
 * A language a view can be named in, with the stem its generated names are built from.
 *
 * Mirrors the catalogs the web ships (`web/src/lib/i18n/locales/`): those two languages exist,
 * and English is the fallback there as well. A language added there needs an entry here, or
 * views come out named in English while the rest of the screen is not.
 */
enum class ViewNameLanguage(val tag: String, val newViewStem: String) {
    ENGLISH("en", "New view"),
    GERMAN("de", "Neue Ansicht"),
}

/**
 * The language to name a view in: the locale cookie, else the browser's `Accept-Language`.
 *
 * Deliberately the same rule and the same order as `web/src/hooks.server.ts` -- the name is
 * written once and then stands in a list next to labels the web translated, so the two have to
 * agree on which language that is. The cookie wins outright when it is there, including when it
 * holds something unsupported: the user picked that in the ui, and falling through to the header
 * would name the view in a language they switched away from.
 */
fun pickViewNameLanguage(localeCookie: String?, acceptLanguage: String?): ViewNameLanguage {
    val value = localeCookie ?: acceptLanguage ?: return ViewNameLanguage.ENGLISH

    // Browsers send the header in preference order, so the q-values add nothing over reading it
    // left to right. A tag may be `de-DE`, and only the language subtag is of interest.
    for (tag in value.split(",")) {
        val language = tag.substringBefore(";").trim().take(2).lowercase()
        for (candidate in ViewNameLanguage.entries) {
            if (candidate.tag == language) return candidate
        }
    }
    return ViewNameLanguage.ENGLISH
}

/**
 * The name for a new view: the language's stem and the lowest number not in use, counted only
 * against names in that same language.
 *
 * Per language on purpose -- "Neue Ansicht 1" does not occupy "New view 1". Switching the ui
 * language starts a fresh count instead of continuing one the user cannot read, and a user who
 * switches back finds their old numbering intact.
 *
 * The lowest free number rather than one past the highest: deleting "Neue Ansicht 2" of three
 * should let the next one be a 2 again, otherwise the numbers climb forever and say nothing about
 * how many views there are. [existingNames] is every name the user has right now, so a renamed
 * view stops holding its number the moment it is renamed.
 */
fun nextViewName(language: ViewNameLanguage, existingNames: List<String>): String {
    val prefix = language.newViewStem + " "

    val used = mutableSetOf<Int>()
    for (name in existingNames) {
        val suffix = name.removePrefix(prefix)
        // `removePrefix` hands back the whole string when it does not match, which is how a
        // renamed view and a view named in the other language drop out here.
        if (suffix == name) continue
        val number = suffix.toIntOrNull() ?: continue
        // Not `toIntOrNull` alone: "01" parses as 1 but is a different name, and letting it
        // occupy 1 would skip a number that is in fact free.
        if (number.toString() == suffix) used.add(number)
    }

    var number = 1
    while (number in used) number++
    return language.newViewStem + " " + number
}
