package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

/** A mailbox the account imports mail from; what the accounts filter is on. */
data class ImapAccount(
    val id: Uuid,
    val host: String,
    val port: Int,
    /** The imap login, which for most providers is the address itself. */
    val username: String,
    /** Whether the importer for it is switched off; nothing imported is affected by that. */
    val isPaused: Boolean,
    val emailCount: Long,
    val overmailAccount: OvermailAccount,
)
