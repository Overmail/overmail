package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.OutgoingMail

interface OutgoingMailRepository {

    /**
     * Hands [mail] to the configured SMTP server and returns once the server accepted it.
     * Delivery beyond that point is not something we learn about here.
     *
     * Throws if the server rejects the mail or cannot be reached.
     */
    suspend fun send(mail: OutgoingMail)
}
