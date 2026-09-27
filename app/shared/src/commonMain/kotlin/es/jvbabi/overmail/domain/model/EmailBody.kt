package es.jvbabi.overmail.domain.model

/**
 * What a mail says, both parts as they were imported. Either can be missing; which one is shown
 * is the ui's call.
 */
data class EmailBody(
    val text: String?,
    val html: String?,
)
