package es.jvbabi.overmail.server.ai.steps

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.text.text
import es.jvbabi.overmail.server.ai.MailAnalysisStep
import es.jvbabi.overmail.server.ai.ModelTier
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.TaggedMail
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** What the review wants changed about one already filed mail. */
@Serializable
data class MailTagCorrection(
    @property:LLMDescription("The number the mail was listed under. Only numbers from that list.")
    val mail: Int,

    @property:LLMDescription("Tags to add to that mail. Empty when it is filed correctly.")
    val add: List<MailTag> = emptyList(),

    @property:LLMDescription("Names of tags to take off that mail. Empty when nothing should go.")
    val remove: List<String> = emptyList(),
)

/** The tagging as it should stand after looking at the neighbouring mails. */
@Serializable
data class MailTagReview(
    @property:LLMDescription("The complete set of tags for the mail at hand, revised. Repeat the ones that stay.")
    val tags: List<MailTag> = emptyList(),

    @property:LLMDescription("Changes to the mails listed as already filed. Empty when they are fine as they are.")
    val corrections: List<MailTagCorrection> = emptyList(),
)

/**
 * Second look at a tagging, against the mails that were filed the same way before it.
 *
 * A mail is tagged on its own, so the same matter can end up under "Rechnung" once and
 * "Rechnungen" the next time, and a name the mailbox has settled on can be missed. This step sees
 * the neighbourhood and can pull both sides together -- including the older mails, which is why it
 * may propose changes to them.
 */
val MailTagReviewStep = MailAnalysisStep(
    id = "mail-tag-review",
    serializer = serializer<MailTagReview>(),
    tier = ModelTier.CAPABLE,
    // A revised set for this mail plus corrections for up to ten others.
    maxOutputTokens = 1200,
    instructions = """
        You are looking at a mail, at the tags just suggested for it, and at the mails filed under
        those tags before it. Make the filing of this neighbourhood consistent.

        Answer with two things:
        - tags: the complete set of tags for the mail at hand as it should stand. Repeat every tag
          that stays; leave out the ones you drop; add what the neighbours show was missed.
        - corrections: for the listed mails, tags to add or to remove. Refer to a mail by the number
          it is listed under. Leave this out for every mail that is filed correctly.

        What to look for:
        - Mails marked as being in the same thread are one and the same matter. Their tags should
          agree: what one of them is filed under, the others belong under too, unless a mail
          plainly differs. This is the strongest reason to change anything here.
        - The same thing filed under two names ("Rechnung" and "Rechnungen", "Bug-Report" and
          "Bugreport"). Keep the name the neighbourhood already uses and drop the variant.
        - A tag the neighbours carry that this mail plainly belongs under too, and the other way
          round: a mail that was filed without a tag its siblings all have.
        - A tag that turns out to be a one-off after all, once you see what the others carry.

        Rules:
        - Changing an older mail is the exception. Propose it only when the mail is plainly filed
          wrong or inconsistently -- not to make it tidier, and never to impose a taste.
        - You see each of those mails with its sender and the opening of its text. That is enough
          to judge a tag, and not enough to judge what the mail says further down: remove a tag
          only when what you see plainly contradicts it.
        - Only tags marked as the agent's own may be removed. A tag marked as the user's stays, and
          you may not replace it with your own wording either.
        - New tags follow the same rules as before: German nouns, bare identifiers, filing labels
          rather than keywords.
        - Every added tag needs its short reason, as usual.
    """.trimIndent(),
)

/** The neighbourhood as the review reads it: the suggestion first, then what came before. */
fun tagReviewMaterial(
    suggested: List<MailTag>,
    neighbours: List<TaggedMail>,
    placement: ThreadPlacement?,
): String = text {
    textWithNewLine("Tags just suggested for the mail at hand:")
    suggested.forEach { textWithNewLine("- ${it.tag}: ${it.reason}") }
    textWithNewLine("")

    placement?.let {
        textWithNewLine("This mail was just put into the thread \"${it.thread.title}\".")
        textWithNewLine("")
    }

    textWithNewLine("Mails around it, newest first:")

    neighbours.forEachIndexed { index, mail ->
        val sameThread = if (placement != null && mail.id in placement.memberIds) " -- same thread" else ""
        textWithNewLine("[${index + 1}] ${mail.subject}$sameThread")
        textWithNewLine("      from: ${mail.sender}")
        mail.excerpt?.let { textWithNewLine("      text: $it") }
        textWithNewLine("      tags: ${mail.tags.joinToString { "${it.tag.name} (${it.owner()})" }.ifEmpty { "-" }}")
    }
}

/** Who put a tag on a mail. Only the agent's own may be taken off again. */
private fun EmailTag.owner(): String = if (createdByAgent) "agent" else "user"
