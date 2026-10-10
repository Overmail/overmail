package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.FetchRequest
import es.jvbabi.overmail.kamel.FolderState
import es.jvbabi.overmail.kamel.IdleEvent
import es.jvbabi.overmail.kamel.IdleFolder
import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.kamel.MailClient
import es.jvbabi.overmail.kamel.MailFolder
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/**
 * A database of its own per test; [name] only makes it findable in a log.
 *
 * `MODE=MySQL` because the inserter uses `INSERT IGNORE`, which H2 only takes in that mode.
 */
internal fun testDatabase(name: String) = OvermailDatabase(
    Database.connect("jdbc:h2:mem:$name-${Uuid.random()};DB_CLOSE_DELAY=-1;MODE=MySQL", driver = "org.h2.Driver")
)

/** Creates the schema, a user and an account, and returns the account as the importer sees it. */
internal suspend fun OvermailDatabase.addAccount(
    folders: List<ImapConnection.FolderSync> = listOf(folderSync()),
    paused: Boolean = false,
): ImapConnection {
    init()
    return query {
        val owner = User.new {
            username = "owner-${Uuid.random()}"
            email = "owner-${Uuid.random()}@example.com"
            firstname = "Julius"
            lastname = "Babies"
        }
        val account = ImapAccount.new {
            user = owner
            host = "imap.example.com"
            port = 993
            username = "owner"
            password = "secret"
            isPaused = paused
        }
        folders.forEach { sync ->
            ImapAccountFolderSync.new {
                imapAccount = account
                folder = sync.folder
                imapPush = sync.imapPush
                aiImport = sync.aiImportSettings
            }
        }
        ImapConnection(
            id = account.id.value,
            userId = owner.id.value,
            host = account.host,
            port = account.port,
            authentication = ImapClient.Auth.BasicAuth(account.username, account.password),
            folders = folders,
            isPaused = paused,
        )
    }
}

internal fun folderSync(
    folder: String = "INBOX",
    imapPush: Boolean = false,
    aiImportSettings: ImapAccountFolderSync.AiImportSettings = ImapAccountFolderSync.AiImportSettings.AllMessages,
) = ImapConnection.FolderSync(folder, imapPush, aiImportSettings, createdAt = Instant.parse("2026-06-01T12:00:00Z"))

/** A mail as a folder hands it out. The second of [uid] keeps the dedup key of every mail apart. */
internal fun mail(
    uid: Long,
    subject: String? = "Mail $uid",
    from: String? = "Ada Lovelace <ada@example.com>",
    flags: Set<KamelEmail.Flag> = emptySet(),
    date: String? = "Mon, 1 Jun 2026 12:00:%02d +0000".format(uid % 60),
    body: String = "Hello from mail $uid.",
): KamelEmail = KamelEmail.parse(
    buildString {
        from?.let { append("From: $it\r\n") }
        append("To: Julius <julius@example.com>, julius@example.com\r\n")
        append("Cc: Grace Hopper <grace@example.com>\r\n")
        subject?.let { append("Subject: $it\r\n") }
        date?.let { append("Date: $it\r\n") }
        append("Message-ID: <$uid@example.com>\r\n")
        append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
        append(body)
        append("\r\n")
    }.toByteArray(),
    uid = uid,
    flags = flags,
)

/** A folder that keeps its mails in memory and remembers what it was asked for. */
internal class FakeFolder(
    name: String = "INBOX",
    var mails: List<KamelEmail> = emptyList(),
    var uidValidity: Long = 1,
) : MailFolder {
    override val path = listOf(name)
    override val delimiter = "/"
    override val specialType: ImapFolder.SpecialType? = null

    /** The first UID of every fetch, in the order they were made. */
    val fetchedFrom = mutableListOf<Long>()

    val idleEvents = MutableSharedFlow<IdleEvent>(extraBufferCapacity = 16)

    /** How often an `IDLE` was issued. */
    var watches = 0

    /** How many of the next watches fail instead of watching. */
    var failingWatches = 0

    override suspend fun state() = FolderState(
        uidValidity = uidValidity,
        uidNext = (mails.maxOfOrNull { it.uidValue() } ?: 0) + 1,
        exists = mails.size,
    )

    override fun getMails(config: FetchRequest.() -> Unit): Flow<KamelEmail> = flow {
        val range = FetchRequest().apply(config).selection as FetchRequest.Selection.UidRange
        fetchedFrom += range.from
        mails.filter { it.uidValue() in range }.sortedBy { it.uidValue() }.forEach { emit(it) }
    }

    override fun getIdleFolder() = object : IdleFolder {
        override fun events(): Flow<IdleEvent> = flow {
            watches++
            if (failingWatches-- > 0) throw java.io.IOException("the watch lost its connection")
            emitAll(idleEvents)
        }
    }

    private fun KamelEmail.uidValue() = (uidValue as es.jvbabi.overmail.kamel.util.Optional.Set).value
}

internal class FakeClient(private vararg val folders: MailFolder) : MailClient {
    override suspend fun getFolders(onlyRoot: Boolean): List<MailFolder> = folders.toList()
}
