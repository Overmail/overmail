package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a mail exists to let its reader in somewhere, and what it takes to use it.
 *
 * Two flags rather than a kind, because a mail can carry both and the usual one does: the code is
 * written out for typing and a link beside it uses the same code. They are what the rows of
 * [es.jvbabi.overmail.server.database.models.MagicEmails] are made of, one per kind.
 *
 * No separate "is this one at all": a mail is one exactly when it carries a code or a link, so
 * there is nothing here that can contradict itself.
 *
 * Each flag comes with the thing itself: the code as the mail writes it, and the number of the link
 * among the mail's own, see [MailContext.links]. That is what a row is written with -- a row saying
 * a mail carries a code somewhere is a row that sends the reader back into the mailbox.
 */
@Serializable
data class MagicAnalysis(
    /** The mail writes out a code to type somewhere else. */
    @SerialName("carries_code") val carriesCode: Boolean = false,

    /** The mail carries a link that itself signs the reader in. */
    @SerialName("carries_link") val carriesLink: Boolean = false,

    /**
     * Who the code or the link lets the reader into, as the service is named: "GitHub", "Steam".
     * Null for the mail that is not one of these at all.
     */
    @SerialName("provider") val provider: String? = null,

    /**
     * The code itself, exactly as the mail writes it, spaces and all: "418 902", "A7K-2QX".
     *
     * Copied and never composed: it is checked against the mail, see [MAGIC_STEP], because a code
     * a mail never wrote will let nobody in anywhere. Null where the mail carries none.
     */
    @SerialName("code") val code: String? = null,

    /**
     * Which of the numbered links of the mail is the one that signs the reader in -- 1 for the
     * first, and so on. Null where the mail carries no such link.
     *
     * A number rather than the link, because the link is in the mail already: the numbered list is
     * handed over with it, see [MailContext.links], and a model asked to copy three hundred
     * characters of signed token back gets one of them wrong. The number is checkable and the link
     * it names is the mail's own, character for character.
     */
    @SerialName("link_number") val linkNumber: Int? = null,

    /**
     * How long it works for, in minutes, and only where the mail states a span of its own accord.
     *
     * A span rather than a time, because the steps are not shown when the mail was sent: a model
     * handed "expires at 14:32" and no clock can only invent one. Null covers both the mail that
     * names a clock time and the far more common one that says nothing -- see how the caller turns
     * this into a moment.
     */
    @SerialName("valid_for_minutes") val validForMinutes: Int? = null,
)

/**
 * The step that fills it in.
 *
 * The fast model: this is reading, not weighing. What a magic mail looks like is written on its
 * face -- it was sent because somebody was signing in a minute ago, and it says so.
 *
 * The hard part is not recognising one, it is the mail that looks like one and is not: a link to a
 * login page, a mail confirming an address, a number that identifies an order. Those are what most
 * of the prompt is about, because a false row here is worse than a missing one -- it puts a mail in
 * a list of ways into an account when it is no such thing.
 */
