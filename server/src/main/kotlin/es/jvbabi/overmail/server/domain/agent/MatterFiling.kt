package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import kotlin.uuid.Uuid

/** What became of a mail that names a matter. */
sealed interface MatterFiled {
    /** The tag the identifier itself became, which every one of these outcomes gets. */
    val tag: String

    /** Why the mail carries it, in the reader's words. */
    val reason: String

    /**
     * Written down and tagged, and nothing more: this is the first mail anybody has seen about this
     * matter, so there is no thread yet.
     */
    data class Noted(override val tag: String, override val reason: String) : MatterFiled

    /** The second mail: the thread was opened now, around every mail that names the matter. */
    data class Opened(
        val thread: MailThread,
        /** How many mails went into it, this one included. Never fewer than two. */
        val mails: Int,
        override val tag: String,
        override val reason: String,
    ) : MatterFiled

    /** The third and every one after it: the thread was already there. */
    data class Joined(
        val thread: MailThread,
        override val tag: String,
        override val reason: String,
    ) : MatterFiled
}

/**
 * Files a mail under the matter its identifier names.
 *
 * Two things happen to such a mail, and only one of them happens straight away.
 *
 * The identifier becomes a tag, always. It is the sharpest label a mail can carry -- every mail
 * about that invoice writes the same string, nothing else does -- and as a tag it is a thing to
 * search for in the same place as every other label rather than a field somewhere else.
 *
 * The thread waits for the second mail. A thread is a matter several mails belong to, and the first
 * mail of a matter is not several: opening one there means a mailbox of threads of one, which is a
 * second listing of the same mails and reads as clutter to whoever opens it. So the first mail is
 * only written down -- see [MailIdentifierRepository], which is what makes it findable at all -- and
 * the mail that turns it into a matter is the one that opens the thread, with both of them in it.
 *
 * Which is also why the identifier is recorded per mail rather than only on the thread: without a
 * row for the first mail, the second one has nothing to recognise it by.
 */
class MatterFiling(
    private val owner: User,
    private val matters: MailIdentifierRepository,
    private val threads: ThreadRepository,
    private val tagging: TagRepository,
) {
    /**
     * Records [identifier] for the mail, tags it with it, and puts it in the matter's thread where
     * there is one to put it in.
     *
     * [matterName] is what a matter of this kind is called for a reader -- "Rechnung", "Bestellung"
     * -- and is used for the reason and for the thread's name. It is passed in rather than worked out
     * here: what kind of thing a number identifies is something the reading knows.
     */
    suspend fun file(mailId: Uuid, identifier: String, matterName: String): MatterFiled {
        val matter = identifier.trim()
        val reason = "Die Mail nennt $matterName $matter."

        // Written down first, so that the mail is findable by the next one even if everything after
        // this falls over.
        matters.record(mailId, matter)

        val label = tagging.findOrCreate(owner, matter, createdByAgent = true)
        tagging.attach(mailId, label, reason, createdByAgent = true)

        val existing = threads.findByIdentifier(owner, matter)
        if (existing != null) {
            threads.attach(mailId, existing, reason, createdByAgent = true)

            return MatterFiled.Joined(thread = existing, tag = label.name, reason = reason)
        }

        // Everything else that ever named it. Empty means this is the first, and a matter of one
        // mail is not a matter yet.
        val others = matters.mailsWith(owner, matter).filterNot { it == mailId }
        if (others.isEmpty()) return MatterFiled.Noted(tag = label.name, reason = reason)

        // Through the identifier rather than by creating it outright: two mails of the same matter
        // being read at the same moment must end up in one thread, not in two halves of one.
        val thread = threads.findOrCreateByIdentifier(
            user = owner,
            identifier = matter,
            title = "$matterName $matter",
            createdByAgent = true,
        )

        // The older ones first, so the thread reads in the order the matter happened.
        for (id in others + mailId) {
            threads.attach(id, thread, reason, createdByAgent = true)
        }

        return MatterFiled.Opened(
            thread = thread,
            mails = others.size + 1,
            tag = label.name,
            reason = reason,
        )
    }
}
