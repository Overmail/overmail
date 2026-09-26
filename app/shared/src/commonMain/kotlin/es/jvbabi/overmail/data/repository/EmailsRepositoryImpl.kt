package es.jvbabi.overmail.data.repository

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.common.email.grouping.smartDateBoundaries
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedEmail
import es.jvbabi.overmail.data.network.followServerSentEvents
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.NetworkErrorKind
import es.jvbabi.overmail.domain.model.NetworkException
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewGrouping
import es.jvbabi.overmail.domain.model.ViewGroupingKind
import es.jvbabi.overmail.domain.model.ViewSorting
import es.jvbabi.overmail.domain.model.ViewSortingKind
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.utils.takeFrom
import io.ktor.client.HttpClient
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val logger = Logger.withTag("EmailsRepository")

class EmailsRepositoryImpl(
    private val httpClient: HttpClient,
    private val overmailDatabase: OvermailDatabase,
    imapAccountsRepository: ImapAccountsRepository,
) : EmailsRepository {
    private val emailSync = EmailSync(httpClient, overmailDatabase, imapAccountsRepository)

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

                            val arranged = filtered
                                .map { it.toModel() }
                                .arrange(viewSettingsState.groupings, viewSettingsState.sorting)
                            send(arranged)
                        }
                    }
            }
        }

        // Keeps what the database holds current while the view is on screen.
        launch { emailSync.changes(user).collect() }

        launch {
            followViewIds(viewSettingsState, user).collectLatest { groups ->
                groups.filter { it.total > it.ids.size }.forEach { group ->
                    logger.w { "Group ${group.keys} holds ${group.total} mails, the server sent ${group.ids.size} of them" }
                }
                emailSync.loadMissing(user, groups.flatMap { it.ids })
            }
        }
    }

    /**
     * The ids of every mail in the view per outermost group, and again whenever they change:
     * the snapshots of `GET /api/emails/list/ids/stream`, `emailListIdsStream.kt` there. Per
     * outermost group, because the server cuts each group at 10000 ids.
     */
    private fun followViewIds(viewState: ViewState, user: OvermailAccount): Flow<List<RemoteGroupIds>> = channelFlow {
        val url = URLBuilder(urlString = user.homeserver).apply {
            appendPathSegments("api", "emails", "list", "ids", "stream")
            viewState.groupings.firstOrNull()?.let { parameters.append("by", it.kind.wire) }
            parameters.appendFilter(viewState.filter)
        }.build()

        httpClient.followServerSentEvents(url, user.token, what = "The ids of the view") { data ->
            when (val message = streamJson.decodeFromString<ApiIdsStreamEvent>(data)) {
                is ApiIdsStreamEvent.Snapshot -> send(
                    message.groups.map { RemoteGroupIds(keys = it.keys, total = it.total, ids = it.ids) }
                )
                is ApiIdsStreamEvent.Failed -> throw NetworkException(
                    kind = NetworkErrorKind.Other,
                    message = message.error.message,
                    apiErrorCode = message.error.code,
                )
            }
        }
    }
}

private val streamJson = Json { ignoreUnknownKeys = true }

/** The ids of one group as the server answered them; [keys] are the server's, outermost first. */
private data class RemoteGroupIds(
    val keys: List<String>,
    /** How many mails the group holds, more than [ids] when the answer was cut. */
    val total: Long,
    val ids: List<Uuid>,
)

/** The server's `MailGroupingKind.wire`. */
private val ViewGroupingKind.wire: String
    get() = when (this) {
        ViewGroupingKind.DateSmart -> "date_smart"
        ViewGroupingKind.Year -> "year"
        ViewGroupingKind.Month -> "month"
        ViewGroupingKind.Day -> "day"
        ViewGroupingKind.Sender -> "sender"
        ViewGroupingKind.ImapAccount -> "imap_account"
        ViewGroupingKind.Read -> "read"
        ViewGroupingKind.Archived -> "archived"
    }

/**
 * The filter as the list endpoints read it, see `listFilter.kt` there. What is null is left out;
 * an empty list is sent as an empty parameter, which the server reads as letting nothing through.
 */
private fun ParametersBuilder.appendFilter(filter: ViewFilter) {
    filter.readState?.let { append("read_state", it.toString()) }
    filter.archivedState?.let { states -> append("archived_state", states.joinToString(",") { it.name }) }
    filter.imapAccountIds?.let { ids -> append("imap_account_ids", ids.joinToString(",")) }
    filter.sentBy?.let { correspondents -> append("sent_by", correspondents.joinToString(",") { it.wire }) }
    filter.sentTo?.let { correspondents -> append("sent_to", correspondents.joinToString(",") { it.wire }) }
    filter.hasLabels?.let { ids -> append("has_labels", ids.joinToString(",")) }
}

