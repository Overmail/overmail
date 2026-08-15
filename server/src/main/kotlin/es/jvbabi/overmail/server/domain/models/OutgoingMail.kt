package es.jvbabi.overmail.server.domain.models

/** A bare header address. Unlike [EmailUser] this is not stored and belongs to no user. */
data class MailAddress(
    val address: String,
    val name: String? = null,
)

/**
 * A mail we are about to hand to the SMTP server. The sender is not part of this: it is the
 * account from `data/config.json`, which is the only one we may authenticate as.
 */
data class OutgoingMail(
    val to: List<MailAddress>,
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val subject: String,
    val textContent: String? = null,
    val htmlContent: String? = null,
)
