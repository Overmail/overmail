package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.emailIsNotArchived
import es.jvbabi.overmail.server.database.models.emailIsNotSpam
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.queryParameter
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.v1.core.Op

/**
 * Which mails a listing is about.
 *
 * Not a question of archived or not: [UNARCHIVED] is the mailbox as it stands, [ALL] is everything
 * that ever arrived. Spam is in neither -- that is a folder of its own, not a scope of this one.
 */
enum class MailScope(val wire: String) {
    UNARCHIVED("unarchived"),
    ALL("all"),
}

/** What `?scope=` asks for, or 400. The mailbox as it stands unless something else is named. */
internal fun ApplicationCall.mailScope(): MailScope {
    val requested = queryParameter("scope") ?: return MailScope.UNARCHIVED
    return MailScope.entries.firstOrNull { it.wire == requested }
        ?: invalidRequest("scope", "is not one of unarchived, all", requested)
}

/**
 * The predicate behind a scope.
 *
 * One place for the ids and for the stretches they fall into -- two that drifted apart would be
 * headers counting mails the rows do not show.
 */
internal fun MailScope.filter(): Op<Boolean> = when (this) {
    MailScope.UNARCHIVED -> emailIsNotArchived()
    MailScope.ALL -> emailIsNotSpam()
}
