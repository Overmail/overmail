package es.jvbabi.overmail.server.ai.classification

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.seconds

class EmailClassification(
    config: ApplicationConfig,
    private val model: LLModel,
    private val overmailDatabase: OvermailDatabase,
) {

    private val promptExecutor = MultiLLMPromptExecutor(
        OpenAILLMClient(
            apiKey = config.ai.apiKey,
            settings = OpenAIClientSettings(
                baseUrl = config.ai.baseUrl,
            )
        )
    )


    suspend fun run(email: Email) {
        val user = overmailDatabase.query { email.imapAccount.user }

        val basePrompt = overmailDatabase.query {
            prompt("base") {
                system("You are an AI used to help the user organize their emails. You will be given the content of an email and some context about the user. Your task will be to answer the questions about the email and the user. You will answer in JSON format, details are provided in the questions.")
                system("The users name is ${email.imapAccount.user.username} and their email address is ${email.imapAccount.user.email}.")
                system(
                    """
                    From: ${email.sender.address} (${if (email.senderName != null) email.senderName else "no name provided"})
                    Subject: ${email.subject}
                    Sent: ${email.sent}
                    
                    Text content: ${email.textContent}
                """.trimIndent()
                )
            }
        }

        println("Classifying email origin for email ID: ${email.id} (${email.subject})")

        val emailFirstLookResult = promptExecutor.executeStructured<EmailFirstLook>(
            prompt = prompt(basePrompt) {
                user("Analyze the email above and provide the requested classification.")
            },
            model = model,
        )

        if (emailFirstLookResult.isFailure) {
            // Handle the case where the classification failed
            println("Failed to classify email origin for email ID: ${email.id}")
            println(emailFirstLookResult.exceptionOrNull()?.stackTraceToString())
            return
        }

        val emailFirstLook = emailFirstLookResult.getOrThrow()

        println(emailFirstLook)

        // The prompts also tell the model to propose no tags for untrustworthy mail, but this
        // guard is the hard guarantee: a phishing mail must never create or receive labels,
        // no matter what the model answers.
        if (!emailFirstLook.data.trustworthy) {
            println("Email ID ${email.id} classified as untrustworthy, skipping tag assignment.")
            delay(5.seconds)
            return
        }

        // The full tag list goes into the prompt, not just exact-name matches of the proposals:
        // the model can only reuse "Sicherheitswarnung" instead of inventing
        // "Sicherheitsbenachrichtigung" if it sees that the former exists.
        val existingTags = overmailDatabase.query {
            Labels
                .selectAll()
                .where { Labels.owner eq user.id }
                .let { Label.wrapRows(it) }
                .toList()
        }

        val finalizedClassificationResult = promptExecutor.executeStructured<EmailClassificationFinalized>(
            prompt = prompt(basePrompt) {
                user(
                    """
                        This email has been classified as follows:
                        ${emailFirstLook.data}

                        These are all tags the user currently has:
                        ${existingTags.joinToString("\n") { "- " + it.name + (it.description?.let { d -> " ($d)" } ?: "") }.ifBlank { "The user has no tags yet." }}

                        No additional context is available. Please polish and finalize the classification. The proposed classification may be incorrect or incomplete. Feel free to change it as you see fit.

                        Whenever an existing tag covers the email, reuse its exact name. Never create a tag that is a near-duplicate or synonym of an existing one — pick the existing tag instead. Drop proposed tags that are too specific to ever match another email.
                    """.trimIndent()
                )
            },
            model = model,
        )

        if (finalizedClassificationResult.isFailure) {
            // Handle the case where the classification failed
            println("Failed to finalize email classification for email ID: ${email.id}")
            println(finalizedClassificationResult.exceptionOrNull()?.stackTraceToString())
            return
        }

        val finalizedClassification = finalizedClassificationResult.getOrThrow()

        println(finalizedClassification)

        // The sender classification is attached as tags as well, so emails are findable by
        // organization, sending platform, and sender name without the model having to repeat
        // them in its tag list.
        val originTags = with(emailFirstLook.data.sender) {
            listOfNotNull(
                organization?.let {
                    EmailClassificationFinalized.Tag(
                        name = it,
                        description = "E-Mails von $it",
                        reason = "Identified as the organization this email is from."
                    )
                },
                via?.let {
                    EmailClassificationFinalized.Tag(
                        name = it,
                        description = "E-Mails, die über $it versendet wurden",
                        reason = "Identified as the platform this email was sent through."
                    )
                },
                name?.let {
                    EmailClassificationFinalized.Tag(
                        name = it,
                        description = "E-Mails von $it",
                        reason = "Identified as the sender of this email."
                    )
                },
            )
        }

        // Model tags come first so their richer descriptions win when both name a tag equally.
        val tags = (finalizedClassification.data.tags + originTags).distinctBy { it.name.trim().lowercase() }

        tags.forEach { tag ->
            overmailDatabase.query {
                // Case-insensitive lookup as the last line of defense against duplicate labels
                // that differ only in casing (e.g. "newsletter" vs "Newsletter").
                val tagElement = Label.find { (Labels.owner eq user.id) and (Labels.name.lowerCase() eq tag.name.lowercase()) }.firstOrNull() ?: Label.new {
                    name = tag.name
                    color = Label.defaultColorFor(tag.name)
                    owner = user
                    description = tag.description
                    createdByAgent = true
                }

                if (email.labels.none { it.id == tagElement.id }) {
                    EmailLabel.new {
                        this.email = email
                        this.label = tagElement
                        this.labeledByAgent = true
                        this.reason = tag.reason
                    }
                }
            }
        }
    }
}

