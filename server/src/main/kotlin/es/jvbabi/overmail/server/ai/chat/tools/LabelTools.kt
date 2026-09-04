package es.jvbabi.overmail.server.ai.chat.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.attachLabelToEmail
import es.jvbabi.overmail.server.database.models.detachLabelFromEmail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * The three writes the agent may make to labels: making one, hanging it on a mail, taking it off
 * again.
 *
 * In one file because they are one subject and share the same lookup: whether the ids the model
 * came up with are this user's at all. Every one of them is bound to [User.Id] like the reading
 * tools, so there is nothing the model could say to touch somebody else's mail or labels -- and a
 * foreign id is answered as unknown, exactly like one that does not exist.
 *
 * What they change is announced through [MailNotifier], so a mail on screen shows the label
 * without the reader doing anything. Only a write that actually wrote is announced.
 */

/**
 * Makes a label, or hands back the one of that name that is already there.
 *
 * That second half is deliberate: it turns this into the way to get from a name to an id, which is
 * what [LabelEmailTool] needs, and it is the same rule the classification follows -- a user does
 * not want two labels called "Uni".
 */
class CreateLabelTool(
    private val userId: User.Id,
    private val database: OvermailDatabase,
    /** Called with the markup for a label that was made. Not called for one that was already there. */
    private val onLabelCreated: (String) -> Unit = {},
) : Tool<CreateLabelTool.Args, CreateLabelTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Create a label for the user. A name the user already has is not created a " +
        "second time: the existing label is answered with instead, so this is also how to look " +
        "up the id of a label by its name before attaching it with `${LabelEmailTool.NAME}`. " +
        "The colour is picked here, not by you.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The name of the label, as the user would write it.")
        @SerialName("name") val name: String,
        @property:LLMDescription(
            "What this label is for, one sentence. Shown to the user; leave it out when the " +
                "name says it all."
        )
        @SerialName("description") val description: String? = null,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("label")
        data class Label(
            @SerialName("label_id") val labelId: String,
            @SerialName("name") val name: String,
            @SerialName("color") val color: String,
            /** True when this label was already there, so nothing was created. */
            @SerialName("existed") val existed: Boolean,
        ) : Result()

        @Serializable
        @SerialName("invalid_argument")
        data class InvalidArgument(
            @SerialName("message") val message: String,
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val name = Label.normalizeName(args.name)
        if (name.isEmpty()) return Result.InvalidArgument("A label needs a name.")

        val description = args.description?.trim()?.takeIf { it.isNotEmpty() }

        return database.query {
            // Case-insensitive, so a model that writes "uni" does not create a twin of "Uni". The
            // stored spelling wins from here on.
            val existing = Labels
                .select(Labels.id, Labels.name, Labels.color)
                .where { (Labels.owner eq userId) and (Labels.name.lowerCase() eq name.lowercase()) }
                .limit(1)
                .singleOrNull()

            if (existing != null) {
                return@query Result.Label(
                    labelId = existing[Labels.id].value.toString(),
                    name = existing[Labels.name],
                    color = existing[Labels.color],
                    existed = true,
                )
            }

            val color = Label.defaultColorFor(name)
            val labelId = Labels.insertAndGetId {
                it[Labels.name] = name
                it[Labels.color] = color
                it[Labels.owner] = userId
                it[Labels.description] = description
                it[Labels.createdByAgent] = true
            }.value

            onLabelCreated(markup(labelId))

            Result.Label(labelId = labelId.toString(), name = name, color = color, existed = false)
        }
    }

    companion object {
        const val NAME = "create_label"

        /**
         * The element the chat renders for a label the agent made. Only the id: what the label
         * looks like is looked up when the answer is shown, so a rename does not leave an old
         * name standing in an old message.
         */
        fun markup(labelId: Uuid): String =
            """<toolcall-create-label labelId="$labelId"></toolcall-create-label>"""
    }
}

