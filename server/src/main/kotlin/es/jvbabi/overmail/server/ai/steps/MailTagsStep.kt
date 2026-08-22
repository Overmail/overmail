package es.jvbabi.overmail.server.ai.steps

import ai.koog.agents.core.tools.annotations.LLMDescription
import es.jvbabi.overmail.server.ai.MailAnalysisStep
import es.jvbabi.overmail.server.ai.MailParticipant
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
    // A tag with nothing written next to it cannot be checked, neither here nor by the user
    // reading it later; and a mail without a single tag is unfiled, which the prompt rules out.
    validate = { answer ->
        when {
            answer.tags.isEmpty() -> "You suggested no tag at all. Every mail gets at least one, " +
                "from what the envelope shows if the text gives nothing."

            else -> answer.tags.firstOrNull { it.tag.isBlank() || it.reason.isBlank() }?.let {
                "The tag \"${it.tag}\" came without a reason. Every tag needs one short German " +
                    "sentence saying where in this mail you read it off."
            }
        }
    },
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
        the normal case for automated mail. What is left says enough: who the other party is and
        which organisation they write for, what their domain is, what the signature and the
        footer carry, what the addresses show, and what kind of mail it plainly is -- a
        notification, a newsletter, a delivery note, a receipt, a calendar invitation. File it
        under that -- but the footer means the company signature, the disclaimer, the imprint, not
        the "sent from my ..." line above them. One tag you can stand behind is worth far more here
        than none.

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
        - Never the device or the mail app the mail was typed on. "Gesendet von meinem iPhone",
          "Sent from my Samsung Mobile" and their like are the program's doing, not the writer's:
          neither that line nor the maker behind it is a tag, and it is no evidence for any other
          tag either.
        - What a mail enumerates is its content, not a folder: countries, currencies, prices, dates,
          product names out of a list or a table. A bulk announcement is filed as the announcement
          it is, not by the entries it happens to list.
        - Nothing the mailbox already knows by itself: a date, a year, a month, the sender's
          address, who else was on the mail. Those are filters, not tags. A year keeps its place
          inside an identifier ("RE-2026-114"), but never stands as a tag of its own.
        - The owner's own name and their own organisation are never tags: they fit every mail in
          this mailbox and so tell none of them apart. On an outgoing mail the party worth filing
          under is the recipient, not the sender.
        - The same goes for everything read off the owner's own address. Their domain says whose
          mailbox this is and nothing beyond that -- a private one as much as a company one. For an
          owner at julius@familie-babies.de neither "Familie" nor "Babies" is a tag, and for one at
          julian@schulverwalter.de "Schulverwalter" is none. Which part of life a mail belongs to
          follows from what the mail deals with, never from the address it was written from or sent
          to.
        - A mail the owner wrote is filed under the same matter as the mail it answers. Who wrote
          it changes nothing about what it is about.
        - Nothing so wide it would fit any mail ("Information", "Nachricht", "Anfrage"), nothing so
          narrow it fits this one alone -- unless it is an identifier.
        - One short reason per tag, saying where in the mail you read it off. Your own words.
        - Three to six tags for a mail with substance, fewer when there is less to file, and never
          none: every mail gets at least one tag. A missing subject, a short text or a mail that is
          hard to place is no reason to answer with nothing -- fall back to what the envelope
          shows, the other party's organisation and the kind of mail it is.
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

/**
 * Takes out the tags that say no more than whose mailbox this is: the owner's own name, and the
 * words their own address is made of. Such a tag sits on every mail in the mailbox and so files
 * none of them apart.
 *
 * The prompt says as much, and this is here because saying it is not enough: a private domain
 * reads like a part of life ("familie-babies.de" -> "Familie"), a company domain like the matter
 * at hand, and on a mail the owner wrote themselves their name stands right where a model looks
 * for the party to file under. It writes down what it found there rather than the mailbox it is
 * looking at.
 *
 * Only a tag that is one of those words as a whole goes. A word of the owner's that also stands
 * for something a mail is about keeps its place inside a longer tag, an identifier above all.
 */
fun List<MailTag>.withoutOwnerIdentity(owner: MailParticipant): List<MailTag> {
    val own = owner.ownWords()
    return filterNot { it.tag.trim().lowercase() in own }
}

/**
 * The words that name the mailbox rather than a mail in it: the owner's name and each part of it,
 * their address, its local part and every label of its domain. Anything shorter than three
 * characters is left out -- a tag that short is no tag, and a domain's country label ("de", "uk")
 * would only ever match by accident.
 */
private fun MailParticipant.ownWords(): Set<String> {
    val local = address.substringBefore('@')
    val domain = address.substringAfter('@', "")
    val name = this.name.orEmpty()

    val words = buildList {
        add(name)
        addAll(name.split(WORD_SEPARATORS))
        add(address)
        add(local)
        addAll(local.split(WORD_SEPARATORS))
        add(domain)
        // The domain without its country label: that is the word a model reads as a tag.
        add(domain.substringBeforeLast('.'))
        addAll(domain.split(WORD_SEPARATORS))
    }

    return words.map { it.trim().lowercase() }.filter { it.length >= MIN_OWN_WORD }.toSet()
}

/** What separates one word from the next in a name, an address or a domain. */
private val WORD_SEPARATORS = Regex("[\\s._+-]+")

/** Below this a word matches by accident rather than because it names the mailbox. */
private const val MIN_OWN_WORD = 3
