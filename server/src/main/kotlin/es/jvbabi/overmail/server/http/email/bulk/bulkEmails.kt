package es.jvbabi.overmail.server.http.email.bulk

import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the bulk routes under `/api/emails/bulk` have in common: a list of mails in the body, and
 * an answer saying how many of them this actually changed.
 *
 * A body rather than `?ids=`, because a selection here is a stretch of the mailbox and not a
 * handful of ids a cache is missing (see `requestedIds`): a month of mail does not fit in a url.
 *
 * Every one of them is idempotent and quiet about the mails it does not touch -- ones that are
 * already in the state asked for, ones that belong to somebody else, ones that are not there at
 * all. What a caller ticked is what it sends; what changed is what comes back.
 */
@Serializable
data class BulkEmailsRequest(
    @SerialName("ids") val ids: List<Uuid> = emptyList(),
)

@Serializable
data class BulkEmailsResponse(
    /** How many mails this moved or flipped. The rest were already where the caller wanted them. */
    @SerialName("changed") val changed: Int,
)

/**
 * How many mails one bulk request may carry.
 *
 * One transaction and one `IN (...)`, so this is a bound on both. A client with a longer selection
 * sends it in parts -- which is what the web app does, see EmailRepository.
 */
const val MAX_BULK_IDS = 500
