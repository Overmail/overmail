package es.jvbabi.overmail.server.ai.chat.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.database.models.User
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Enough to answer with, few enough to leave room for the conversation around them. */
private const val MAX_HITS = 8

/**
 * The assistant's own memory: looking something up, reading it in full, and writing it down.
 *
 * All three go through [KnowledgeStore], which is also what the classification uses -- what the
 * agent learns while sorting a mail is the same knowledge it reads in a chat. Bound to one user
 * like every tool here.
 */

/**
 * Looks for what is known by keyword.
 *
 * Answers with metadata and the beginning of each entry, not the whole of them: this is the step
 * that decides what is worth loading, and [ReadKnowledgeTool] is the one that loads it.
 */
class SearchKnowledgeTool(
    private val userId: User.Id,
    private val store: KnowledgeStore,
    /** Called with the markup for the search that ran, whatever it turned up. */
    private val onSearch: (String) -> Unit = {},
) : Tool<SearchKnowledgeTool.Args, SearchKnowledgeTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Search what you know about this user: their habits, their correspondents, " +
        "decisions they made, dates that matter to them. Give the words you would look for " +
        "yourself, not a sentence. Answers with the beginning of each entry; read the ones you " +
        "need in full with `${ReadKnowledgeTool.NAME}`. An empty query answers with the most " +
        "recently written entries.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The words to look for, separated by spaces or commas.")
        @SerialName("query") val query: String = "",
    )

    @Serializable
    data class Result(
        @SerialName("entries") val entries: List<Entry>,
    )

    @Serializable
    data class Entry(
        @SerialName("knowledge_id") val knowledgeId: String,
        @SerialName("name") val name: String,
        @SerialName("keywords") val keywords: List<String>,
        /** The day this entry is about, absent for knowledge that is not tied to one. */
        @SerialName("relevant_on") val relevantOn: String?,
        /** The beginning of the entry; ask for it by id to read the rest. */
        @SerialName("excerpt") val excerpt: String,
    )

    override suspend fun execute(args: Args): Result {
        onSearch(markup(args.query.trim()))

        val entries = store.search(userId = userId, query = args.query, limit = MAX_HITS)

        return Result(
            entries = entries.map { entry ->
                Entry(
                    knowledgeId = entry.id.toString(),
                    name = entry.name,
                    keywords = entry.keywords,
                    relevantOn = entry.relevantOn?.toString(),
                    excerpt = entry.excerpt,
                )
            }
        )
    }

    companion object {
        const val NAME = "search_knowledge"

        fun markup(query: String): String =
            """<toolcall-search-knowledge query="${escapeAttribute(query)}"></toolcall-search-knowledge>"""
    }
}

/** Reads one entry in full, by the id a search handed out. */
class ReadKnowledgeTool(
    private val userId: User.Id,
    private val store: KnowledgeStore,
    private val onRead: (String) -> Unit = {},
) : Tool<ReadKnowledgeTool.Args, ReadKnowledgeTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Read one entry of what you know about this user in full. The id comes from " +
        "`${SearchKnowledgeTool.NAME}`.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The id of the entry, as the search gave it.")
        @SerialName("knowledge_id") val knowledgeId: String,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("knowledge")
        data class Knowledge(
            @SerialName("knowledge_id") val knowledgeId: String,
            @SerialName("name") val name: String,
            @SerialName("keywords") val keywords: List<String>,
            @SerialName("relevant_on") val relevantOn: String?,
            @SerialName("description") val description: String,
        ) : Result()

        @Serializable
        @SerialName("not_found")
        data class NotFound(
            @SerialName("message") val message: String = "No knowledge with this id belongs to the user.",
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val id = Uuid.parseOrNull(args.knowledgeId.trim()) ?: return Result.NotFound()
        val entry = store.read(userId = userId, id = id) ?: return Result.NotFound()

        onRead(markup(entry.name))

        return Result.Knowledge(
            knowledgeId = entry.id.toString(),
            name = entry.name,
            keywords = entry.keywords,
            relevantOn = entry.relevantOn?.toString(),
            description = entry.description,
        )
    }

    companion object {
        const val NAME = "read_knowledge"

        fun markup(name: String): String =
            """<toolcall-read-knowledge name="${escapeAttribute(name)}"></toolcall-read-knowledge>"""
    }
}

/**
 * Writes something down, or rewrites the entry of that name.
 *
 * Deliberately one tool for both: the name is the handle, so learning more about something is
 * that entry saying more rather than a second one beside it.
 */
class WriteKnowledgeTool(
    private val userId: User.Id,
    private val store: KnowledgeStore,
    private val onWrite: (String) -> Unit = {},
) : Tool<WriteKnowledgeTool.Args, WriteKnowledgeTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Write down something about this user that will still be worth knowing next " +
        "week: how they want their mail handled, who writes to them and why, a date they will " +
        "be asked about again. Not the content of a single email, and not what the user can see " +
        "for themselves. Writing a name that already exists replaces that entry, so read it " +
        "first with `${ReadKnowledgeTool.NAME}` and send the whole text back with your addition.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("What the entry is about, in a few words. This is also its handle.")
        @SerialName("name") val name: String,
        @property:LLMDescription("What you learned, in full sentences. Write it for your future self.")
        @SerialName("description") val description: String,
        @property:LLMDescription(
            "The words you would search for to find this again -- names, addresses, order " +
                "numbers, the subject it comes up under. Without them the entry is hard to find."
        )
        @SerialName("keywords") val keywords: List<String> = emptyList(),
        @property:LLMDescription(
            "The day this is about as YYYY-MM-DD, for a deadline, an appointment or a change " +
                "that takes effect. Leave it out when the entry is not tied to a day."
        )
        @SerialName("relevant_on") val relevantOn: String? = null,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("written")
        data class Written(
            @SerialName("knowledge_id") val knowledgeId: String,
            @SerialName("name") val name: String,
            /** True when an entry of that name was rewritten rather than added. */
            @SerialName("replaced") val replaced: Boolean,
        ) : Result()

        @Serializable
        @SerialName("invalid_argument")
        data class InvalidArgument(
            @SerialName("message") val message: String,
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val name = args.name.trim()
        if (name.isEmpty()) return Result.InvalidArgument("An entry needs a name.")
        if (args.description.isBlank()) return Result.InvalidArgument("An entry needs a description.")

        val relevantOn = args.relevantOn?.trim()?.takeIf { it.isNotEmpty() }?.let { date ->
            runCatching { LocalDate.parse(date) }.getOrNull()
                ?: return Result.InvalidArgument("Expected relevant_on as YYYY-MM-DD.")
        }

        val written = store.write(
            userId = userId,
            name = name,
            description = args.description,
            keywords = args.keywords,
            relevantOn = relevantOn,
            byAgent = true,
        )

        onWrite(markup(name = written.entry.name, replaced = written.existed))

        return Result.Written(
            knowledgeId = written.entry.id.toString(),
            name = written.entry.name,
            replaced = written.existed,
        )
    }

    companion object {
        const val NAME = "write_knowledge"

        fun markup(name: String, replaced: Boolean): String =
            """<toolcall-write-knowledge name="${escapeAttribute(name)}" replaced="$replaced"></toolcall-write-knowledge>"""
    }
}
