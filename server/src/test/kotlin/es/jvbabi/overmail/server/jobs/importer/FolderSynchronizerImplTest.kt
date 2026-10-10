package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** How a folder is read: only what is new, and never past a mail that could not be stored. */
class FolderSynchronizerImplTest {

    private val database = testDatabase("folder-sync")

    /** UIDs the pipeline was handed, in order. */
    private val imported = mutableListOf<Long>()

    /** UIDs the pipeline fails on. */
    private val failing = mutableSetOf<Long>()

    private val synchronizer = FolderSynchronizerImpl(database) { mail, _ ->
        val uid = mail.uid.await()
        if (uid in failing) throw IOException("connection lost on $uid")
        imported += uid
        Result.AlreadyExists
    }

    @Test
    fun `the first pass reads the whole folder, the next one only what arrived since`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1), mail(2), mail(3)))

        synchronizer.synchronize(folder, context)
        folder.mails += listOf(mail(4), mail(5))
        synchronizer.synchronize(folder, context)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), imported)
        assertEquals(listOf(1L, 4L), folder.fetchedFrom)
    }

    @Test
    fun `a folder with nothing new is not fetched`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1)))

        synchronizer.synchronize(folder, context)
        synchronizer.synchronize(folder, context)

        assertEquals(listOf(1L), folder.fetchedFrom)
    }

    @Test
    fun `how far a folder was read survives a restart`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1), mail(2)))

        synchronizer.synchronize(folder, context)
        folder.mails += mail(3)
        FolderSynchronizerImpl(database) { mail, _ -> imported += mail.uid.await(); Result.AlreadyExists }
            .synchronize(folder, context)

        assertEquals(listOf(1L, 2L, 3L), imported)
    }

    @Test
    fun `folders and accounts are read independently`() = runBlocking {
        val first = ImportContext(database.addAccount(), folderSync())
        val second = ImportContext(database.addAccount(), folderSync())
        val inbox = FakeFolder("INBOX", mails = listOf(mail(1), mail(2)))
        val archive = FakeFolder("Archive", mails = listOf(mail(1)))

        synchronizer.synchronize(inbox, first)
        synchronizer.synchronize(archive, first)
        synchronizer.synchronize(inbox, second)

        assertEquals(listOf(1L, 2L, 1L, 1L, 2L), imported)
    }

    @Test
    fun `new uids after a changed uidvalidity start the folder over`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1), mail(2)))

        synchronizer.synchronize(folder, context)
        folder.uidValidity = 2
        synchronizer.synchronize(folder, context)

        assertEquals(listOf(1L, 2L, 1L, 2L), imported)
    }

    @Test
    fun `a pass that fails stops there and the next one goes on from that mail`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1), mail(2), mail(3)))
        failing += 2

        assertFailsWith<IOException> { synchronizer.synchronize(folder, context) }
        assertEquals(listOf(1L), imported)

        failing.clear()
        synchronizer.synchronize(folder, context)

        assertEquals(listOf(1L, 2L, 3L), imported)
        assertEquals(listOf(1L, 2L), folder.fetchedFrom)
    }

    @Test
    fun `a mail that fails every time is left behind after three passes`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1), mail(2), mail(3)))
        failing += 2

        assertFailsWith<IOException> { synchronizer.synchronize(folder, context) }
        assertFailsWith<IOException> { synchronizer.synchronize(folder, context) }
        synchronizer.synchronize(folder, context)

        assertEquals(listOf(1L, 3L), imported)

        // And it stays behind: the next pass has nothing to fetch.
        synchronizer.synchronize(folder, context)
        assertTrue(folder.fetchedFrom.size == 3, "${folder.fetchedFrom}")
    }
}