private val Correspondent.wire: String
    get() = when (this) {
        is Correspondent.Contact -> id.toString()
        is Correspondent.Self -> "self"
    }

/** The events of `GET /api/emails/list/ids/stream`, `http/email/list/emailListIdsStream.kt`. */
@Serializable
private sealed class ApiIdsStreamEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(@SerialName("groups") val groups: List<Group>) : ApiIdsStreamEvent()

    @Serializable
    @SerialName("failed")
    data class Failed(@SerialName("error") val error: Error) : ApiIdsStreamEvent()

    @Serializable
    data class Group(
        @SerialName("keys") val keys: List<String>,
        @SerialName("total") val total: Long,
        @SerialName("ids") val ids: List<Uuid>,
    )

    @Serializable
    data class Error(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    )
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
        val allowedSenderIds = filter.sentBy.participantIds(usersParticipantIds)
        filtered.removeAll { email -> email.sender.participant.id !in allowedSenderIds }
    }
    if (filter.sentTo != null) {
        // Addressed to any of them, in any of the header fields -- To, Cc and Bcc alike.
        val allowedRecipientIds = filter.sentTo.participantIds(usersParticipantIds)
        filtered.removeAll { email -> email.recipients.none { it.recipient.participantId in allowedRecipientIds } }
    }
    if (filter.archivedState != null) filtered.removeAll { email -> email.dbEmail.archivedState !in filter.archivedState }
    if (filter.hasLabels != null) filtered.removeAll { email -> email.labels.none { it.label.id in filter.hasLabels } }
    if (filter.imapAccountIds != null) filtered.removeAll { email -> email.imapAccount.imapAccount.id !in filter.imapAccountIds }
    return filtered
}

private fun List<Correspondent>.participantIds(usersParticipantIds: Set<Uuid>): Set<Uuid> = flatMap { correspondent ->
    when (correspondent) {
        is Correspondent.Contact -> setOf(correspondent.id)
        is Correspondent.Self -> usersParticipantIds
    }
}.toSet()

/**
 * Cuts the mails by the first of [groupings] and every group again by the rest, with the mails
 * of the deepest level ordered by [sorting]. No groupings is the flat, sorted listing.
 */
private fun List<Email>.arrange(groupings: List<ViewGrouping>, sorting: ViewSorting): List<ViewResult> {
    val grouping = groupings.firstOrNull() ?: return sortedWith(sorting.comparator()).map { ViewResult.Item(it) }
    val remaining = groupings.drop(1)

    val groups = applyGrouping(grouping.kind) { emails -> emails.arrange(remaining, sorting) }
        .filter { it.items.isNotEmpty() }
        .sortedWith(grouping.kind.groupOrder())
    return if (grouping.reversed) groups.reversed() else groups
}

/** @param children what goes under a group, from the mails that fell into it */
private fun Collection<Email>.applyGrouping(
    groupingKind: ViewGroupingKind,
    children: (List<Email>) -> List<ViewResult>,
): List<ViewResult.Group> {
    val result = mutableListOf<ViewResult.Group>()
    val emails = this.toMutableList()
    when (groupingKind) {
        ViewGroupingKind.DateSmart -> {
            val boundaries = smartDateBoundaries()
            ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Today, children(emails.takeFrom { it.sentAt >= boundaries.today }))
                .let(result::add)

            ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Yesterday, children(emails.takeFrom { it.sentAt >= boundaries.yesterday }))
                .let(result::add)

            boundaries.week?.let { week -> ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Week, children(emails.takeFrom { it.sentAt >= week })) }
                ?.let(result::add)

            boundaries.month?.let { month -> ViewResult.Group.DateSmart(stretch = ViewResult.Group.DateSmart.Stretch.Month, children(emails.takeFrom { it.sentAt >= month })) }
                ?.let(result::add)

            emails.groupBy { email ->
                val sentDate = email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                sentDate.year to sentDate.month
            }.map { (yearAndMonth, emailsInSameMonth) ->
                val (year, month) = yearAndMonth
                ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.CalendarMonth(year, month), children(emailsInSameMonth))
            }.let(result::addAll)
        }

        ViewGroupingKind.Archived -> {
            emails.groupBy { email -> email.archivedState }.map { (archivedState, emailsWithSameArchivedState) ->
                ViewResult.Group.Archived(archivedState, children(emailsWithSameArchivedState))
            }.let(result::addAll)
        }
        ViewGroupingKind.Year -> {
            emails.groupBy { email ->
                email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault()).year
            }.map { (year, emailsInSameYear) ->
                ViewResult.Group.Year(year, children(emailsInSameYear))
            }.let(result::addAll)
        }
        ViewGroupingKind.Month -> {
            emails.groupBy { email ->
                val sentDate = email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault())
                sentDate.year to sentDate.month
            }.map { (yearAndMonth, emailsInSameMonth) ->
                ViewResult.Group.Month(year = yearAndMonth.first, month = yearAndMonth.second, children(emailsInSameMonth))
            }.let(result::addAll)
        }
        ViewGroupingKind.Day -> {
            emails.groupBy { email ->
                email.sentAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
            }.map { (date, emailsInSameDay) ->
                ViewResult.Group.Day(date, children(emailsInSameDay))
            }.let(result::addAll)
        }
        ViewGroupingKind.ImapAccount -> {
            emails.groupBy { email -> email.imapAccount.id }.values.map { emailsForSameImapAccount ->
                ViewResult.Group.ImapAccount(emailsForSameImapAccount.first().imapAccount, children(emailsForSameImapAccount))
            }.let(result::addAll)
        }
        ViewGroupingKind.Read -> {
            emails.groupBy { email -> email.isRead }.map { (isRead, emailsWithSameReadState) ->
                ViewResult.Group.Read(isRead, children(emailsWithSameReadState))
            }.let(result::addAll)
        }
        ViewGroupingKind.Sender -> {
            emails.groupBy { email -> email.sentBy.id }.map { (participantId, emailsFromSameSender) ->
                ViewResult.Group.Sender(participantId, children(emailsFromSameSender))
            }.let(result::addAll)
        }
    }

    return result
}

