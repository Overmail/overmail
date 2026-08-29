package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.data.ChangeNotifiers
import es.jvbabi.overmail.server.data.reloads
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.toImapAccount
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.uuid.Uuid

class ImapAccountRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: ChangeNotifiers,
): ImapAccountRepository {

    override fun getForUser(user: User): Flow<List<ImapAccount>> {
        return changes.imapAccounts.changesOfOwner(user.id)
            .reloads()
            .conflate()
            .map {
                database.query {
                    ImapAccounts
                        .selectAll()
                        .where(ImapAccounts.user eq user.id)
                        .map { it.toImapAccount(user) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun getById(id: Uuid): Flow<ImapAccount?> {
        return changes.imapAccounts.changesOfRow(id)
            .reloads()
            .conflate()
            .map {
                database.query {
                    (ImapAccounts innerJoin Users)
                        .selectAll()
                        .where(ImapAccounts.id eq id)
                        .map { it.toImapAccount(it.toUser()) }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }

    override fun getAll(): Flow<List<ImapAccount>> {
        return changes.imapAccounts.changes()
            .reloads()
            .conflate()
            .map {
                database.query {
                    (ImapAccounts innerJoin Users)
                        .selectAll()
                        .map { it.toImapAccount(it.toUser()) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }
}