/** Hangs one of the user's labels on one of their mails. */
class LabelEmailTool(
    private val userId: User.Id,
    private val database: OvermailDatabase,
    private val mailNotifier: MailNotifier,
    /** Called with the markup for a label that was attached. Not called when it was already there. */
    private val onLabelAttached: (String) -> Unit = {},
) : Tool<LabelEmailTool.Args, LabelEmailTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Attach one of the user's labels to one of their emails. Both ids come from " +
        "the conversation or from `${SearchEmailsTool.NAME}`; a label id for a name you only " +
        "know as text comes from `${CreateLabelTool.NAME}`.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The id of the email, without the `[email:` wrapper.")
        @SerialName("email_id") val emailId: String,
        @property:LLMDescription("The id of the label, without the `[label:` wrapper.")
        @SerialName("label_id") val labelId: String,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("attached")
        data class Attached(
            /** True when the mail already carried this label, so nothing changed. */
            @SerialName("already_there") val alreadyThere: Boolean,
        ) : Result()

        /** An id that is not a uuid, nothing, or somebody else's -- all the same here. */
        @Serializable
        @SerialName("not_found")
        data class NotFound(
            @SerialName("message") val message: String =
                "No email and label with these ids belong to the user.",
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val pair = ownedEmailAndLabel(userId, database, args.emailId, args.labelId) ?: return Result.NotFound()
        val (emailId, labelId) = pair

        val attached = database.query { attachLabelToEmail(emailId, labelId) }

        if (attached) {
            onLabelAttached(markup(emailId = emailId, labelId = labelId))
            // A label changes what a mail shows, not where it sits in a listing.
            mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)
        }

        return Result.Attached(alreadyThere = !attached)
    }

    companion object {
        const val NAME = "label_email"

        fun markup(emailId: Uuid, labelId: Uuid): String =
            """<toolcall-label-email emailId="$emailId" labelId="$labelId"></toolcall-label-email>"""
    }
}

/** Takes one of the user's labels off one of their mails. */
class UnlabelEmailTool(
    private val userId: User.Id,
    private val database: OvermailDatabase,
    private val mailNotifier: MailNotifier,
    /** Called with the markup for a label that came off. Not called when it was not there. */
    private val onLabelDetached: (String) -> Unit = {},
) : Tool<UnlabelEmailTool.Args, UnlabelEmailTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Take a label off one of the user's emails. The label itself stays; only this " +
        "email stops carrying it. Which labels an email has comes from `${SearchEmailsTool.NAME}`.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The id of the email, without the `[email:` wrapper.")
        @SerialName("email_id") val emailId: String,
        @property:LLMDescription("The id of the label, without the `[label:` wrapper.")
        @SerialName("label_id") val labelId: String,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("detached")
        data class Detached(
            /** True when the mail did not carry this label to begin with. */
            @SerialName("was_not_there") val wasNotThere: Boolean,
        ) : Result()

        @Serializable
        @SerialName("not_found")
        data class NotFound(
            @SerialName("message") val message: String =
                "No email and label with these ids belong to the user.",
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val pair = ownedEmailAndLabel(userId, database, args.emailId, args.labelId) ?: return Result.NotFound()
        val (emailId, labelId) = pair

        val detached = database.query { detachLabelFromEmail(emailId, labelId) }

        if (detached) {
            onLabelDetached(markup(emailId = emailId, labelId = labelId))
            mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)
        }

        return Result.Detached(wasNotThere = !detached)
    }

    companion object {
        const val NAME = "unlabel_email"

        fun markup(emailId: Uuid, labelId: Uuid): String =
            """<toolcall-unlabel-email emailId="$emailId" labelId="$labelId"></toolcall-unlabel-email>"""
    }
}

/**
 * The two ids the model came up with, if both are this user's. Null for anything else, and the
 * cases are not told apart: a mail of somebody else, a label of somebody else, an id that is not
 * a uuid and one that is nothing all answer the same, so nothing here says what exists.
 *
 * Columns only, and the ownership is part of the lookup rather than a test on its result.
 */
private suspend fun ownedEmailAndLabel(
    userId: User.Id,
    database: OvermailDatabase,
    email: String,
    label: String,
): Pair<Uuid, Uuid>? {
    val emailId = Uuid.parseOrNull(email.trim()) ?: return null
    val labelId = Uuid.parseOrNull(label.trim()) ?: return null

    return database.query {
        val ownsEmail = Emails
            .leftJoin(ImapAccounts)
            .select(Emails.id)
            .where { (Emails.id eq emailId) and (ImapAccounts.user eq userId) }
            .empty()
            .not()
        if (!ownsEmail) return@query null

        val ownsLabel = Labels
            .select(Labels.id)
            .where { (Labels.id eq labelId) and (Labels.owner eq userId) }
            .empty()
            .not()
        if (!ownsLabel) return@query null

        emailId to labelId
    }
}
