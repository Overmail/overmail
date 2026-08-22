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
    // The reason is what makes a tag checkable afterwards, and this step is where the weak ones
    // come from: a tag copied off a sibling is easy to write down and hard to justify.
    validate = { review ->
        (review.tags + review.corrections.flatMap { it.add })
            .firstOrNull { it.tag.isBlank() || it.reason.isBlank() }
            ?.let {
                "The tag \"${it.tag}\" came without a reason. Every tag you answer with needs one " +
                    "short German sentence naming what in that mail it is read off."
            }
    },
    instructions = """
        You are looking at a mail, at the tags just suggested for it, and at the mails filed under
        those tags before it. Make the filing of this neighbourhood consistent.

        Answer with two things:
        - tags: the complete set of tags for the mail at hand as it should stand. Repeat every tag
          that stays; leave out the ones you drop; add what the neighbours show was missed.
        - corrections: for the listed mails, tags to add or to remove. Refer to a mail by the number
          it is listed under. Leave this out for every mail that is filed correctly.

        The mail at hand may already be filed, by a user or by a run that was cut short before it
        got through. What it carries is listed with who put it there, and this is where that gets
        tidied up: an agent tag you leave out of `tags` comes off the mail. Repeat it to keep it. A
        tag marked as the user's stays whatever you answer, so repeat those as well.

        What to look for:
        - Mails marked as being in the same thread are one and the same matter, so their tags
          should agree: a tag one of them carries is worth holding against this mail. Holding
          against is the whole of it -- the thread says where to look, it does not settle what
          comes of it. The tag goes on only where this mail itself shows the same thing, and the
          reason says what shows it.
        - The same thing filed under two names ("Rechnung" and "Rechnungen", "Bug-Report" and
          "Bugreport"). Keep the name the neighbourhood already uses and drop the variant.
        - A tag the neighbours carry that this mail plainly belongs under too, and the other way
          round: a mail that was filed without a tag its siblings all have.
        - A tag that turns out to be a one-off after all, once you see what the others carry.
        - A mail the owner wrote themselves is marked as such in the list. It is filed like the mail
          it answers -- same matter, same tags -- and never under the owner's own name, nor under
          anything read off their own address: their domain says whose mailbox this is, not what a
          mail in it is about.

        Rules:
        - Sitting in the same thread is no reason for a tag. "This mail is part of the thread X"
          says nothing about the mail: it repeats a decision another step made, and repeating it
          is how a whole thread ends up filed under something none of its mails is about. Go back
          to the mail every time -- its subject, its sender, what its text says -- and put the tag
          on only when you find it there. What you find is what the reason names.
        - The title of a thread is not a tag. It names one matter and one only, which is what a
          title is for and precisely what a tag must not be. An identifier out of it may well be a
          tag; the sentence around it is not.
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
        - The mail at hand keeps at least one tag. This step tidies a filing up, it does not empty
          it: when what was suggested turns out not to fit, answer with what does fit instead --
          the sender's organisation, what the mail is plainly about, the kind of mail it is --
          rather than leaving the mail unfiled. The same goes for a correction: do not strip an
          older mail down to nothing.
    """.trimIndent(),
)

/** The neighbourhood as the review reads it: the suggestion first, then what came before. */
fun tagReviewMaterial(
    suggested: List<MailTag>,
    alreadyFiled: List<EmailTag>,
    neighbours: List<TaggedMail>,
    placement: ThreadPlacement?,
): String = text {
    textWithNewLine("Tags just suggested for the mail at hand:")
    suggested.forEach { textWithNewLine("- ${it.tag}: ${it.reason}") }
    textWithNewLine("")

    if (alreadyFiled.isNotEmpty()) {
        textWithNewLine("The mail at hand is already filed under:")
        alreadyFiled.forEach { textWithNewLine("- ${it.tag.name} (${it.owner()})") }
        textWithNewLine("")
    }

    placement?.let {
        textWithNewLine("This mail was just put into the thread \"${it.thread.title}\".")
        // Said where the title is, not thirty lines up in the prompt: this is the moment the
        // title is in front of the model and looks like something to file under.
        textWithNewLine("That title names the matter for the thread list. It is not a tag, and")
        textWithNewLine("belonging to the thread is not by itself a reason for one.")
        textWithNewLine("")
    }

    textWithNewLine("Mails around it, newest first:")

    neighbours.forEachIndexed { index, mail ->
        val sameThread = if (placement != null && mail.id in placement.memberIds) " -- same thread" else ""
        textWithNewLine("[${index + 1}] ${mail.subjectLine()}$sameThread")
        textWithNewLine("      from: ${mail.from()}")
        mail.excerpt?.let { textWithNewLine("      text: $it") }
        textWithNewLine("      tags: ${mail.tags.joinToString { "${it.tag.name} (${it.owner()})" }.ifEmpty { "-" }}")
    }
}

/**
 * Takes out the tag the review reaches for when the thread is fresh in view: the title of that
 * thread, put on as a tag of its own. It names one matter and one only, so it files nothing --
 * every mail it would ever be found on is already in the thread it came from.
 *
 * The prompt says as much, and this is here because saying it is not enough: the title sits in
 * the material right next to the tags, and a model that has just been told these mails are one
 * matter writes it down as the thing they have in common. Only the title as a whole goes; a tag
 * that merely contains a word of it, an identifier above all, is left alone.
 */
fun List<MailTag>.withoutThreadTitle(placement: ThreadPlacement?): List<MailTag> {
    val title = placement?.thread?.title?.trim() ?: return this
    return filterNot { it.tag.trim().equals(title, ignoreCase = true) }
}

/** Who put a tag on a mail. Only the agent's own may be taken off again. */
private fun EmailTag.owner(): String = if (createdByAgent) "agent" else "user"
