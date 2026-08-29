package es.jvbabi.overmail.server.data

import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Carries the changes a repository writes over to the flows the same repository hands out.
 *
 * One notifier per table. A notifier subscribes to the notifiers of the tables it points at with
 * a foreign key ([parents]), so a change travels down that chain on its own: renaming a user
 * reaches the mails of their imap accounts without the mail repository knowing that a user table
 * exists. That is what [changes] and [changesOfOwner] collect; [eventsOfOwner] stays with the
 * events this table produced itself.
 *
 * Every row in this schema belongs to exactly one user, so [User.Id] is what a subscriber can
 * narrow down to without knowing the changed row -- one mailbox does not reload because another
 * user received mail. The owner is kept as the change travels down the chain.
 *
 * Everything happens in this process: a row written by anything but this server goes unnoticed,
 * so every write has to go through a repository that reports it here once its transaction
 * committed.
 */
class EntityNotifier<ID>(private vararg val parents: EntityNotifier<*>) {

    // DROP_OLDEST so a write is never held up by a slow subscriber. Dropping an event is
    // harmless: subscribers re-read the current state instead of folding the events into it,
    // and the newest event always makes it into the buffer.
    private val events = MutableSharedFlow<OwnedEvent<ID>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Reports a committed insert. Not `suspend`: reporting never waits for a subscriber. */
    fun created(owner: User.Id, id: ID) = notify(owner, Event.Created(id))

    /** Reports a committed update. */
    fun modified(owner: User.Id, id: ID) = notify(owner, Event.Modified(id))

    /** Reports a committed delete. */
    fun deleted(owner: User.Id, id: ID) = notify(owner, Event.Deleted(id))

    private fun notify(owner: User.Id, event: Event<ID>) {
        events.tryEmit(OwnedEvent(owner, event))
    }

    /** What happened to [owner]'s rows of this table, for subscribers that act on single rows. */
    fun eventsOfOwner(owner: User.Id): Flow<Event<ID>> = events.filter { it.owner == owner }.map { it.event }

    /** What happened to one row of this table. */
    fun eventsOfRow(id: ID): Flow<Event<ID>> = events.filter { it.event.id == id }.map { it.event }

    /** Signals that a query over [owner]'s rows -- joined parents included -- may return something else now. */
    fun changesOfOwner(owner: User.Id): Flow<Unit> =
        chain().map { it.eventsOfOwner(owner).map { } }.merge()

    /**
     * The same for a query about one row. Parent changes are not narrowed down here: the owner of
     * [id] is exactly what such a query is about to look up, so it cannot filter by it yet.
     */
    fun changesOfRow(id: ID): Flow<Unit> =
        (listOf(eventsOfRow(id).map { }) + ancestors().map { it.events.map { } }).merge()

    /** The same for a query that spans users. */
    fun changes(): Flow<Unit> = chain().map { it.events.map { } }.merge()

    /** This notifier and, transitively, the ones it is chained to. */
    private fun chain(): Set<EntityNotifier<*>> = buildSet {
        add(this@EntityNotifier)
        addAll(ancestors())
    }

    // A table can reach the same parent over two foreign keys -- a mail points at its account and
    // at its sender, both of which point at a user. Walking into a set keeps that one change one
    // reload instead of one per path.
    private fun ancestors(): Set<EntityNotifier<*>> = parents.flatMapTo(mutableSetOf()) { it.chain() }

    private data class OwnedEvent<ID>(val owner: User.Id, val event: Event<ID>)
}

/**
 * Turns a subscription into what a reading repository function is built on: emits once on
 * subscription, so a collector starts with the current state, and then on every change.
 */
fun Flow<*>.reloads(): Flow<Unit> = map { }.onStart { emit(Unit) }
