package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.ai.ProposedTag
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.TagRepository
import kotlin.uuid.Uuid

/**
 * Puts tags on a mail without spelling anything twice.
 *
 * The one thing it does beyond attaching: where the mailbox already carries the same word in another
 * spelling, that spelling wins. "Github" is filed as the "GitHub" the mailbox has, "Rechnungen" as
 * its "Rechnung". Nothing is decided about meaning here -- only about spelling, and only where the
 * two are so close that no reader would call them different labels, see [SAME_WORD]. Anything less
 * certain than that is a judgement about the mail, and there is a step with tools and a model for
 * it, see [es.jvbabi.overmail.server.ai.REVISION_STEP].
 *
 * Used for the tags that are filed without being weighed: the ones read off the sender, which are
 * names rather than opinions, and the proposals of a run whose revision step never got to them.
 */
class TagFiling(
    private val owner: User,
    private val tagging: TagRepository,
) {
    /**
     * Files [proposed] on the mail and answers with what actually went on it -- the names as the
     * mailbox spells them, which is not always what was proposed.
     *
     * One read of the mailbox's vocabulary for the whole call, not one per tag.
     */
    suspend fun file(mailId: Uuid, proposed: List<ProposedTag>): List<ProposedTag> {
        if (proposed.isEmpty()) return emptyList()

        val vocabulary = tagging.usageForUser(owner)

        return proposed.map { proposal ->
            val known = similarTo(proposal.name, vocabulary, limit = 1, threshold = SAME_WORD)
                .firstOrNull()
                ?.usage
                ?.tag
                ?.name

            val label = tagging.findOrCreate(owner, known ?: proposal.name, createdByAgent = true)
            tagging.attach(mailId, label, proposal.reason, createdByAgent = true)

            proposal.copy(name = label.name)
        }
    }
}

/**
 * Where two names stop being two spellings of one word and start being two words.
 *
 * High on purpose. At this closeness the difference is a plural, a case, an umlaut written out or a
 * letter dropped -- things nobody means as a distinction. A compound and its head ("Stromrechnung",
 * "Rechnung") sit below it, and rightly: they may well be different labels, and which one this mail
 * wants is something only reading it can say.
 */
private const val SAME_WORD = 0.9
