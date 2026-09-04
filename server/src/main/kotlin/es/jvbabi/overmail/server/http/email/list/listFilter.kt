package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.emailIsNotArchived
import es.jvbabi.overmail.server.database.models.emailIsNotSpam
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.v1.core.Op

/**
 * Whether the listing holds archived mails as well: `?archived=true`.
 *
 * Off unless asked for. Archiving a mail is putting it away, and a listing that shows it anyway
 * is a listing where archiving did nothing.
 */
internal fun ApplicationCall.listIncludesArchived(): Boolean =
    request.queryParameters["archived"]?.toBooleanStrictOrNull() ?: false

/**
 * What a listing holds. Spam is never in it; archived mails only on request.
 *
 * One place for both the ids and the stretches they fall into -- two predicates that drift apart
 * would be a table whose headers count mails its rows do not show.
 */
internal fun listFilter(includeArchived: Boolean): Op<Boolean> =
    if (includeArchived) emailIsNotSpam() else emailIsNotArchived()
