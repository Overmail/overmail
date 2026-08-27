package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What kind of thing a [TopicAnalysis.threadId] identifies.
 *
 * A closed list rather than a free string, because the point of it is that two mails carrying the
 * same sort of number are described the same way. A number whose kind the mail does not make clear
 * is [OTHER] -- an identifier worth keeping is not worth guessing a label for.
 */
@Serializable
enum class ThreadKind {
    /**
     * No identifier at all, which is what most mail carries.
     *
     * A member of its own rather than a null, and not for tidiness. A nullable enum comes out of
     * the serializer as `"type": ["string", "null"]` next to an `"enum"` of the names -- and under
     * a schema validator `enum` applies whatever the type, so `null` satisfies the type and
     * violates the enum. A backend enforcing its own structured output strictly would reject the
     * one answer that is given most often. This shape cannot be got wrong.
     */
    @SerialName("none")
    NONE,

    @SerialName("invoice")
    INVOICE,

    @SerialName("order")
    ORDER,

    @SerialName("booking")
    BOOKING,

    /** A parcel's tracking number, or whatever the carrier calls it. */
    @SerialName("shipment")
    SHIPMENT,

    /** A support ticket, a case, a service request. */
    @SerialName("ticket")
    TICKET,

    /** A payment, a transfer, a charge. */
    @SerialName("transaction")
    TRANSACTION,

    /** A bug, an issue, a pull or merge request. */
    @SerialName("issue")
    ISSUE,

    /** A conversation or thread a platform numbers itself. */
    @SerialName("conversation")
    CONVERSATION,

    @SerialName("other")
    OTHER,
}

/**
 * One label the mail is filed under, and what in the mail says so.
 *
 * The label never comes alone. A tag is a claim about a mail -- and one the reader is going to
 * browse their mailbox by -- so it is asked for with the words it was read off and a sentence
 * saying what they make it: "Newsletter", off "Du erhältst diese Mail, weil du den Newsletter
 * abonniert hast", because the mail says itself that it is a subscription. A tag whose evidence is
 * nowhere in the mail is thrown away, see [TOPIC_STEP], and that check is only possible because the
 * words are asked for rather than the label alone.
 *
 * [quote] before [tag] on purpose: the fields are filled in the order they stand, so the model
 * looks for the words first and labels what it found, rather than picking a label and then hunting
 * for something to hang it on.
 */
@Serializable
data class TopicTag(
    /**
     * The words of the mail the tag was read off, copied: a phrase from the subject, the body or
     * the sender. Short -- the line it stands in, not the paragraph around it.
     */
    @SerialName("quote") val quote: String,

    /** The label itself, in German: "Rechnung", "Newsletter", "Bewerbung Musterfirma". */
    @SerialName("tag") val tag: String,

    /** What those words make the mail, in one short German sentence. */
    @SerialName("reason") val reason: String,
)

/**
 * What a mail is about, and what ties it to the other mail about the same matter.
 *
 * Two answers rather than one, and deliberately at two different levels of precision. [tags] group
 * mail by the sort of thing it is, which is what a reader wants a mailbox sorted by; [threadId]
 * picks out one matter exactly, which is what a reader wants when they are looking for the rest of
 * *this* one. Most mail yields the first and not the second.
 *
 * Not the same thing as [es.jvbabi.overmail.server.domain.models.Email.thread]: that is the thread
 * the mail headers form, replies to replies of one message. This is the matter itself, which mail
 * can share without ever being a reply -- an order confirmation, a shipping notice and an invoice
 * are three separate mails from three separate senders about one order.
 */
@Serializable
data class TopicAnalysis(
    /**
     * The labels the mail is filed under, the most general first, each with what it was read off.
     *
     * Empty for a mail that fits no label, which is a correct answer -- a person writing to the
     * owner about nothing in particular is such a mail.
     */
    @SerialName("tags") val tags: List<TopicTag> = emptyList(),

    /**
     * The identifier this mail carries for the matter it belongs to, as the mail spells it:
     * `RE-2024-00123`, `#INC0043221`, `1Z999AA10123456784`.
     *
     * Null for the great majority of mail, which carries no such thing. What is here is copied out
     * of the mail rather than described, so it can be matched against the next mail that carries it.
     */
    @SerialName("thread_id") val threadId: String? = null,

    /**
     * What kind of identifier [threadId] is, and [ThreadKind.NONE] exactly when there is none.
     */
    @SerialName("thread_kind") val threadKind: ThreadKind = ThreadKind.NONE,
)