@Serializable
@SerialName("EmailFirstLook")
data class EmailFirstLook(
    @SerialName("sender") val sender: EmailOrigin,
    @SerialName("magic_email")
    @property:LLMDescription(
        """
            Set to null if this mail does not carry a one time code or a magic link to log in.
            
            Otherwise, set to the object.
        """
    )
    val magicEmail: MagicEmail?,

    @SerialName("trustworthy")
    @property:LLMDescription(
        """
            If this email is likely to be trustworthy, set this to true. If it is likely to be a phishing attempt or otherwise untrustworthy, set this to false.
        """
    )
    val trustworthy: Boolean,

    @SerialName("proposed_tags")
    @property:LLMDescription(
        """
            A list of tags for this email, used to find it again later.

            Tags are the words the user would use when thinking about or searching for this email. Propose tags along these three dimensions:
            1. Sender: the organization or community the email is from, for every recognizable organization. Use the official name, exactly as in the sender classification's `organization` value — do not abbreviate (e.g. "Young Founders Network", not "YFN").
            2. Type of email: what the email is, e.g. "Newsletter", "Rechnung", "Versandbestätigung", "Veranstaltung", "Login", "Anmeldung".
            3. Ongoing matter: when the email belongs to a matter that spans several emails (a job application, a move, a trip, a support case), tag the matter by the name the user would call it (e.g. "Wohnungssuche", "Bewerbung"). Never use raw identifiers like order or ticket numbers for this — threads are already grouped via the thread identifier.

            Rules:
            - There is no fixed maximum, but every tag must earn its place: propose a tag only if it genuinely helps the user find or organize this email, never tags for tags' sake.
            - Do not invent abstract topic or life-area tags beyond these dimensions (no tags like "Finanzen" or "Gesundheit").
            - If the email is not trustworthy (see `trustworthy`), do not propose any tags: set this to an empty list.
            - If no useful tag can be determined, set this to an empty list.
        """
    )
    val proposedTags: List<String>,

    @SerialName("thread_identifier")
    @property:LLMDescription(
        """
            A string that can be used to identify the email thread this email belongs to. This can be used to group emails together in a conversation view. If the email does not belong to a thread, set this to null.
            
            Examples for thread identifiers could be a unique string derived from the email's subject, like an order number, a ticket ID from a support system, or any other unique identifier that can be used to group related emails together. If no such identifier can be determined, set this to null.
        """
    )
    val threadIdentifier: String?,
) {
    @Serializable
    @SerialName("EmailOrigin")
    @LLMDescription("Information about the sender of the email, including their name, affiliated organization, and the platform or service used to send the email.")
    data class EmailOrigin(
        @property:LLMDescription(
            """
        The organization the sender is affiliated with. This can be `null` if the sender is a private person and the email is clearly in a private context.

        Otherwise, identify the company or organization the email is actually from. Use the official company or organization name, not the domain name. For example, if an email is sent from `notification@somesoftware.com`, the organization should be "Some Software", using the correct spelling and capitalization. If the exact name cannot be determined from the email, fall back to the domain name.

        Distinguish the organization from the email platform or service used to send the message. If an individual from another organization sends an email through Some Software, then Some Software is the platform (via), not the sender's organization.
    """
        )
        @SerialName("organization")
        val organization: String?,

        @property:LLMDescription(
            """
        The email platform, service, or system through which the email was sent, if it is different from the sender's organization.

        For example, if an employee of Example Company sends an email using Some Software, set `via` to "Some Software" and `organization` to "Example Company".

        Set this to `null` if no separate platform or sending service can be identified. Do not use the organization itself as the `via` value.
    """
        )
        @SerialName("via")
        val via: String?,

        @property:LLMDescription(
            """
        The name of the person or sender represented by the email address.

        Prefer the sender's actual personal name when available. If the sender is an automated or generic account, use the identifiable sender or service name instead, if one can be determined. Do not infer a person's name from the email address unless there is sufficient evidence to do so.
        
        If the sender's name cannot be determined, set this to `null`. It shouldn't just be the name of the organization or service.
    """
        )
        @SerialName("name")
        val name: String?,
    )

    @Serializable
    @SerialName("MagicEmail")
    @LLMDescription("Information about a magic email, which is an email that contains a one-time code or a magic link to log in.")
    data class MagicEmail(
        @SerialName("payload")
        @property:LLMDescription(
            """
                The one-time code or magic link contained in the email. This is the actual content that the user can use to log in or authenticate. It should be extracted from the email's text.
            """
        )
        val payload: String,

        @SerialName("type")
        @property:LLMDescription(
            """
                The type of magic email. This indicates whether the email contains a one-time code or a magic link.
            """
        )
        val type: MagicEmailType,

        @SerialName("valid_for")
        @property:LLMDescription(
            """
                The duration for which the one-time code or magic link is valid. This should be expressed in seconds. If the validity period cannot be determined from the email, this can be set to `null`.
            """
        )
        val validFor: Int?,
    ) {
        enum class MagicEmailType {
            @SerialName("one_time_code")
            ONE_TIME_CODE,

            @SerialName("magic_link")
            MAGIC_LINK
        }
    }
}

