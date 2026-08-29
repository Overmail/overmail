package es.jvbabi.overmail.server.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The chain in [ChangeNotifiers]: who is told about a change, and who is not. */
class EntityNotifierTest {

    private val notifiers = ChangeNotifiers()
    private val alice = Uuid.random()
    private val bob = Uuid.random()

    @Test
    fun `a mail insert reaches a subscriber of that mailbox`() = runTest {
        val reloads = collect { notifiers.emails.changesOfOwner(alice) }

        notifiers.emails.created(alice, Uuid.random())

        assertEquals(1, reloads.size)
    }

    @Test
    fun `a mail of another user does not`() = runTest {
        val reloads = collect { notifiers.emails.changesOfOwner(alice) }

        notifiers.emails.created(bob, Uuid.random())

        assertEquals(0, reloads.size)
    }

    @Test
    fun `a change travels down the foreign keys, keeping its owner`() = runTest {
        val reloads = collect { notifiers.emailRecipients.changesOfOwner(alice) }

        // users <- imap_accounts <- emails <- email_recipients: three hops, and two paths from the
        // recipients to the user, which still has to be one reload.
        notifiers.users.modified(alice, alice)
        assertEquals(1, reloads.size)

        notifiers.users.modified(bob, bob)
        assertEquals(1, reloads.size)
    }

    @Test
    fun `a row subscription takes parent changes of any owner`() = runTest {
        val reloads = collect { notifiers.emails.changesOfRow(Uuid.random()) }

        // Who owns that row is what the query is about to look up, so it cannot filter by it.
        notifiers.imapAccounts.modified(bob, Uuid.random())

        assertEquals(1, reloads.size)
    }

    @Test
    fun `events stay with the table that produced them`() = runTest {
        val events = collect { notifiers.emails.eventsOfOwner(alice) }

        notifiers.users.modified(alice, alice)
        assertEquals(0, events.size)

        val id = Uuid.random()
        notifiers.emails.created(alice, id)
        assertEquals(listOf(Event.Created(id)), events)
    }

    /** Subscribes before the test makes its changes and collects into the returned list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> TestScope.collect(subscription: () -> Flow<T>): List<T> {
        val collected = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subscription().toList(collected) }
        return collected
    }
}
