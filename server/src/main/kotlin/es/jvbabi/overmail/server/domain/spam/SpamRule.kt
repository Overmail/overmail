package es.jvbabi.overmail.server.domain.spam

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A spam filter: boolean operators over comparisons on one mail, which is spam when the tree comes
 * out true. What holds it against a mail is [SpamRuleMatcher].
 *
 * `op` is the tag on every node, which is also how it travels -- the block editor in the web app
 * builds exactly this shape, see `web/src/lib/app/spam_dialog/rule.ts`. Keep the two in step: a
 * name changed on one side is a filter the other side cannot read.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
sealed interface SpamRule {

    /**
     * True when every operand is true. Holds as many operands as the block it came from had
     * conditions stacked in it.
     */
    @Serializable
    @SerialName("and")
    data class And(val operands: List<SpamRule>) : SpamRule

    /** True when at least one operand is. */
    @Serializable
    @SerialName("or")
    data class Or(val operands: List<SpamRule>) : SpamRule

    /** True when its operand is not. */
    @Serializable
    @SerialName("not")
    data class Not(val operand: SpamRule) : SpamRule

    /** One part of the mail held against one text. */
    @Serializable
    @SerialName("match")
    data class Match(
        val field: SpamRuleField,
        /** How [value] is held against the field, not what is compared. */
        val match: SpamRuleMatch,
        val value: String,
    ) : SpamRule
}

/** The part of a mail a comparison reads. */
@Serializable
enum class SpamRuleField {
    @SerialName("subject")
    SUBJECT,

    /** The display name the sender used in this mail, which many mails do not carry. */
    @SerialName("sender_name")
    SENDER_NAME,

    @SerialName("sender_address")
    SENDER_ADDRESS,

    /** The body as a reader sees it, see [MailFacts]. */
    @SerialName("body")
    BODY,
}

/** How a comparison holds a part of the mail against its text. */
@Serializable
enum class SpamRuleMatch {
    @SerialName("equals")
    EQUALS,

    @SerialName("contains")
    CONTAINS,

    @SerialName("regex")
    REGEX,
}
