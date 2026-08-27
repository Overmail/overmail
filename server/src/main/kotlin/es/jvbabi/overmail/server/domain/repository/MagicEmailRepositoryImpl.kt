package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toMagicEmail
import es.jvbabi.overmail.server.database.models.MagicEmails
import es.jvbabi.overmail.server.domain.models.MagicEmail
import es.jvbabi.overmail.server.domain.models.MagicEmailKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class MagicEmailRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : MagicEmailRepository {

    override fun getForEmail(emailId: Uuid): Flow<List<MagicEmail>> {
        return changes.changesOf(MagicEmails)
            .conflate()
            .map {
                database.query {
                    MagicEmails
                        .selectAll()
                        .where(MagicEmails.email eq emailId)
                        // The id only breaks ties: the two rows of one mail are written in the same
                        // moment, and the order still has to be one order.
                        .orderBy(
                            MagicEmails.createdAt to SortOrder.ASC,
                            MagicEmails.id to SortOrder.ASC,
                        )
                        .map { row -> row.toMagicEmail() }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun record(
        emailId: Uuid,
        provider: String,
        kind: MagicEmailKind,
        payload: String,
        validUntil: Instant?,
    ): MagicEmail? {
        return database.query {
            // Read and written in the one transaction, so two readings of the same mail racing each
            // other cannot both decide the row is missing. The unique index on (email, kind) is
            // what makes that a lost insert rather than a duplicate row either way; this is what
            // keeps it from being an exception on the ordinary rerun.
            val existing = MagicEmails
                .selectAll()
                .where((MagicEmails.email eq emailId) and (MagicEmails.kind eq kind))
                .firstRowOrNull()

            if (existing != null) return@query null

            val createdAt = Clock.System.now()
            val id = MagicEmails.insertAndGetId {
                it[MagicEmails.email] = emailId
                it[MagicEmails.provider] = provider
                it[MagicEmails.kind] = kind
                it[MagicEmails.payload] = payload
                it[MagicEmails.validUntil] = validUntil
                it[MagicEmails.createdAt] = createdAt
            }.value

            MagicEmail(
                id = id,
                emailId = emailId,
                provider = provider,
                kind = kind,
                payload = payload,
                validUntil = validUntil,
                usedAt = null,
                createdAt = createdAt,
            )
        }
    }
}
