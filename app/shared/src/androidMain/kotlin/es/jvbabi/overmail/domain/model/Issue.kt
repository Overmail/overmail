package es.jvbabi.overmail.domain.model

/** The GitHub project the app is released from, and where its issues are tracked. */
const val GITHUB_REPOSITORY = "Overmail/overmail"

/**
 * Web URL of [issue].
 *
 * Changelog entries are keyed by issue number, and this turns such a key into the link behind it.
 */
fun issueUrl(issue: Int): String = "https://github.com/$GITHUB_REPOSITORY/issues/$issue"
