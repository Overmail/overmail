package es.jvbabi.overmail.server.ai.classification

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailAiClassificationEvent
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock

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

        // Opened before and closed after the actual work (in the finally below), so every exit
        // path — model errors, the untrustworthy skip, unexpected exceptions — leaves a record.
        // An event without finishedAt therefore means the classification crashed hard.
        val event = overmailDatabase.query {
            EmailAiClassificationEvent.new {
                this.email = email
                this.provider = this@EmailClassification.model.provider.id
                this.model = this@EmailClassification.model.id
                this.startedAt = Clock.System.now()
            }
        }

        var totalTokensIn: Int? = null
        var totalTokensOut: Int? = null
        fun recordUsage(response: StructuredResponse<*>) {
            response.message.metaInfo.inputTokensCount?.let { totalTokensIn = (totalTokensIn ?: 0) + it }
            response.message.metaInfo.outputTokensCount?.let { totalTokensOut = (totalTokensOut ?: 0) + it }
        }

        val logBuilder = StringBuilder()
        fun log(message: String) {
            println(message)
            logBuilder.appendLine("[${Clock.System.now()}] $message")
        }

        try {
            classify(email, user, ::recordUsage, ::log)
        } catch (exception: Exception) {
            // Logged so the crash shows up in the persisted event, not just on stdout.
            log("Unexpected error during classification: ${exception.stackTraceToString()}")
            throw exception
        } finally {
            overmailDatabase.query {
                event.finishedAt = Clock.System.now()
                event.tokensIn = totalTokensIn
                event.tokensOut = totalTokensOut
                event.log = logBuilder.toString()
            }
        }
    }

    private suspend fun classify(
        email: Email,
        user: User,
        recordUsage: (StructuredResponse<*>) -> Unit,
        log: (String) -> Unit,
    ) {
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

        log("Classifying email ID: ${email.id} (${email.subject})")
        log("Base prompt:\n" + basePrompt.messages.joinToString("\n") { "[${it.role}] ${it.textContent()}" })

        val firstLookRequest = "Analyze the email above and provide the requested classification."
        log("First-look request: $firstLookRequest")

        val emailFirstLookResult = promptExecutor.executeStructured<EmailFirstLook>(
            prompt = prompt(basePrompt) {
                user(firstLookRequest)
            },
            model = model,
        )

        if (emailFirstLookResult.isFailure) {
            log("Failed to classify email origin for email ID: ${email.id}")
            log(emailFirstLookResult.exceptionOrNull()?.stackTraceToString() ?: "No exception details available.")
            return
        }

        val emailFirstLook = emailFirstLookResult.getOrThrow()
        recordUsage(emailFirstLook)

        log("First-look raw response:\n${emailFirstLook.message.textContent()}")
        log("First-look parsed: ${emailFirstLook.data}")

        // The prompts also tell the model to propose no labels for untrustworthy mail, but this
        // guard is the hard guarantee: a phishing mail must never create or receive labels,
        // no matter what the model answers.
        if (!emailFirstLook.data.trustworthy) {
            log("Email ID ${email.id} classified as untrustworthy, skipping label assignment.")
            return
        }

        // The full label list goes into the prompt, not just exact-name matches of the proposals:
        // the model can only reuse "Sicherheitswarnung" instead of inventing
        // "Sicherheitsbenachrichtigung" if it sees that the former exists.
        val existingLabels = overmailDatabase.query {
            Labels
                .selectAll()
                .where { Labels.owner eq user.id }
                .let { Label.wrapRows(it) }
                .toList()
        }

        val finalizeRequest = """
            This email has been classified as follows:
            ${emailFirstLook.data}

            These are all labels the user currently has:
            ${existingLabels.joinToString("\n") { "- " + it.name + (it.description?.let { d -> " ($d)" } ?: "") }.ifBlank { "The user has no labels yet." }}

            No additional context is available. Please polish and finalize the classification. The proposed classification may be incorrect or incomplete. Feel free to change it as you see fit.

            Whenever an existing label covers the email, reuse its exact name. Creating a new label is the exception, not the rule: before creating one, check it against the user's existing labels AND against the other labels in your answer. Names that differ only by abbreviation, legal suffix ("e.V.", "GmbH"), spelling, or plural are the SAME label — output it once, with one canonical name. Drop proposed labels that are too specific to ever match another email.
        """.trimIndent()
        log("Finalize request:\n$finalizeRequest")

        val finalizedClassificationResult = promptExecutor.executeStructured<EmailClassificationFinalized>(
            prompt = prompt(basePrompt) {
                user(finalizeRequest)
            },
            model = model,
        )

        if (finalizedClassificationResult.isFailure) {
            log("Failed to finalize email classification for email ID: ${email.id}")
            log(finalizedClassificationResult.exceptionOrNull()?.stackTraceToString() ?: "No exception details available.")
            return
        }

        val finalizedClassification = finalizedClassificationResult.getOrThrow()
        recordUsage(finalizedClassification)

        log("Finalize raw response:\n${finalizedClassification.message.textContent()}")
        log("Finalize parsed: ${finalizedClassification.data}")

        // The sender classification is attached as labels as well, so emails are findable by
        // organization, sending platform, and sender name without the model having to repeat
        // them in its label list.
        val originLabels = with(emailFirstLook.data.sender) {
            listOfNotNull(
                organization?.let {
                    EmailClassificationFinalized.Label(
                        name = it,
                        description = "E-Mails von $it",
                        reason = "Identified as the organization this email is from."
                    )
                },
                via?.let {
                    EmailClassificationFinalized.Label(
                        name = it,
                        description = "E-Mails, die über $it versendet wurden",
                        reason = "Identified as the platform this email was sent through."
                    )
                },
                name?.let {
                    EmailClassificationFinalized.Label(
                        name = it,
                        description = "E-Mails von $it",
                        reason = "Identified as the sender of this email."
                    )
                },
            )
        }

        log("Origin labels derived from the sender classification: ${originLabels.map { it.name }}")

        // Model labels come first so their richer descriptions win when both name a label equally.
        val labels = (finalizedClassification.data.labels + originLabels).distinctBy { normalizeLabelName(it.name).lowercase() }

        labels.forEach { label ->
            // Normalized before lookup and storage, so model output that differs only in stray
            // whitespace still resolves onto the exact stored label name.
            val requestedName = normalizeLabelName(label.name)
            if (requestedName.isEmpty()) {
                log("Skipping label with blank name (reason: ${label.reason})")
                return@forEach
            }

            val (resolvedName, existing, alreadyAttached) = overmailDatabase.query {
                // Case-insensitive lookup as the last line of defense against duplicate labels
                // that differ only in casing (e.g. "newsletter" vs "Newsletter"). When a label is
                // found, its stored name wins over whatever spelling the model used.
                val existing = Label.find { (Labels.owner eq user.id) and (Labels.name.lowerCase() eq requestedName.lowercase()) }.firstOrNull()
                val labelElement = existing ?: Label.new {
                    name = requestedName
                    color = Label.defaultColorFor(requestedName)
                    owner = user
                    description = label.description
                    createdByAgent = true
                }

                // Checked against the table instead of the entity's cached referrers, so the
                // dedup does not depend on the state of the DAO cache.
                val alreadyAttached = EmailLabel
                    .find { (EmailLabels.email eq email.id) and (EmailLabels.label eq labelElement.id) }
                    .any()
                if (!alreadyAttached) {
                    EmailLabel.new {
                        this.email = email
                        this.label = labelElement
                        this.labeledByAgent = true
                        this.reason = label.reason
                    }
                }

                Triple(labelElement.name, existing != null, alreadyAttached)
            }
            log(
                "Label '$requestedName': " +
                        (if (existing) "reused existing label '$resolvedName'" else "created new label") +
                        ", " + (if (alreadyAttached) "email already had it" else "attached to email") +
                        " (reason: ${label.reason})"
            )
        }
    }

    companion object {
        /** Trims and collapses inner whitespace, so lookups and storage always see one spelling. */
        private fun normalizeLabelName(name: String): String = name.trim().replace(Regex("\\s+"), " ")
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

    @SerialName("proposed_labels")
    @property:LLMDescription(
        """
            A list of labels for this email, used to find it again later.

            Labels are the words the user would use when thinking about or searching for this email. Propose labels along these three dimensions:
            1. Sender: the organization or community the email is from, for every recognizable organization. Use the official name without legal form suffixes, and do not abbreviate: "Young Founders Network", not "YFN" and not "Young Founders Network e.V.".
            2. Type of email: what the email is, e.g. "Newsletter", "Rechnung", "Versandbestätigung", "Veranstaltung", "Login", "Anmeldung".
            3. Ongoing matter: when the email belongs to a matter that spans several emails (a job application, a move, a trip, a support case), label the matter by the name the user would call it (e.g. "Wohnungssuche", "Bewerbung"). Never use raw identifiers like order or ticket numbers for this — threads are already grouped via the thread identifier.

            Rules:
            - There is no fixed maximum, but every label must earn its place: propose a label only if it genuinely helps the user find or organize this email, never labels for labels' sake.
            - Exactly one label per concept. Never emit several spelling variants of the same name — with and without a legal suffix ("e.V.", "GmbH"), abbreviated and written out, singular and plural, or with typos. If two candidate labels mean the same thing, output only one of them.
            - Do not invent abstract topic or life-area labels beyond these dimensions (no labels like "Finanzen" or "Gesundheit").
            - If the email is not trustworthy (see `trustworthy`), do not propose any labels: set this to an empty list.
            - If no useful label can be determined, set this to an empty list.
        """
    )
    val proposedLabels: List<String>,

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

        Otherwise, identify the company or organization the email is actually from. Use the official company or organization name, not the domain name, and omit legal form suffixes such as "e.V.", "GmbH", "AG", or "Inc." (e.g. "Young Founders Network", not "Young Founders Network e.V."). For example, if an email is sent from `notification@somesoftware.com`, the organization should be "Some Software", using the correct spelling and capitalization. If the exact name cannot be determined from the email, fall back to the domain name.

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
    @SerialName("labels")
    @property:LLMDescription(
        """
            The final list of labels for this email, used to find it again later.

            Labels are the words the user would use when thinking about or searching for this email. Assign labels along these three dimensions:
            1. Sender: the organization or community the email is from, for every recognizable organization. Use the official name without legal form suffixes, and do not abbreviate: "Young Founders Network", not "YFN" and not "Young Founders Network e.V.".
            2. Type of email: what the email is, e.g. "Newsletter", "Rechnung", "Versandbestätigung", "Veranstaltung", "Login", "Anmeldung".
            3. Ongoing matter: when the email belongs to a matter that spans several emails (a job application, a move, a trip, a support case), label the matter by the name the user would call it (e.g. "Wohnungssuche", "Bewerbung"). Never use raw identifiers like order or ticket numbers for this — threads are already grouped via the thread identifier.

            Rules:
            - There is no fixed maximum, but every label must earn its place: assign a label only if it genuinely helps the user find or organize this email, never labels for labels' sake.
            - If an existing label of the user covers one of these dimensions, reuse its exact name instead of inventing a variation.
            - Exactly one label per concept. Never emit several spelling variants of the same name — with and without a legal suffix ("e.V.", "GmbH"), abbreviated and written out, singular and plural, or with typos. If two candidate labels mean the same thing, output only one of them.
            - Do not invent abstract topic or life-area labels beyond these dimensions (no labels like "Finanzen" or "Gesundheit").
            - If the email is not trustworthy, do not assign any labels: set this to an empty list.
            - If no useful label can be determined, set this to an empty list.
        """
    )
    val labels: List<EmailClassificationFinalized.Label>
) {
    @SerialName("Label")
    @Serializable
    @LLMDescription("A label that can be used to categorize or label an email.")
    data class Label(
        @SerialName("name")
        @property:LLMDescription(
            """
                The name of the label. This should be a concise and descriptive label that represents the category or topic of the email. The name should be correctly spelled and capitalized and reflect the German language if applicable.
            """
        )
        val name: String,
        @SerialName("description")
        @property:LLMDescription(
            """
                A brief description of the label. This should provide additional context or information about the label's purpose or meaning.
            """
        )
        val description: String,

        @SerialName("reason")
        @property:LLMDescription(
            """
                The reason for assigning this label to the email. This should explain why the label is appropriate for the email's content and context. Ideally, you provide proof or evidence from the email that supports the assignment.
            """
        )
        val reason: String
    )
}