/**
 * The step that fills it in.
 *
 * The capable model rather than the fast one: reading a number off a mail is extraction, but
 * deciding what sort of thing a mail is is a judgement call, and it is the one the reader sees most
 * of -- a mailbox grouped wrong is worse than one not grouped at all.
 *
 * The step is not shown what the mailbox already files mail under. It proposes from the mail alone,
 * which is what makes the proposals worth looking at: a model handed a list picks off the list, and
 * then nobody learns what it would have called this mail. The cost of that is a mailbox that can
 * end up with "Rechnung" next to "Beleg" -- which is a reconciliation to do somewhere, not a reason
 * to put words in its mouth here.
 *
 * A private mailbox is what the prompt assumes throughout, because that is what this is: the labels
 * are the ones somebody would use for their own post -- Studium, Wohnung, Versicherung -- and not
 * the ones a company files correspondence under.
 */
val TOPIC_STEP = MailAnalysisStep(
    id = "topic",
    instructions = """
        Say what sort of thing this mail is about, as tags to file it under, and whether it carries
        an identifier that ties it to the other mails about the same matter.

        The tags are proposals. A later step holds them against what the mailbox already uses and
        against the mail that came before this one, and it is that step which files them -- so
        propose the word that fits this mail best and do not worry about the mailbox: naming the
        obvious word is exactly what makes it possible to notice that the mailbox already has one.

        This is somebody's private mailbox -- their own post, not a company's. The tags are the
        words they would use for their own affairs, and they are there for one purpose: that months
        later the reader finds this mail by asking for a category rather than by scrolling. Write
        them in German whatever language the mail is in, capitalised as German nouns are, singular:
        "Rechnung", not "rechnungen".

        Give each tag on two levels, the general one first:

        1. What sort of mail it is, in the plainest word there is: "Benachrichtigung", "System",
           "Konto", "Newsletter", "Werbung", "Rechnung", "Bestellung", "Lieferung", "Termin",
           "Vertrag", "Zahlung", "Bestätigung", "Mahnung", "Einladung". This is the label that has
           to fit hundreds of other mails too -- take the ordinary word, not the precise one:
           "Rechnung", not "Zahlungsaufforderung".
        2. What it is about in the reader's life, where the mail makes that clear: "Studium",
           "Bewerbung", "Wohnung", "Umzug", "Steuer", "Versicherung", "Reise", "Schule", "Auto",
           "Gesundheit", "Verein", "Job", "Bank", "Handy", "Strom". Where the matter is one the
           reader will have more mail about, this tag may name it: "Bewerbung Musterfirma",
           "Studium Uni Leipzig", "Umzug Berlin". Only where the next mail about that same matter
           would carry the same words -- an ongoing matter, not this one event.

        Both levels together, at most $MAX_TAGS tags in all, and two is the usual answer. Always
        give the general one where the mail allows it at all -- "Studium", "Schule", "Wohnung",
        "Rechnung", "Newsletter" are the words a reader really browses by, and a mailbox of nothing
        but specific labels has no categories in it. What matters is that each tag is one a further
        mail would get as well: a label nothing else will ever carry files this mail on its own and
        helps nobody find anything.

        Not tags, however well they describe this mail:
        - the sender's name, the company behind it, or the platform it came through: "GitHub",
          "Sparkasse", "Amazon". Those are read off the mail by another step and are already tags of
          their own by the time you see it -- naming one here only spells it a second way. A company
          name may stand *inside* a matter tag, where it says which matter: "Bewerbung Musterfirma"
          is a tag, "Musterfirma" alone is one somebody else already made.
        - the subject line, or what this one mail happens to say.
        - a number, a date, an amount, a price, a place on its own.
        - two words for the same thing. Three near-synonyms are one tag written three ways.
        - a sentence. One to $MAX_TAG_WORDS words, no punctuation.

        Where nothing fits, `tags` is empty. That is a correct answer, and a mail that is simply a
        person writing to the owner is often exactly that.

        Every tag is given with its evidence, and the evidence comes first:

        - `quote` is the words of the mail the tag was read off, copied exactly as they stand there
          -- a phrase from the subject, from the body, or the sender's own line. Short: the words
          that make the point, not the paragraph around them. It has to stand in the mail; a tag
          whose quote is not in the mail is thrown away, and inventing one loses the tag.
        - `tag` is the label those words earn.
        - `reason` is one short German sentence saying what the quoted words make this mail:
          "Nennt eine offene Rechnung mit Betrag und Frist.", "Sagt selbst, dass es ein Abo ist.",
          "Bezieht sich auf die laufende Bewerbung bei Musterfirma." Say what the mail is, not what
          you did: no "Ich habe erkannt, dass ...".

        `thread_id` is the identifier this mail carries for the matter it is part of: the string the
        next mail about the same matter will carry too. Copy it exactly as the mail writes it,
        without the word in front of it -- "Rechnungsnummer: RE-2024-00123" gives `RE-2024-00123`.
        It has to stand in the mail; never assemble one, never complete one, never tidy one up.

        What counts: an invoice number, an order number, a booking reference, a shipment tracking
        number, a ticket or case number, a transaction id, a bug or issue id, the number a platform
        gives a conversation.

        What does not, however much it looks like one:
        - anything identifying the reader rather than the matter: a customer number, a member
          number, an account number, a tax id, an IBAN, a licence key.
        - anything identifying this one mail rather than the matter: a message id, a mail's own
          reference number, a "no-reply" address.
        - anything out of a link: an unsubscribe key, a login token, a session id, a tracking
          parameter, the random-looking tail of a URL.
        - a date, a time, an amount, a postcode, a phone number, a version number, an article or
          product number, a seat or room number.

        Take the longest form the mail actually writes, not the number inside it. Never strip a
        prefix, a marker or a leading `#` off it: `#412` where the mail writes `#412`, `INC0043221`
        where it writes "Ticket INC0043221". What is left after stripping is a number that matches
        everything, and an identifier that matches everything identifies nothing. Equally, never
        add a part the mail does not write next to it -- what goes here has to stand in the mail
        exactly as you write it, or it can never be matched against the next mail again.

        One identifier and not several. Where the mail carries more than one, take the one the mail
        is about: an invoice mail is about the invoice even where it names the order too.

        `thread_kind` says which kind it is. Where the mail carries no such identifier -- and most
        mail does not -- `thread_id` is null and `thread_kind` is "none". That is the ordinary
        answer, not a failure.
    """.trimIndent(),
    serializer = TopicAnalysis.serializer(),
    tier = ModelTier.CAPABLE,
    // Room for four tags with their evidence and a sentence each, and the identifier after them.
    maxOutputTokens = 800,
    validate = { analysis, context ->
        val names = analysis.tags.map { it.tag }
        val wordy = analysis.tags.firstOrNull { it.tag.words() > MAX_TAG_WORDS }
        val unquoted = analysis.tags.firstOrNull { !context.quotes(it.quote.trim()) }
        val wordyQuote = analysis.tags.firstOrNull { it.quote.length > MAX_QUOTE_LENGTH }
        val wordyReason = analysis.tags.firstOrNull { it.reason.length > MAX_REASON_LENGTH }

        // What the schema cannot say: how many, how long, that two entries must differ, that one
        // field is only filled with another, and that a string claimed to be quoted was quoted.
        when {
            names.any { it.isBlank() } ->
                "`tags` came back with an entry whose `tag` is empty. Leave the entry out instead."
            names.size > MAX_TAGS ->
                "`tags` came back with ${names.size} entries. Name at most $MAX_TAGS, and only " +
                    "labels a further mail would be filed under too."
            names.distinctBy { it.lowercase() }.size != names.size ->
                "`tags` came back with the same tag twice. Name each one once."
            wordy != null ->
                "`tags` has \"${wordy.tag}\", which is a description rather than a label. A tag is " +
                    "one to $MAX_TAG_WORDS words."
            analysis.tags.any { it.quote.isBlank() } ->
                "`tags` has an entry with an empty `quote`. Every tag is given with the words of " +
                    "the mail it was read off, or it is left out."
            analysis.tags.any { it.reason.isBlank() } ->
                "`tags` has an entry with an empty `reason`. Say in one German sentence what the " +
                    "quoted words make this mail."
            wordyQuote != null ->
                "the `quote` of \"${wordyQuote.tag}\" is ${wordyQuote.quote.length} characters " +
                    "long. Quote the words that make the point, not the paragraph around them."
            wordyReason != null ->
                "the `reason` of \"${wordyReason.tag}\" is a paragraph. One short German sentence."
            // The guard against a label hung on nothing, and one of the two reasons `validate` is
            // handed the mail: a tag is a claim about this mail, and a claim whose words are not in
            // it was made about some other mail.
            unquoted != null ->
                "the `quote` of \"${unquoted.tag}\" is \"${unquoted.quote}\", which does not stand " +
                    "in this mail. Copy the words the mail really writes, or leave the tag out."
            analysis.threadId?.isBlank() == true ->
                "`thread_id` came back empty. Use null instead."
            analysis.threadId != null && analysis.threadKind == ThreadKind.NONE ->
                "`thread_id` is filled but `thread_kind` is \"none\". Say which kind of " +
                    "identifier it is, or `other` where the mail does not make it clear."
            analysis.threadKind != ThreadKind.NONE && analysis.threadId == null ->
                "`thread_kind` says \"${analysis.threadKind}\" but `thread_id` is empty. A mail " +
                    "carrying no identifier has a null `thread_id` and `thread_kind` \"none\"."
            analysis.threadId != null && analysis.threadId.length < MIN_THREAD_ID ->
                "`thread_id` is \"${analysis.threadId}\", which is too short to identify " +
                    "anything. Use null unless the mail carries a real identifier."
            // The other one: an identifier is only worth keeping if it can be matched later, and
            // one the mail never wrote will match nothing ever.
            analysis.threadId != null && !context.writes(analysis.threadId) ->
                "`thread_id` is \"${analysis.threadId}\", which does not stand anywhere in this " +
                    "mail. Copy one the mail writes, or use null."
            else -> null
        }
    },
)