@Serializable
@SerialName("EmailClassificationFinalized")
data class EmailClassificationFinalized(
    @SerialName("tags")
    @property:LLMDescription(
        """
            The final list of tags for this email, used to find it again later.

            Tags are the words the user would use when thinking about or searching for this email. Assign tags along these three dimensions:
            1. Sender: the organization or community the email is from, for every recognizable organization. Use the official name, exactly as in the sender classification's `organization` value — do not abbreviate (e.g. "Young Founders Network", not "YFN").
            2. Type of email: what the email is, e.g. "Newsletter", "Rechnung", "Versandbestätigung", "Veranstaltung", "Login", "Anmeldung".
            3. Ongoing matter: when the email belongs to a matter that spans several emails (a job application, a move, a trip, a support case), tag the matter by the name the user would call it (e.g. "Wohnungssuche", "Bewerbung"). Never use raw identifiers like order or ticket numbers for this — threads are already grouped via the thread identifier.

            Rules:
            - There is no fixed maximum, but every tag must earn its place: assign a tag only if it genuinely helps the user find or organize this email, never tags for tags' sake.
            - If an existing tag of the user covers one of these dimensions, reuse its exact name instead of inventing a variation.
            - Do not invent abstract topic or life-area tags beyond these dimensions (no tags like "Finanzen" or "Gesundheit").
            - If the email is not trustworthy, do not assign any tags: set this to an empty list.
            - If no useful tag can be determined, set this to an empty list.
        """
    )
    val tags: List<Tag>
) {
    @SerialName("Tag")
    @Serializable
    @LLMDescription("A tag that can be used to categorize or label an email.")
    data class Tag(
        @SerialName("name")
        @property:LLMDescription(
            """
                The name of the tag. This should be a concise and descriptive label that represents the category or topic of the email. The name should be correctly spelled and capitalized and reflect the German language if applicable.
            """
        )
        val name: String,
        @SerialName("description")
        @property:LLMDescription(
            """
                A brief description of the tag. This should provide additional context or information about the tag's purpose or meaning.
            """
        )
        val description: String,

        @SerialName("reason")
        @property:LLMDescription(
            """
                The reason for assigning this tag to the email. This should explain why the tag is appropriate for the email's content and context. Ideally, you provide proof or evidence from the email that supports the assignment.
            """
        )
        val reason: String
    )
}