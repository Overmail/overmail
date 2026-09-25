package es.jvbabi.overmail.data.repository

import androidx.room.util.getLastInsertedRowId
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedEmail
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewGroupingKind
import es.jvbabi.overmail.domain.model.ViewSorting
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class EmailsRepositoryImpl(
    private val overmailDatabase: OvermailDatabase,
) : EmailsRepository {
    override fun getView(
        viewSettingsState: ViewState,
        instantLocalEmission: Boolean,
        user: OvermailAccount
    ): Flow<List<ViewResult>> = channelFlow {
        launch {
            overmailDatabase.imapAccountsDao.allForAccount(user.id).collectLatest { imapAccountsForUser ->
                val emailsForUser = (imapAccountsForUser.map { it.imapAccount.username } + user.email).toSet()
                overmailDatabase.participantsDao.getAllParticipantsWithEmails(emailsForUser.toList())
                    .map { it.map { participant -> participant.participant.id }.toSet() }
                    .collectLatest { participantIdsWhichAreCurrentUser ->
                        overmailDatabase.emailsDao.getAllEmailsForAccount(user.id).collectLatest { rawEmails ->
                            val filtered = rawEmails.applyFilter(
                                filter = viewSettingsState.filter,
                                usersParticipantIds = participantIdsWhichAreCurrentUser
                            )

                            val modelled = filtered.map { it.toModel() }
                            viewSettingsState.groupings.first().kind
                        }
                    }
            }
        }.join() // Job so we can later fetch data additionally while using the realtime local database as single source of truth
    }
}

/**
 * @param usersParticipantIds All ids of [es.jvbabi.overmail.data.database.entity.DbParticipant] that are the user in the current context
 */
private fun Collection<EmbeddedEmail>.applyFilter(
    filter: ViewFilter,
    usersParticipantIds: Set<Uuid>
): List<EmbeddedEmail> {
    val filtered = this.toMutableList()
    if (filter.readState != null) filtered.removeAll { email -> email.dbEmail.isRead != filter.readState }
    if (filter.sentBy != null) {
        val allowedSenderIds = filter.sentBy.flatMap { sender ->
            when (sender) {
                is Correspondent.Contact -> setOf(sender.id)
                is Correspondent.Self -> usersParticipantIds
            }
        }
        filtered.removeAll { email -> email.sender.participant.id !in allowedSenderIds }
    }
    if (filter.sentTo != null) TODO("EmbeddedEmails don't provide sender data yet")
    if (filter.archivedState != null) TODO("EmbeddedEmails don't provide archive state yet")
    if (filter.hasLabels != null) TODO("EmbeddedEmails don't provide labels yet")
    if (filter.imapAccountIds != null) filtered.removeAll { email -> email.imapAccount.imapAccount.id !in filter.imapAccountIds }
    return filtered
}

private fun Collection<Email>.applyGrouping(groupingKind: ViewGroupingKind): List<ViewResult.Group> {
    when (groupingKind) {
        ViewGroupingKind.DateSmart -> {
            
        }
    }
}