val MAGIC_STEP = MailAnalysisStep(
    id = "magic",
    instructions = """
        Say whether this mail exists to let its reader in somewhere -- a one-time code to type in,
        or a link that signs them in -- and if it does, who it lets them into and for how long.

        Such a mail has one job and shows it: it was sent because somebody was signing in,
        registering, confirming a device or resetting a password, and it carries the thing they now
        need. It is short, nobody wrote it by hand, and the code or the link is the point of it.

        `carries_code` is true when the mail writes out a code for the reader to type somewhere
        else: "Your code is 418 902", "Bestaetigungscode: 55213", a short run of digits or letters
        set on a line of its own. What makes it one is that the reader is meant to copy it.
        A number that identifies the reader or the matter is not one, however much it looks like
        one: a customer number, an order or invoice number, a ticket number, a booking reference, a
        contract number, an amount, a date, a phone number.

        `carries_link` is true when the mail carries a link that itself puts the reader into the
        account -- "Sign in to X", "Jetzt anmelden", "Confirm it's you", "Set a new password" --
        where following it is the whole point of the mail and nothing asks for a password on the
        way. A password reset link counts: it is how the reader gets back in.

        Not one, however similar it looks:
        - a link to a login page, or to the site in general: "Go to your dashboard", "Zum
          Kundenkonto". A page that asks for a password is a page, not a way in.
        - a link that confirms an address or a subscription and nothing else: "Confirm your email
          address", "Newsletter bestaetigen". It confirms something about the reader rather than
          letting them in.
        - an unsubscribe link, a preferences link, a tracking link.
        - a link to one thing rather than into an account: an invoice to download, a parcel to
          follow, a document to read.

        Both flags can be true, and often are: the same mail writes the code out and offers a link
        that carries it. Where neither is true, both are false and everything else is null. Most
        mail is not one of these, and saying so is the ordinary answer, not a failure.

        `provider` is who the code or the link lets the reader into, as the service goes by its
        name: "GitHub", "Notion", "Steam", "Sparkasse". Not the mail service that delivered it, not
        the sending address, not the name of the mail. Where the mail carries a code or a link but
        names nobody it lets you into, it cannot be used for anything -- both flags are false.

        `code` is the code itself, copied out of the mail exactly as it stands there -- "418 902"
        with the space in it, "A7K-2QX" with the dash. Copy it, never tidy it up, never make one up:
        an answer whose code does not stand in the mail is thrown away. Null wherever
        `carries_code` is false.

        `link_number` says which link signs the reader in. The mail's links are listed whole and
        numbered at the end of what you were given, and in the text itself each of them stands as
        its bare host -- the list and the text are the same links. Answer with the number of the one
        that puts the reader into the account: 1 for the first, 2 for the second. Do not write the
        link out, and do not name a number that is not in the list. Where the mail carries a code
        and a link, both `code` and `link_number` are filled. Null wherever `carries_link` is false,
        and false is also the answer where the mail has no numbered links at all -- there is then
        nothing to be let in by.

        `valid_for_minutes` is how long it works for, in minutes, and only where the mail states a
        span itself: "expires in 10 minutes" gives 10, "valid for 1 hour" gives 60, "24 Stunden
        gueltig" gives 1440. Where the mail names a clock time or a date instead, or says nothing
        about it at all, it is null. You are not told when this mail was sent, so a time is not
        something you can turn into a span -- do not try.
    """.trimIndent(),
    serializer = MagicAnalysis.serializer(),
    maxOutputTokens = 200,
    validate = { analysis, context ->
        val carries = analysis.carriesCode || analysis.carriesLink
        val links = context.links.size

        // What the schema cannot say: that the fields only make sense together, that a code claimed
        // to be copied was copied, that a link is one of the mail's own, and that a span has to be
        // one somebody could wait out.
        when {
            carries && analysis.provider == null ->
                "The mail carries a code or a link but `provider` is empty. Name who it lets the " +
                    "reader into, or say it is not one of these mails by setting both flags false."
            analysis.provider?.isBlank() == true ->
                "`provider` came back empty. Use null instead."
            !carries && analysis.provider != null ->
                "`provider` is \"${analysis.provider}\" but the mail carries neither a code nor a " +
                    "link. A mail that lets nobody in has a null `provider`."
            analysis.carriesCode && analysis.code == null ->
                "`carries_code` is true but `code` is empty. Copy the code the mail writes out, or " +
                    "set `carries_code` to false."
            analysis.code?.isBlank() == true ->
                "`code` came back empty. Use null instead."
            !analysis.carriesCode && analysis.code != null ->
                "`code` is \"${analysis.code}\" but `carries_code` is false. A mail that writes no " +
                    "code out has a null `code`."
            analysis.code != null && analysis.code.length > MAX_CODE_LENGTH ->
                "`code` is \"${analysis.code}\", which is too long to be a code somebody types in. " +
                    "Copy the code alone, without the sentence around it."
            // The one guard against an invented code, and the reason `validate` is handed the mail:
            // a code the mail never wrote will let nobody in anywhere, and nothing downstream can
            // tell it from one that would have.
            analysis.code != null && !context.writes(analysis.code) ->
                "`code` is \"${analysis.code}\", which does not stand anywhere in this mail. Copy " +
                    "the one the mail writes, or set `carries_code` to false."
            analysis.carriesLink && links == 0 ->
                "`carries_link` is true but this mail carries no links at all, so there is none " +
                    "that could let anybody in. Set `carries_link` to false."
            analysis.carriesLink && analysis.linkNumber == null ->
                "`carries_link` is true but `link_number` is empty. Name the number of the link " +
                    "that signs the reader in, or set `carries_link` to false."
            !analysis.carriesLink && analysis.linkNumber != null ->
                "`link_number` is ${analysis.linkNumber} but `carries_link` is false. A mail with " +
                    "no way in has a null `link_number`."
            analysis.linkNumber != null && analysis.linkNumber !in 1..links ->
                "`link_number` is ${analysis.linkNumber}, and this mail has " +
                    "${if (links == 1) "1 numbered link" else "$links numbered links"}. Name one " +
                    "of the numbers in the list, or set `carries_link` to false."
            !carries && analysis.validForMinutes != null ->
                "`valid_for_minutes` is filled but the mail carries neither a code nor a link. " +
                    "There is nothing here that expires."
            analysis.validForMinutes != null && analysis.validForMinutes <= 0 ->
                "`valid_for_minutes` is ${analysis.validForMinutes}, which is not a span. Use " +
                    "null where the mail does not say how long it works for."
            analysis.validForMinutes != null && analysis.validForMinutes > MAX_VALIDITY_MINUTES ->
                "`valid_for_minutes` is ${analysis.validForMinutes}, which is longer than any " +
                    "such code or link lasts. Use null unless the mail states a span itself."
            else -> null
        }
    },
)

/**
 * Where a stated validity stops being believable: thirty days.
 *
 * Generous on purpose -- a sign-in link mailed for a device setup can be good for a week -- and
 * still short enough to catch the answer that read a date as a span.
 */
private const val MAX_VALIDITY_MINUTES = 60 * 24 * 30

/**
 * Where a code stops being one: forty characters.
 *
 * Long enough for the longest thing a reader is asked to type -- a grouped recovery code with its
 * dashes -- and short enough to catch the answer that copied the sentence the code stood in.
 */
private const val MAX_CODE_LENGTH = 40

/**
 * Whether the mail really writes [code], ignoring case and whitespace.
 *
 * Whitespace is dropped from both sides of the comparison, unlike the identifier check on the topic
 * step: "418 902" and "418902" are the same code and the mail is as likely to write either, so a
 * model that regrouped the digits has still copied the code. Case goes the same way -- a code is
 * typed into a field that does not care.
 *
 * Held against the same text the model was shown, so a code it read in the mail cannot be refused
 * here: what is caught is the code that was never in front of it.
 */
private fun MailContext.writes(code: String): Boolean {
    val wanted = code.condensed()

    return wanted.isNotEmpty() &&
        (subject.condensed().contains(wanted, ignoreCase = true) ||
            body.condensed().contains(wanted, ignoreCase = true))
}

private fun String.condensed(): String = filterNot { it.isWhitespace() }
