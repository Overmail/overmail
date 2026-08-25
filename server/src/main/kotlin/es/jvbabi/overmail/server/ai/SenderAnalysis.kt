package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who is behind a mail: the person who wrote it and the organisation they wrote for.
 *
 * Both are nullable and both are meant to be: plenty of mail comes from a person with no company
 * behind them, and at least as much from a company with no person in front of it. A field nobody
 * can fill from the mail is null, which is an answer.
 */
@Serializable
data class SenderAnalysis(
    /**
     * The person, as the mail spells their name: "Julius Babies". Null for mail nobody signed --
     * a newsletter, a receipt, anything from `no-reply@`.
     */
    @SerialName("person") val person: String? = null,

    /**
     * The organisation the mail was written for: "Deutsche Bahn", not "bahn.de". Null for private
     * mail, and for a company that is only visible in the address.
     */
    @SerialName("organisation") val organisation: String? = null,
)

/**
 * The step that fills it in.
 *
 * First of the analysis steps, and the shape the ones after it follow: one question, one schema,
 * the fast model. What the mail is *about* is a different step's business.
 */
val SENDER_STEP = MailAnalysisStep(
    id = "sender",
    instructions = """
        Say who is behind this mail: the person who wrote it, and the organisation they wrote it
        for.

        - `person`: the writer's name as the mail spells it, e.g. "Julius Babies". Take it from the
          signature, the display name or the way the mail signs off. Not a role ("Support Team"),
          not a greeting of the recipient, and never a name assembled out of an address.
        - `organisation`: the company, authority, club or shop the mail was written for, in the name
          it uses for itself: "Deutsche Bahn", not "bahn.de" and not "Deutsche Bahn AG Vertrieb".
          Take it from the signature, the imprint or the letterhead.

        Leave a field null when the mail does not show it. Mail from a person with no organisation
        behind them, and mail from an organisation with nobody named in front of it, are both
        normal and both have one field filled.

        The mailbox owner is not the answer unless the mail is theirs -- read the direction.
    """.trimIndent(),
    serializer = SenderAnalysis.serializer(),
    tier = ModelTier.FAST,
    maxOutputTokens = 200,
    validate = { analysis ->
        // A schema can say "string or null"; it cannot say "not the empty string". A model that
        // answers "" means null and is worth one more ask.
        when {
            analysis.person?.isBlank() == true -> "`person` came back empty. Use null instead."
            analysis.organisation?.isBlank() == true -> "`organisation` came back empty. Use null."
            else -> null
        }
    },
)
