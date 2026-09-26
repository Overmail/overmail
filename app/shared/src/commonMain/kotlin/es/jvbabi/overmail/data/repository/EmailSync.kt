package es.jvbabi.overmail.data.repository

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.dao.ParticipantFromEmail
import es.jvbabi.overmail.data.database.entity.DbEmail
import es.jvbabi.overmail.data.database.entity.DbEmailLabels
import es.jvbabi.overmail.data.database.entity.DbEmailRecipients
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.data.network.followServerSentEvents
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.EmailRecipientType
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = Logger.withTag("EmailSync")

/** How many mails one `QUERY /api/emails/meta` may ask for, the server's `MAX_BULK_IDS`. */
private const val META_CHUNK = 500

/** How long the change stream stays open after the last view stopped collecting it. */
private const val CHANGES_STOP_TIMEOUT_MILLIS = 5_000L

private val streamJson = Json { ignoreUnknownKeys = true }

/**
 * Keeps the mails in the local database the server's: what a view is missing is loaded through
 * `QUERY /api/emails/meta`, and what changes afterwards arrives over `GET /api/emails/changes`.
 *
 * Only ever writes; [EmailsRepositoryImpl] reads the database as usual and sees the writes like
 * any other.
 */
class EmailSync(
    private val httpClient: HttpClient,
    private val overmailDatabase: OvermailDatabase,
    private val imapAccountsRepository: ImapAccountsRepository,
) {
    /** Outlives any one view, so the stream can be handed from one to the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Mutex()

    /** One change stream per account, guarded by [lock]. */
    private val changes = mutableMapOf<Uuid, SharedFlow<Nothing>>()

    /**
     * The change stream of [account], running for as long as somebody collects this -- and a
     * little longer, so a view that is replaced by the next does not drop the connection.
     * Never emits; collecting it is what keeps the database current.
     */
    suspend fun changes(account: OvermailAccount): SharedFlow<Nothing> = lock.withLock {
        changes.getOrPut(account.id) {
            flow<Nothing> { followChanges(account) }
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = CHANGES_STOP_TIMEOUT_MILLIS))
        }
    }

    /** Loads the mails among [ids] that are not in the local database yet. */
    suspend fun loadMissing(account: OvermailAccount, ids: Collection<Uuid>) {
        val missing = ids.distinct().chunked(META_CHUNK).flatMap { chunk ->
            chunk - overmailDatabase.emailsDao.existingIds(chunk).toSet()
        }
        missing.chunked(META_CHUNK).forEach { chunk ->
            fetchMeta(account, chunk)
                .onSuccess { store(account, it) }
                .onFailure { logger.w(it) { "Loading ${chunk.size} mails failed" } }
        }
    }

    /**
     * Applies the changes to [account]'s mail until the server refuses the stream. Mails the
     * database does not hold are skipped: what a view needs, it loads itself.
     */
    private suspend fun followChanges(account: OvermailAccount) {
        val url = URLBuilder(urlString = account.homeserver).apply {
            appendPathSegments("api", "emails", "changes")
        }.build()

        httpClient.followServerSentEvents(url, account.token, what = "The mail changes") { data ->
            when (val event = streamJson.decodeFromString<ApiChangeEvent>(data)) {
                // Everything missed while no stream was open is in what the database holds now.
                ApiChangeEvent.Ready -> reloadAll(account)

                is ApiChangeEvent.Emails -> {
                    val held = overmailDatabase.emailsDao.existingIds(event.emails.map { it.id }).toSet()
                    store(account, event.emails.filter { it.id in held })
                }

                is ApiChangeEvent.Removed -> overmailDatabase.emailsDao.deleteByIds(event.ids)

                is ApiChangeEvent.Senders -> overmailDatabase.emailsDao.updateParticipantsFromEmails(
                    event.senders.map { ParticipantFromEmail(it.id, it.address, it.avatarUrl, it.avatarPadding) }
                )
            }
        }
    }

    /**
     * Asks again for every mail the database holds of [account]. What the server leaves out is
     * gone. Throws when a request fails, which reconnects the stream and so tries again.
     */
    private suspend fun reloadAll(account: OvermailAccount) {
        val held = overmailDatabase.emailsDao.allIds(account.id)
        held.chunked(META_CHUNK).forEach { chunk ->
            val emails = fetchMeta(account, chunk).getOrThrow()
            store(account, emails)

            val gone = chunk - emails.map { it.id }.toSet()
            if (gone.isNotEmpty()) overmailDatabase.emailsDao.deleteByIds(gone)
        }
    }

    private suspend fun fetchMeta(account: OvermailAccount, ids: List<Uuid>): Result<List<ApiEmailMeta>> = safeRequest {
        val response = httpClient.request(URLBuilder(urlString = account.homeserver).apply {
            appendPathSegments("api", "emails", "meta")
        }.build()) {
            method = HttpMethod.Query
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(ApiIdsRequest(ids))
        }

        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()
        response.body<ApiEmailsMetaResponse>().emails
    }

    /**
     * Writes [emails] into the database in one transaction. A mail from a mailbox the database
     * does not know yet has the mailboxes loaded first -- the listing reads a mail together with
     * its mailbox -- and is skipped when that does not bring it either.
     */
    private suspend fun store(account: OvermailAccount, emails: List<ApiEmailMeta>) {
        if (emails.isEmpty()) return

        val mailboxes = emails.map { it.imapAccountId }.distinct()
        var known = overmailDatabase.imapAccountsDao.existingIds(mailboxes).toSet()
        if (known.size < mailboxes.size) {
            imapAccountsRepository.getAll(overmailAccount = account).first()
            known = overmailDatabase.imapAccountsDao.existingIds(mailboxes).toSet()
        }

        val storable = emails.filter { it.imapAccountId in known }
        if (storable.size < emails.size) {
            logger.w { "Skipping ${emails.size - storable.size} mails from mailboxes the server does not list" }
        }

        overmailDatabase.emailsDao.store(
            emails = storable.map { it.toDb(account) },
            participants = storable
                .flatMap { email -> listOf(email.sender) + email.to + email.cc + email.bcc }
                .distinctBy { it.id }
                .map { it.toDb(account) },
            labels = storable.flatMap { it.labels }.distinctBy { it.id }.map { it.toDb(account) },
            emailLabels = storable.flatMap { email -> email.labels.map { DbEmailLabels(email.id, it.id) } },
            recipients = storable.flatMap { email ->
                email.to.map { DbEmailRecipients(email.id, it.id, EmailRecipientType.Recipient) } +
                    email.cc.map { DbEmailRecipients(email.id, it.id, EmailRecipientType.Cc) } +
                    email.bcc.map { DbEmailRecipients(email.id, it.id, EmailRecipientType.Bcc) }
            },
        )
    }
}

