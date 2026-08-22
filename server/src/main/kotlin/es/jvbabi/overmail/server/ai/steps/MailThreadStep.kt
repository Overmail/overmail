package es.jvbabi.overmail.server.ai.steps

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.text.text
import es.jvbabi.overmail.server.ai.MailAnalysisStep
import es.jvbabi.overmail.server.ai.ModelTier
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.TaggedMail
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

/** Which of the neighbouring mails are the same matter as the one at hand. */
@Serializable
data class ThreadChoice(
    @property:LLMDescription("Numbers of the listed mails that are the same matter as this one. Empty when this mail stands alone.")
    val sameMatter: List<Int> = emptyList(),

    @property:LLMDescription("Short German title for the matter, needed when none of those mails is in a thread yet. Names the matter, not the mail.")
    val title: String? = null,

    @property:LLMDescription("A better title, when the thread those mails are already in no longer covers the matter. Null to keep it.")
    val betterTitle: String? = null,

    @property:LLMDescription("One short sentence in German saying why they belong together.")
    val reason: String? = null,
)

/** Where a mail ended up, and which of the neighbouring mails sit in the same thread. */
data class ThreadPlacement(
    val thread: MailThread,
    val memberIds: Set<Uuid>,
)

/**
 * Puts a mail with the mails it continues.
 *
 * The model is asked one question only -- which of the listed mails are the same matter -- and the
 * decision whether that means joining a thread or opening one is made from what those mails are
 * already in. Asking for both at once meant two numbered lists to point at, and an answer that
 * pointed into the wrong one was indistinguishable from no answer at all.
 */
val MailThreadStep = MailAnalysisStep(
    id = "mail-thread",
    serializer = serializer<ThreadChoice>(),
    tier = ModelTier.CAPABLE,
    maxOutputTokens = 400,
    instructions = """
        Say which of the listed mails are the same matter as the mail at hand.

        Two things make mails one matter, and both count:
        - The same case: system messages about one and the same thing, each written by a machine,
          none of them answering another. A bug tracker on one bug, a provider on one contract, a
          shop on one order, a ticket system on one ticket. Here an identifier decides it: when the
          same number or code appears on both sides, they are the same matter. That settles it --
          no further judgement needed, list the mail.
        - The same conversation: mails that answer each other. The subject carries a reply or
          forward prefix, the body quotes what came before, the same people write back and forth.

        Where neither an identifier nor a reply chain ties the mails together, they have to be
        plainly about the same single thing. Sharing a tag is not enough, and sharing a sender is
        not either: two invoices from one company are one matter only when they concern the same
        thing, two newsletters never are, and a mass announcement to all customers is nobody's case.
        In that situation an empty answer is the right one.

        Also give a title when you list any mail: short, German, naming the matter rather than
        repeating the subject line, no date. When the listed mails already sit in a thread, its
        title is shown -- keep it, and only propose a better one when it no longer covers what the
        matter has become.
    """.trimIndent(),
)

/**
 * The neighbourhood as the thread step reads it: one numbered list, each mail with the thread it
 * is already in. One list on purpose -- the answer refers back to these numbers, and a second
 * numbering would be a second thing to get wrong.
 */
fun threadMaterial(
    neighbours: List<TaggedMail>,
    threadsByMail: Map<Uuid, MailThread>,
): String = text {
    textWithNewLine("Mails around this one, newest first:")

    neighbours.forEachIndexed { index, mail ->
        textWithNewLine("[${index + 1}] ${mail.subject}")
        textWithNewLine("      from: ${mail.sender}")
        mail.excerpt?.let { textWithNewLine("      text: $it") }
        textWithNewLine("      tags: ${mail.tags.joinToString { it.tag.name }.ifEmpty { "-" }}")
        threadsByMail[mail.id]?.let { textWithNewLine("      thread: \"${it.title}\"") }
    }
}
