package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.domain.model.ShareDraft
import kotlin.uuid.Uuid

/**
 * The share links of a mail, `/api/emails/{emailId}/shares`. Not cached: the list is only ever
 * looked at in the share sheet, and what it shows has to be what a visitor gets.
 */
interface SharesRepository {
    /** Every link made for [emailId], expired ones included, newest first. */
    suspend fun list(emailId: Uuid, account: OvermailAccount): Result<List<Share>>

    suspend fun create(emailId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share>

    /** Changes the link [shareId]; it stays the same link, so whoever holds it keeps it. */
    suspend fun update(emailId: Uuid, shareId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share>

    /** Takes the link back for good. */
    suspend fun delete(emailId: Uuid, shareId: Uuid, account: OvermailAccount): Result<Unit>
}