/**
 * The most tags one mail is worth: the sort of mail it is, what it is about, and a shade of either.
 * Past this the model has stopped labelling and started listing.
 */
private const val MAX_TAGS = 4

/** Where a label stops being one. Three words is "Bewerbung Musterfirma GmbH"; four is a phrase. */
private const val MAX_TAG_WORDS = 3

/** Where a quote stops being one and becomes the mail again. About a line and a half. */
private const val MAX_QUOTE_LENGTH = 200

/** Where a reason stops being a sentence. */
private const val MAX_REASON_LENGTH = 200

/** Shorter than this identifies nothing: "12" is a number the mail happened to contain. */
private const val MIN_THREAD_ID = 3

private val WHITESPACE = Regex("""\s+""")

private fun String.words(): Int = trim().split(WHITESPACE).count { it.isNotEmpty() }

/** The same string with every run of whitespace as one space, for comparing prose to prose. */
private fun String.flattened(): String = replace(WHITESPACE, " ").trim()

/**
 * Whether the mail really writes [text] -- the subject, the body or the sender's own line.
 *
 * Whitespace is flattened on both sides, unlike the identifier check below: a quote is prose, and
 * the mail it is quoted from has line breaks wherever its sender's mail program put them. A model
 * that quoted across one of those has still quoted the mail. Case is ignored for the same reason
 * it is there: it is the words that are being checked, not the typography.
 *
 * The sender counts as the mail, because it is part of what a step is shown and part of what a tag
 * is fairly read off: "noreply@" is where "Benachrichtigung" comes from.
 */
private fun MailContext.quotes(text: String): Boolean {
    val wanted = text.flattened()

    return wanted.isNotEmpty() && listOf(subject, body, sender.toString())
        .any { it.flattened().contains(wanted, ignoreCase = true) }
}

/**
 * Whether the mail writes [text] exactly, ignoring case only.
 *
 * Whitespace is not flattened here, unlike for a quote: an identifier with a space put in it is no
 * longer the string the next mail carries, and matching it against the next mail is the only thing
 * it is for.
 */
private fun MailContext.writes(text: String): Boolean =
    subject.contains(text, ignoreCase = true) || body.contains(text, ignoreCase = true)
