package es.jvbabi.overmail.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import nl.jacobras.humanreadable.HumanReadable

/**
 * Keeps `HumanReadable`'s language in step with the locale Compose renders in.
 *
 * Human-Readable detects the system language once, when it is first touched, and holds it in global
 * state for the rest of the process — a locale change while the app runs would leave relative times
 * such as "5 minutes ago" in the previous language while everything around them switched. Reading
 * the locale from the composition instead ties the two together.
 *
 * Deliberately applied *during* composition rather than from a side effect: callers format
 * timestamps while they compose, so a side effect would run too late for the first frame.
 */
@Composable
fun SyncHumanReadableLocale() {
    val languageTag = Locale.current.toLanguageTag()
    remember(languageTag) { HumanReadable.config.languageTag = languageTag }
}