private fun ApiEmailMeta.toDb(account: OvermailAccount) = DbEmail(
    id = id,
    overmailAccountId = account.id,
    imapAccountId = imapAccountId,
    sentAt = Instant.fromEpochSeconds(sent),
    sentFromParticipantId = sender.id,
    subject = subject,
    isRead = isRead,
    archivedState = when (archiveState) {
        "archive" -> ArchivedState.Archive
        "spam" -> ArchivedState.Spam
        else -> ArchivedState.Unarchive
    },
)

/** Kept only where the row is new: the participant search knows the count, a mail does not. */
private fun ApiEmailMeta.Participant.toDb(account: OvermailAccount) = DbParticipant(
    id = id,
    name = name,
    email = address,
    avatarUrl = avatarUrl,
    avatarPadding = avatarPadding,
    emailCount = 0,
    overmailAccountId = account.id,
)

/** Kept only where the row is new: the label search knows the count and the age, a mail does not. */
private fun ApiEmailMeta.Label.toDb(account: OvermailAccount) = DbLabels(
    id = id,
    name = name,
    description = description,
    color = parseHexColor(color),
    createdAt = Instant.fromEpochSeconds(0),
    createdByAgent = createdByAgent,
    emailCount = 0,
    overmailAccountId = account.id,
)

/** `QUERY /api/emails/meta`, `http/email/meta/emailsMeta.kt`. */
@Serializable
private data class ApiIdsRequest(@SerialName("ids") val ids: List<Uuid>)

@Serializable
private data class ApiEmailsMetaResponse(@SerialName("emails") val emails: List<ApiEmailMeta>)

/** The server's `EmailMeta`, what of it the database keeps. */
@Serializable
private data class ApiEmailMeta(
    @SerialName("id") val id: Uuid,
    @SerialName("imap_account_id") val imapAccountId: Uuid,
    @SerialName("subject") val subject: String,
    @SerialName("sent") val sent: Long,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("archive_state") val archiveState: String,
    @SerialName("sender") val sender: Participant,
    @SerialName("to") val to: List<Participant>,
    @SerialName("cc") val cc: List<Participant>,
    @SerialName("bcc") val bcc: List<Participant>,
    @SerialName("labels") val labels: List<Label>,
) {
    @Serializable
    data class Participant(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        @SerialName("avatar_padding") val avatarPadding: Double?,
    )

    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("description") val description: String?,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
    )
}

/** The events of `GET /api/emails/changes`, `http/email/changes/emailChanges.kt`. */
@Serializable
private sealed class ApiChangeEvent {
    @Serializable
    @SerialName("ready")
    data object Ready : ApiChangeEvent()

    @Serializable
    @SerialName("emails")
    data class Emails(@SerialName("emails") val emails: List<ApiEmailMeta>) : ApiChangeEvent()

    @Serializable
    @SerialName("removed")
    data class Removed(@SerialName("ids") val ids: List<Uuid>) : ApiChangeEvent()

    @Serializable
    @SerialName("senders")
    data class Senders(@SerialName("senders") val senders: List<Sender>) : ApiChangeEvent()

    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        @SerialName("avatar_padding") val avatarPadding: Double?,
    )
}