/**
 * Which of two groups of the same level comes first, before [ViewGrouping.reversed] is applied;
 * the web app's `compareGroups`. Dates run newest first, the read state unread first, the archive
 * state inbox first. Senders and accounts are ordered by how much mail they hold.
 */
private fun ViewGroupingKind.groupOrder(): Comparator<ViewResult.Group> = when (this) {
    ViewGroupingKind.DateSmart -> compareBy { group ->
        when (val stretch = (group as ViewResult.Group.DateSmart).stretch) {
            ViewResult.Group.DateSmart.Stretch.Today -> 0
            ViewResult.Group.DateSmart.Stretch.Yesterday -> 1
            ViewResult.Group.DateSmart.Stretch.Week -> 2
            ViewResult.Group.DateSmart.Stretch.Month -> 3
            // Behind the named stretches, newest first.
            is ViewResult.Group.DateSmart.Stretch.CalendarMonth -> Int.MAX_VALUE - (stretch.year * 12 + stretch.month.ordinal)
        }
    }
    ViewGroupingKind.Year -> compareByDescending { (it as ViewResult.Group.Year).year }
    ViewGroupingKind.Month -> compareByDescending<ViewResult.Group> { (it as ViewResult.Group.Month).year }
        .thenByDescending { (it as ViewResult.Group.Month).month }
    ViewGroupingKind.Day -> compareByDescending { (it as ViewResult.Group.Day).date }
    ViewGroupingKind.Read -> compareBy { (it as ViewResult.Group.Read).isRead }
    ViewGroupingKind.Archived -> compareBy { group ->
        when ((group as ViewResult.Group.Archived).state) {
            ArchivedState.Unarchive -> 0
            ArchivedState.Archive -> 1
            ArchivedState.Spam -> 2
        }
    }
    ViewGroupingKind.Sender -> compareByDescending<ViewResult.Group> { it.emailCount }
        .thenBy { (it as ViewResult.Group.Sender).participantId.toString() }
    ViewGroupingKind.ImapAccount -> compareByDescending<ViewResult.Group> { it.emailCount }
        .thenBy { (it as ViewResult.Group.ImapAccount).imapAccount.id.toString() }
}

private val ViewResult.emailCount: Int
    get() = when (this) {
        is ViewResult.Item -> 1
        is ViewResult.Group -> items.sumOf { it.emailCount }
    }

/**
 * The server's order for [ViewSorting]: dates newest first, senders and subjects alphabetical,
 * the id breaking ties in the same direction. [ViewSorting.reversed] turns all of it around.
 */
private fun ViewSorting.comparator(): Comparator<Email> {
    val natural = when (kind) {
        ViewSortingKind.Date -> compareByDescending<Email> { it.sentAt }.thenByDescending { it.id.toString() }
        ViewSortingKind.Sender -> compareBy<Email> { it.sentBy.email }.thenBy { it.id.toString() }
        ViewSortingKind.Subject -> compareBy<Email, String?>(nullsLast()) { it.subject }.thenBy { it.id.toString() }
    }
    return if (reversed) natural.reversed() else natural
}
