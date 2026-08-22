package es.jvbabi.overmail.server.ai.steps

import ai.koog.agents.core.tools.annotations.LLMDescription
import es.jvbabi.overmail.server.ai.MailAnalysisStep
import es.jvbabi.overmail.server.ai.ModelTier
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** One suggested tag, and where in the mail it comes from. */
@Serializable
data class MailTag(
    @property:LLMDescription("The tag itself: the thing the mail is filed under, as it would read on a folder. A short German noun, except for names, which keep their own spelling, and identifiers, which are the bare number or code without any word in front of it.")
    val tag: String,

    @property:LLMDescription("One short sentence in German -- also for mails in other languages -- saying why the tag fits and where it was read off. A sentence of your own, not a quotation from the mail.")
    val reason: String,
)

/** Tags to file a mail under. */
@Serializable
data class MailTags(
    val tags: List<MailTag> = emptyList(),
)

/**
 * Suggests what a mail should be filed under, for a mailbox that will be searched by these tags
 * months later. The prompt is written around that: a tag is worth having when someone would type
 * it to find this mail again, which is a different question from what the mail talks about.
 */
val MailTagsStep = MailAnalysisStep(
    id = "mail-tags",
    serializer = serializer<MailTags>(),
    // Tagging weighs what a mail is about rather than reading a fact off it.
    tier = ModelTier.CAPABLE,
    instructions = """
        Tag this mail so that its owner finds it again months from now, when they remember the
        matter but not a single word of the text. Tags are what they will type into a search box,
        not the words that stood out in the mail.

        Ask it of every tag before you answer with it: would the owner search for this, and would
        it bring up this mail together with the others like it -- rather than half the mailbox, or
        this one mail alone?

        What to look for:
        - What kind of mail this is, of a kind that recurs: Rechnung, Vertrag, Bug-Report,
          Bewerbung, Einladung, Mahnung.
        - The matter it deals with, named the way the owner would name it later: Abiball,
          Ferienjob, Umzug, Steuererklärung.
        - The part of life or work it belongs to: Schule, Arbeit, Finanzen, Softwareentwicklung.
        - The organisation, project or product behind it.
        - Any number or code the mail carries for that matter.

        A mail without a subject, or with hardly any text, is filed all the same -- and that is
        the normal case for automated mail. What is left says enough: who wrote it and which
        organisation they write for, what the sender's domain is, what the signature and the
        footer carry, what the addresses show, and what kind of mail it plainly is -- a
        notification, a newsletter, a delivery note, a receipt, a calendar invitation. File it
        under that. One tag you can stand behind is worth far more here than none.

        This is the level asked for. A mail "[Bug 304729] CSS-Zoom not working on iFrames" from the
        WebKit bug tracker is filed under: WebKit, Bug-Report, Softwareentwicklung, 304729. Note
        what is not in there: not "iFrames" and not "CSS-Zoom" -- nobody searches for those to find
        this mail again, they describe this one message; not "Safari" or "macOS Beta", which the
        mail only mentions in passing; and the identifier is the bare number, not "Bug 304729".
        Those four are the tags of that one mail, not a vocabulary to reuse.

        Rules:
        - German nouns, singular, ordinary spelling -- also for a mail written in another language:
          "Steuern", not "Tax"; "Preiserhöhung", not "Price Update". Names of products, projects
          and organisations keep their own spelling.
        - An identifier is the bare number or code: "304729", not "Bug 304729"; "RE-2026-114", not
          "Rechnung RE-2026-114".
        - Nothing the mail merely names along the way, and nothing out of a quoted earlier message.
        - What a mail enumerates is its content, not a folder: countries, currencies, prices, dates,
          product names out of a list or a table. A bulk announcement is filed as the announcement
          it is, not by the entries it happens to list.
        - Nothing the mailbox already knows by itself: a date, a year, a month, the sender's
          address, who else was on the mail. Those are filters, not tags. A year keeps its place
          inside an identifier ("RE-2026-114"), but never stands as a tag of its own.
        - The owner's own name and their own organisation are never tags: they fit every mail in
          this mailbox and so tell none of them apart. On an outgoing mail the party worth filing
          under is the recipient, not the sender.
        - A mail the owner wrote is filed under the same matter as the mail it answers. Who wrote
          it changes nothing about what it is about.
        - Nothing so wide it would fit any mail ("Information", "Nachricht", "Anfrage"), nothing so
          narrow it fits this one alone -- unless it is an identifier.
        - One short reason per tag, saying where in the mail you read it off. Your own words.
        - Three to six tags for a mail with substance, fewer when there is less to file, and never
          none: every mail gets at least one tag. A missing subject, a short text or a mail that is
          hard to place is no reason to answer with nothing -- fall back to what the envelope
          shows, the sender's organisation and the kind of mail it is.
    """.trimIndent(),
)

/**
 * Trims what the prompt asks for but a small model does not reliably deliver: the label a mail
 * puts in front of its number ("Bug 304729", "Rechnung RE-2026-114"). Only a leading word from
 * [IDENTIFIER_LABELS] is removed, and only from a tag whose remainder contains a digit, so a
 * product that carries a number in its name stays intact.
 */
fun MailTags.normalised(): MailTags = MailTags(
    tags.map { tag -> tag.copy(tag = tag.tag.withoutIdentifierLabel()) }
)

private val IDENTIFIER_LABELS = setOf(
    "bug", "issue", "ticket", "case", "rechnung", "rechnungsnummer", "auftrag", "auftragsnummer",
    "bestellung", "bestellnummer", "vertrag", "vertragsnummer", "kundennummer", "belegnummer",
    "invoice", "order", "nr", "nr.", "nummer", "no", "no.", "#",
)

private fun String.withoutIdentifierLabel(): String {
    // A leading "#" is a label of its own, and one that carries no word to look up.
    val bare = trim().removePrefix("#").trim()

    val (label, rest) = bare.split(Regex("[\\s:#-]+"), limit = 2).let {
        if (it.size == 2) it[0] to it[1] else return bare
    }

    return if (label.lowercase() in IDENTIFIER_LABELS && rest.any { it.isDigit() }) rest.trim() else bare
}
