package es.jvbabi.overmail.server.util

/**
 * `julius@familie-babies.de` becomes `j***@familie-babies.de` -- enough to recognise an address in
 * a log, not enough to hand somebody a mailbox to write to.
 *
 * The domain is kept because it is what makes a log line useful, and it is not personal on its
 * own. Anything that is not an address at all masks completely.
 */
fun String.maskEmail(): String {
    val local = substringBefore('@')
    val domain = substringAfter('@', missingDelimiterValue = "")

    if (local.isEmpty() || domain.isEmpty()) return "***"
    return "${local.first()}***@$domain"
}
