package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.toMailIdentifier
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.MailIdentifiers
import es.jvbabi.overmail.server.domain.models.MailIdentifier
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class MailIdentifierRepositoryImpl(
    private val database: OvermailDatabase,
) : MailIdentifierRepository {

    override suspend fun record(emailId: Uuid, identifier: String): MailIdentifier? {
        val matter = identifier.trim().take(IDENTIFIER_LENGTH)
        if (matter.isEmpty()) return null

        return database.query {
            // Read and written in the one transaction, so two readings of the same mail racing each
            // other cannot both decide the row is missing. The unique index on the mail is what
            // makes that a lost insert rather than two rows either way; this is what keeps it from
            // being an exception on the ordinary rerun.
            val existing = MailIdentifiers
                .selectAll()
                .where(MailIdentifiers.email eq emailId)
                .firstRowOrNull()

            if (existing != null) return@query null

            val createdAt = Clock.System.now()
            val id = MailIdentifiers.insertAndGetId {
                it[MailIdentifiers.email] = emailId
                it[MailIdentifiers.identifier] = matter
                it[MailIdentifiers.createdAt] = createdAt
            }.value

            MailIdentifier(
                id = id,
                emailId = emailId,
                identifier = matter,
                createdAt = createdAt,
            )
        }
    }

    override suspend fun identifierOf(emailId: Uuid): String? {
        return database.query {
            MailIdentifiers
                .selectAll()
                .where(MailIdentifiers.email eq emailId)
                .firstRowOrNull()
                ?.toMailIdentifier()
                ?.identifier
        }
    }

    override suspend fun mailsWith(
        user: User,
        identifier: String,
        before: Instant?,
        limit: Int,
    ): List<Uuid> {
        val matter = identifier.trim()
        if (matter.isEmpty()) return emptyList()

        return database.query {
            (MailIdentifiers innerJoin Emails innerJoin ImapAccounts)
                .select(MailIdentifiers.email, Emails.sent)
                .where {
                    val mine = (ImapAccounts.user eq user.id) and
                        (MailIdentifiers.identifier.lowerCase() eq matter.lowercase())

                    if (before == null) mine else mine and (Emails.sent less before)
                }
                // Oldest first: a matter reads in the order it happened, and the thread for it is
                // named after the mail that started it.
                .orderBy(Emails.sent to SortOrder.ASC, MailIdentifiers.email to SortOrder.ASC)
                .limit(limit)
                .toList()
                .map { it[MailIdentifiers.email].value }
        }
    }
}

/** What the column takes, so a string cut here is cut the same way it is stored. */
private const val IDENTIFIER_LENGTH = 128
