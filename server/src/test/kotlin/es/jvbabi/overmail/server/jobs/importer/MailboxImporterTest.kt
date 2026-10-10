package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.IdleEvent
import es.jvbabi.overmail.kamel.MailClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.ImapClient

/** When a folder is looked at. Runs on virtual time: nothing here waits for real. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxImporterTest {

    private val timings = MailboxImporter.Timings(pollInterval = 5.minutes, idleRenewInterval = 10.minutes, retryDelay = 30.seconds)

    /** A poll so rare that whatever happens within an hour is the watch's doing. */
    private val watchOnly = timings.copy(pollInterval = 24.hours)

    /** The folders that were synchronized, in order. */
    private val passes = mutableListOf<String>()
    private val synchronizer = FolderSynchronizer { folder, _ -> passes += folder.fullName }

    private fun connection(vararg folders: ImapConnection.FolderSync) = ImapConnection(
        id = Uuid.random(),
        userId = Uuid.random(),
        host = "imap.example.com",
        port = 993,
        authentication = ImapClient.Auth.BasicAuth("owner", "secret"),
        folders = folders.toList(),
    )

    @Test
    fun `every folder is read right away and again with every poll`() = runTest {
        val client = FakeClient(FakeFolder("INBOX"), FakeFolder("Archive"))
        val importer = MailboxImporter(connection(folderSync("INBOX"), folderSync("Archive")), synchronizer, { client }, timings)

        backgroundScope.launch { importer.run() }
        runCurrent()
        assertEquals(setOf("INBOX", "Archive"), passes.toSet())
        assertEquals(2, passes.size)

        advanceTimeBy(5.minutes + 1.seconds)
        assertEquals(4, passes.size)
    }

    @Test
    fun `a pushed change is read without waiting for the poll`() = runTest {
        val inbox = FakeFolder("INBOX")
        val importer = MailboxImporter(connection(folderSync("INBOX", imapPush = true)), synchronizer, { FakeClient(inbox) }, timings)

        backgroundScope.launch { importer.run() }
        runCurrent()
        val before = passes.size

        inbox.idleEvents.emit(IdleEvent.NewMessage(1))
        runCurrent()

        assertEquals(before + 1, passes.size)
    }

    @Test
    fun `a watch is renewed on time, and the folder read with it`() = runTest {
        val inbox = FakeFolder("INBOX")
        val importer = MailboxImporter(connection(folderSync("INBOX", imapPush = true)), synchronizer, { FakeClient(inbox) }, watchOnly)

        backgroundScope.launch { importer.run() }
        runCurrent()
        val before = passes.size
        assertEquals(1, inbox.watches)

        advanceTimeBy(10.minutes + 1.seconds)

        assertEquals(2, inbox.watches)
        // Nothing reported what changed between the two, so the folder is looked at.
        assertEquals(before + 1, passes.size)
    }

    @Test
    fun `a watch that fails is taken up again, and the folder read with it`() = runTest {
        val inbox = FakeFolder("INBOX").apply { failingWatches = 2 }
        val importer = MailboxImporter(connection(folderSync("INBOX", imapPush = true)), synchronizer, { FakeClient(inbox) }, watchOnly)

        backgroundScope.launch { importer.run() }
        runCurrent()
        val before = passes.size
        assertEquals(1, inbox.watches)

        advanceTimeBy(30.seconds + 1.seconds)
        assertEquals(2, inbox.watches)
        advanceTimeBy(30.seconds)
        assertEquals(3, inbox.watches)
        assertEquals(before + 2, passes.size)

        // The third one holds, and reports as any other.
        inbox.idleEvents.emit(IdleEvent.NewMessage(1))
        runCurrent()
        assertEquals(before + 3, passes.size)
    }

    @Test
    fun `a folder without push ignores what the server reports`() = runTest {
        val inbox = FakeFolder("INBOX")
        val importer = MailboxImporter(connection(folderSync("INBOX")), synchronizer, { FakeClient(inbox) }, timings)

        backgroundScope.launch { importer.run() }
        runCurrent()
        inbox.idleEvents.emit(IdleEvent.NewMessage(1))
        runCurrent()

        assertEquals(1, passes.size)
    }

    @Test
    fun `a pass that fails does not end the importer`() = runTest {
        var failing = true
        val synchronizer = FolderSynchronizer { folder, _ ->
            if (failing) throw IOException("no answer")
            passes += folder.fullName
        }
        val importer = MailboxImporter(connection(folderSync("INBOX")), synchronizer, { FakeClient(FakeFolder("INBOX")) }, timings)

        backgroundScope.launch { importer.run() }
        runCurrent()
        assertEquals(emptyList(), passes)

        failing = false
        advanceTimeBy(5.minutes + 1.seconds)

        assertEquals(listOf("INBOX"), passes)
    }

    @Test
    fun `a mailbox that cannot be reached is tried again, with growing pauses`() = runTest {
        var attempts = 0
        val unreachable = object : MailClient {
            override suspend fun getFolders(onlyRoot: Boolean) = throw IOException("refused")
        }
        val importer = MailboxImporter(connection(folderSync("INBOX")), synchronizer, { attempts++; unreachable }, timings)

        backgroundScope.launch { importer.run() }
        runCurrent()
        assertEquals(1, attempts)

        // 30s, then 60s, then 120s.
        advanceTimeBy(31.seconds)
        assertEquals(2, attempts)
        advanceTimeBy(60.seconds)
        assertEquals(3, attempts)
        advanceTimeBy(60.seconds)
        assertEquals(3, attempts)
        advanceTimeBy(60.seconds)
        assertEquals(4, attempts)
    }

    @Test
    fun `a folder the mailbox does not have is left out, the others are read`() = runTest {
        val importer = MailboxImporter(
            connection(folderSync("INBOX"), folderSync("Gone")),
            synchronizer,
            { FakeClient(FakeFolder("INBOX")) },
            timings,
        )

        backgroundScope.launch { importer.run() }
        runCurrent()

        assertEquals(listOf("INBOX"), passes)
    }
}
