package es.jvbabi.overmail.server.ai.steps

import ai.koog.agents.core.tools.annotations.LLMDescription
import es.jvbabi.overmail.server.ai.MailAnalysisStep
import es.jvbabi.overmail.server.ai.ModelTier
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Who a mail came from, as far as the mail itself says so. */
@Serializable
data class MailOrigin(
    @property:LLMDescription("Name of the human who wrote the mail, e.g. \"Ahmad Saleem\". Null if the mail names no human. A job title, department, team or system name is not a person.")
    val person: String? = null,

    @property:LLMDescription("Organisation the mail was sent on behalf of. Null when a private person writes on their own behalf.")
    val institution: String? = null,
)

/**
 * Reads the sender off a mail, and nothing else: what kind of mail it is, how urgent it is or what
 * to do with it are steps of their own.
 */
val MailOriginStep = MailAnalysisStep(
    id = "mail-origin",
    serializer = serializer<MailOrigin>(),
    tier = ModelTier.FAST,
    instructions = """
        Determine who the mail came from.

        person: the name of a human being, and nothing else.
        - Take the name the mail itself gives: a signature, a "Comment from X", a "X wrote:".
        - A job title, a department, a team, a role or a piece of software is not a person. If that
          is all the mail offers, answer null. "Kundendienst", "Support", "Customer Service",
          "Pest Control Officer", "Team", "Redaktion", "Bugzilla", "noreply" are all wrong answers.
        - Notification systems (bug trackers, ticket systems, build servers) send on behalf of a
          human: name that human if the mail names one, otherwise answer null.
        - On an outgoing mail the sender is the mailbox owner: name them, and the institution they
          wrote on behalf of -- never the party they wrote to.

        institution: the organisation the mail was sent on behalf of.
        - A company, authority, school, or the project whose system sent the mail.
        - The sender's own domain counts as evidence when it belongs to an organisation:
          julian.dorn@schulverwalter.de writes for Schulverwalter, even when the mail carries no
          letterhead and signs off with a first name only.
        - A freemail domain is not an organisation and says nothing about who someone writes for:
          gmail.com, gmx.de, web.de, outlook.com, icloud.com, posteo.de, mailbox.org and the like.
        - Answer with the organisation's name, not with its domain: "Schulverwalter", not
          "schulverwalter.de"; "WebKit", not "webkit.org".
        - Answer null when a private person writes on their own behalf.
    """.trimIndent(),
)
