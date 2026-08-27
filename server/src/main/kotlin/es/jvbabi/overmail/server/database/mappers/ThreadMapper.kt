package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailThreads
import es.jvbabi.overmail.server.database.models.Threads
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadEntry
import es.jvbabi.overmail.server.domain.models.User
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toMailThread(user: User): MailThread = MailThread(
    id = this[Threads.id].value,
    user = user,
    title = this[Threads.title],
    identifier = this[Threads.identifier],
    createdAt = this[Threads.createdAt],
    createdByAgent = this[Threads.createdByAgent],
)

fun ResultRow.toMailThreadEntry(thread: MailThread): MailThreadEntry = MailThreadEntry(
    id = this[EmailThreads.id].value,
    thread = thread,
    reason = this[EmailThreads.reason],
    createdAt = this[EmailThreads.createdAt],
    createdByAgent = this[EmailThreads.createdByAgent],
)
