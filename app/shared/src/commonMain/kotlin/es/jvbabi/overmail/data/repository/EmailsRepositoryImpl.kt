package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.common.email.grouping.smartDateBoundaries
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedEmail
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewGrouping
import es.jvbabi.overmail.domain.model.ViewGroupingKind
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.components.group.GroupingSettings
import es.jvbabi.overmail.utils.takeFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
                            val baseGroups = modelled.applyGrouping(viewSettingsState.groupings.first().kind)
                            if (viewSettingsState.groupings.size > 1) {
                                // TODO: Recursive appliance of remaining groupings to group children, then on recursive exit, sort them on recursive ascent by grouping settings
                            }

                            // TODO: Apply sorting on base (not in grouping settings)
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
    val emails = this.map { email -> ViewResult.Item(email) }.toMutableList()
    return emails.applyGrouping(groupingKind)
}

private fun Collection<ViewResult.Item>.applyGrouping(groupingKind: ViewGroupingKind): List<ViewResult.Group> {
    val result = mutableListOf<ViewResult.Group>()
    val emails = this.toMutableList()
    when (groupingKind) {
        ViewGroupingKind.DateSmart -> {
            val boundaries = smartDateBoundaries()
            ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Today, emails.takeFrom { it.email.sentAt >= boundaries.today })
                .let(result::add)

            ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Yesterday, emails.takeFrom { it.email.sentAt >= boundaries.yesterday })
                .let(result::add)

            boundaries.week?.let { ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Week, emails.takeFrom { it.email.sentAt >= boundaries.week!! }) }
                ?.let(result::add)

            boundaries.month?.let { ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Month, emails.takeFrom { it.email.sentAt >= boundaries.month!! }) }
                ?.let(result::add)

            emails.groupBy { email ->
                val sentDate = email.email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                "${sentDate.year}-${sentDate.month}"
            }.values.map { emailsInSameMonth ->
                val sentDate = emailsInSameMonth.first().email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.CalendarMonth(sentDate.year, sentDate.month), emailsInSameMonth)
            }.let(result::addAll)
        }

        ViewGroupingKind.Archived -> TODO("Archive grouping is not yet supported")
        ViewGroupingKind.Year -> {
            emails.groupBy { email ->
                val sentDate = email.email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                sentDate.year
            }.toList().map { (year, emailsInSameYear) ->
                ViewResult.Group.Year(year, emailsInSameYear)
            }.let(result::addAll)
        }
        ViewGroupingKind.Month -> {
            emails.groupBy { email ->
                val sentDate = email.email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                "${sentDate.year}-${sentDate.month}"
            }.values.map { emailsInSameMonth ->
                val sentDate = emailsInSameMonth.first().email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                ViewResult.Group.Month(year = sentDate.year, month = sentDate.month, emailsInSameMonth)
            }.let(result::addAll)
        }
        ViewGroupingKind.Day -> {
            emails.groupBy { email ->
                val sentDate = email.email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                "${sentDate.year}-${sentDate.month}-${sentDate.day}"
            }.values.map { emailsInSameDay ->
                val sentDate = emailsInSameDay.first().email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                ViewResult.Group.Day(sentDate.date, emailsInSameDay)
            }.let(result::addAll)
        }
        ViewGroupingKind.ImapAccount -> {
            emails.groupBy { email -> email.email.imapAccount.id }.values.map { emailsForSameImapAccount ->
                ViewResult.Group.ImapAccount(emailsForSameImapAccount.first().email.imapAccount, emailsForSameImapAccount)
            }.let(result::addAll)
        }
        ViewGroupingKind.Read -> {
            emails.groupBy { email -> email.email.isRead }.values.map { emailsWithSameReadState ->
                ViewResult.Group.Read(emailsWithSameReadState.first().email.isRead, emailsWithSameReadState)
            }.let(result::addAll)
        }
        ViewGroupingKind.Sender -> TODO("Sender data is yet missing")
    }

    return result
}

private fun Collection<ViewResult.Group>.applyRecursiveGrouping(remainingGroupingSettings: List<ViewGrouping>): List<ViewResult.Group> {
    this.map { group ->
        (if (remainingGroupingSettings.size == 1) group.items.map { it as ViewResult.Item } else listOf(group).applyRecursiveGrouping(remainingGroupingSettings.drop(1))).applyGrouping(remainingGroupingSettings.first().kind)
    }
}