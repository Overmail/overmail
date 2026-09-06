package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** When the folder was added; "only new messages" is measured against exactly this. */
private val ADDED_AT = Instant.parse("2026-06-01T12:00:00Z")

private fun sync(scope: ImapAccountFolderSync.AiImportSettings) = ImapConnection.FolderSync(
    folder = "INBOX",
    imapPush = false,
    aiImport = scope,
    createdAt = ADDED_AT,
)

class FolderSyncAssistantScopeTest {

    @Test
    fun `all messages holds for anything, however old`() {
        val folder = sync(ImapAccountFolderSync.AiImportSettings.AllMessages)

        assertTrue(folder.wantsAssistant(Instant.parse("1999-01-01T00:00:00Z")))
        assertTrue(folder.wantsAssistant(ADDED_AT))
        assertTrue(folder.wantsAssistant(Instant.parse("2030-01-01T00:00:00Z")))
    }

    @Test
    fun `only new messages is measured from the moment the folder was added`() {
        val folder = sync(ImapAccountFolderSync.AiImportSettings.OnlyNewMessages)

        // Everything already in the folder is history, however recently it arrived.
        assertFalse(folder.wantsAssistant(ADDED_AT.minus(kotlin.time.Duration.parse("1s"))))
        // The boundary itself counts, so a mail arriving as the account is set up is not lost.
        assertTrue(folder.wantsAssistant(ADDED_AT))
        assertTrue(folder.wantsAssistant(ADDED_AT.plus(kotlin.time.Duration.parse("1s"))))
    }

    @Test
    fun `after a date takes that date, not the day the folder was added`() {
        val boundary = Instant.parse("2024-03-01T00:00:00Z")
        val folder = sync(ImapAccountFolderSync.AiImportSettings.AfterDate(boundary))

        assertFalse(folder.wantsAssistant(boundary.minus(kotlin.time.Duration.parse("1s"))))
        assertTrue(folder.wantsAssistant(boundary))
        // Well after the boundary but well before the folder was added: still in scope.
        assertTrue(folder.wantsAssistant(Instant.parse("2025-01-01T00:00:00Z")))
    }

    @Test
    fun `changing what the assistant reads restarts the importer`() {
        // The signature is what ImporterManager compares; a scope that did not show up in it would
        // leave the running importer on the old setting until the next restart.
        val connection = ImapConnection(
            id = kotlin.uuid.Uuid.random(),
            userId = kotlin.uuid.Uuid.random(),
            host = "imap.example.com",
            port = 993,
            username = "julius",
            password = "secret",
            folders = listOf(sync(ImapAccountFolderSync.AiImportSettings.OnlyNewMessages)),
        )
        val withOtherScope = connection.copy(
            folders = listOf(sync(ImapAccountFolderSync.AiImportSettings.AllMessages)),
        )
        val withPush = connection.copy(folders = connection.folders.map { it.copy(imapPush = true) })

        assertTrue(connection.signature != withOtherScope.signature)
        assertTrue(connection.signature != withPush.signature)
        assertTrue(connection.signature == connection.copy().signature)
    }
}
