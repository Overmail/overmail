package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.ai.ProposedTag
import es.jvbabi.overmail.server.ai.RevisionTool
import es.jvbabi.overmail.server.ai.ToolAnswer
import es.jvbabi.overmail.server.ai.readableBody
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.MemoryRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.domain.spam.toRuleFacts
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where the revision step's tools are actually carried out, see
 * [es.jvbabi.overmail.server.ai.REVISION_STEP].
 *
 * One of these per run, because it remembers things: which mails and which threads it has shown the
 * model, under which handle, and what it has changed since. That memory is the point of it. A model
 * is never handed an id it could get wrong or an id it was never shown -- it works with "M2" and
 * "T1", and a handle that was never handed out resolves to nothing, which makes "act on somebody
 * else's mail" a tool error rather than a possibility.
 *
 * The rule that the agent only touches what the agent made lives here, and it is checked at the
 * point of doing rather than asked for in the prompt: a tag a reader attached is not detached, a
 * thread a reader named is not renamed and nothing is added to it. Both refusals go back to the
 * model as errors, which is how it learns to stop asking.
 *
 * Everything is scoped to [owner]. Mails are looked up through their user's own listing, so a handle
 * for a mail of somebody else cannot exist in the first place.
 */
class RevisionDesk(
    private val owner: User,
    /** The mail the run is about. It is always the first handle, "M1". */
    private val mailId: Uuid,
    /** When it was sent, which is the cutoff for what counts as earlier mail. */
    private val sentAt: Instant,
    private val emails: EmailRepository,
    private val tagging: TagRepository,
    private val threading: ThreadRepository,
    private val matters: MailIdentifierRepository,
    private val remembering: MemoryRepository,
    /**
     * The core memories of this run under their handles, see [MemoryHandles].
     *
     * Handed in rather than loaded here, because the same lines went into the mail's own context for
     * every step before this one: one list of handles, or the model would be told about a "K2" that
     * the earlier prompt numbered differently.
     */
    private val memories: MemoryHandles = MemoryHandles(),
    /**
     * The tags the reading step proposed for this mail, not yet filed.
     *
     * Proposals rather than rows, and that is the whole reason this step decides them: the reading
     * step sees one mail, so it invents a word for what it sees -- and a mailbox filled that way
     * collects "Rechnung" next to "Rechnungen" next to "Beleg" until nothing can be found under any
     * of them. Here they can be held against what the mailbox already uses, see [similarTo], and
     * against the mail that came before this one. What is filed is what comes out of that.
     */
    private val proposals: List<ProposedTag> = emptyList(),
) {
    private val mailHandles = LinkedHashMap<String, Uuid>()
    private val threadHandles = LinkedHashMap<String, MailThread>()

    private val written = mutableListOf<String>()

    private val retagged = mutableSetOf<Uuid>()

    /**
     * What the run changed, in the reader's language, in the order it happened.
     *
     * For the wire and for nothing else: the changes themselves are rows in the database by the time
     * they are in here. Empty is the ordinary outcome -- most mail has no earlier company to be
     * consistent with.
     */
    val changes: List<String> get() = written.toList()

    /**
     * Whether the step got round to filing the tags of [mail].
     *
     * Asked about the mail being read, by the caller that has proposals it did not file itself: a
     * conversation that fell over, or one that simply never called `set_tags`, must not cost the
     * mail its tags altogether. The caller files them as they were proposed then -- worse than what
     * this step would have decided, better than nothing.
     */
    fun hasSetTagsOn(mail: Uuid): Boolean = mail in retagged

    /**
     * What the model is told about the mail before it starts: how it is filed right now, and what
     * to call it. Null where there is nothing to start from, and then the step is not worth running
     * at all.
     *
     * Read out of the database rather than passed in, and that matters: the steps before this one
     * have just written those rows, and what is worth revising is what is actually on the mail
     * rather than what some earlier step believed it put there.
     *
     * Null for the mail that carries neither a tag nor an identifier. There is no way to search for
     * its earlier company -- that is what the search goes on -- so a run could only ask for nothing
     * and be told nothing, at the price of a couple of requests. It is a common enough case to be
     * worth not paying for: a mail nothing could be read off is exactly the mail this step has no
     * handle on.
     */
    suspend fun briefing(): String? {
        val summary = summaryOf(mailId) ?: return null

        val thread = threadsOf(listOf(mailId))[mailId]
        // Off the mail's own record rather than off its thread: the first mail of a matter has no
        // thread yet -- that waits for the second, see [MatterFiling] -- and the identifier is the
        // best thing there is to search on, so reading it from the thread would lose it exactly
        // where it is most useful.
        val identifier = matters.identifierOf(mailId)

        if (summary.tags.isEmpty() && identifier == null && proposals.isEmpty()) return null

        // One read of the mailbox's vocabulary for the whole run: a private mailbox has tens of
        // tags, and every proposal is held against the same list.
        val vocabulary = tagging.usageForUser(owner)

        return buildString {
            appendLine("This is the mail you are working on:")
            appendLine(lineFor(summary, thread))
            appendLine()
            appendLine(
                "Its tags and its thread are what the mailbox now says about it. \"(agent)\" is " +
                    "yours to change, \"(user)\" is the reader's and stays."
            )
            appendLine(
                "The tags it already carries were read off the mail itself -- who sent it, the " +
                    "platform it came through, the number it names. Keep them: ${RevisionTool.SET_TAGS} " +
                    "replaces the whole list, so a tag you leave out of it comes off the mail."
            )

            if (proposals.isNotEmpty()) {
                appendLine()
                appendLine(
                    "These tags were proposed for it by a step that saw this mail and nothing else. " +
                        "They are NOT filed yet -- nothing carries them until you call " +
                        "${RevisionTool.SET_TAGS}. Behind each one is what the mailbox already uses " +
                        "for something like it:"
                )
                for (proposal in proposals) {
                    val known = similarTo(proposal.name, vocabulary)

                    appendLine(
                        "- \"${proposal.name}\" (${proposal.reason}) -- " +
                            if (known.isEmpty()) "nothing like it in the mailbox yet."
                            else "the mailbox already has: ${known.joinToString(", ") { it.asLine() }}"
                    )
                }
                appendLine(
                    "Where one of those existing labels means the same thing, file that one instead " +
                        "-- the same matter under two spellings is findable under neither. Where none " +
                        "of them does, the proposal becomes a new tag, and that is fine."
                )
            }
            if (memories.lines().isNotEmpty()) {
                appendLine()
                appendLine(
                    "What is known about the reader is listed with the mail above, one line each " +
                        "under a handle (K1, K2). Those are summaries: ${RevisionTool.RECALL} " +
                        "answers with what is behind one of them, and it is worth calling only " +
                        "where this mail turns on something the summary does not settle."
                )
            }
            appendLine()
            appendLine(
                "Where this mail teaches something about the reader that will still matter for the " +
                    "mail after next -- a course started, a job taken, a project running, a move " +
                    "being made -- write it down with ${RevisionTool.REMEMBER}. What this one mail " +
                    "is about is not that: a parcel, an invoice, a newsletter teach nothing about " +
                    "anybody. Most mail teaches nothing, and that is the ordinary case."
            )
            appendLine()
            appendLine(
                "Start by looking for earlier mail: call ${RevisionTool.FIND_MAILS} with the tags " +
                    "above" + (identifier?.let { ", and with the identifier \"$it\"" } ?: "") + "."
            )
        }.trim()
    }

    /** Carries out one call. An unknown name, bad arguments and a refusal all read as errors. */
    suspend fun run(tool: String, arguments: JsonObject): ToolAnswer = when (tool) {
        RevisionTool.FIND_MAILS -> findMails(arguments)
        RevisionTool.READ_MAIL -> readMail(arguments)
        RevisionTool.FIND_TAGS -> findTags(arguments)
        RevisionTool.SET_TAGS -> setTags(arguments)
        RevisionTool.CREATE_THREAD -> createThread(arguments)
        RevisionTool.ADD_TO_THREAD -> addToThread(arguments)
        RevisionTool.RENAME_THREAD -> renameThread(arguments)
        RevisionTool.RECALL -> recall(arguments)
        RevisionTool.REMEMBER -> remember(arguments)
        RevisionTool.CLOSE_MEMORY -> closeMemory(arguments)
        else -> failed("There is no tool called \"$tool\".")
    }

    private suspend fun findMails(arguments: JsonObject): ToolAnswer {
        val names = arguments.strings("tags")
        val identifier = arguments.string("identifier")

        if (names.isEmpty() && identifier == null) {
            return failed("Give `tags`, an `identifier`, or both -- otherwise there is nothing to look for.")
        }

        val found = LinkedHashSet<Uuid>()

        // The matter first, where there is one: mails carrying the same identifier are the same
        // affair, which is a stronger claim than sharing a tag with it.
        //
        // Both ways round, because they answer slightly different questions. The mails that name the
        // string are the matter itself, and they are findable before anything has grouped them --
        // which is the whole point of recording it per mail. The mails in its thread are what
        // somebody has grouped since, and can include one that never wrote the number down.
        if (identifier != null) {
            found += matters.mailsWith(owner, identifier, before = sentAt, limit = MAX_FOUND)

            val thread = threading.findByIdentifier(owner, identifier)
            if (thread != null) {
                found += emails
                    .getSummariesForUser(owner, limit = MAX_FOUND, before = sentAt, threadId = thread.id)
                    .first().mails.map { it.id }
            }
        }

        if (names.isNotEmpty()) {
            found += tagging.mailsUnderTags(owner, names, before = sentAt, limit = MAX_FOUND)
        }

        found -= mailId

        if (found.isEmpty()) {
            return ToolAnswer(
                "No earlier mail under " +
                    listOfNotNull(
                        names.takeIf { it.isNotEmpty() }?.joinToString(", ") { "\"$it\"" },
                        identifier?.let { "identifier \"$it\"" },
                    ).joinToString(" or ") +
                    ". Nothing came before this mail, so there is nothing to make consistent with it."
            )
        }

        val ids = found.take(MAX_FOUND)
        val summaries = summariesOf(ids)
        val threads = threadsOf(ids)

        return ToolAnswer(
            buildString {
                val count = summaries.size
                appendLine("$count earlier ${if (count == 1) "mail" else "mails"}, newest first:")
                for (summary in summaries) appendLine(lineFor(summary, threads[summary.id]))
                appendLine()
                append("Read the ones that might be the same matter before changing anything.")
            }
        )
    }

    private suspend fun readMail(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("mail") ?: return failed("`mail` is missing.")
        val id = mailHandles[handle.uppercase()] ?: return unknownMail(handle)

        val summary = summaryOf(id) ?: return failed("$handle is gone from the mailbox.")

        // The whole mail, not the listing: this is the one tool that costs real tokens, and it is
        // called because a subject line was not enough. Cut down the same way the steps cut a mail
        // down, and then cut again -- a conversation carries every mail it has read in every
        // further request.
        val email = emails.getById(id).first()
            ?: return failed("$handle could not be read.")

        if (email.imapAccount.user.id != owner.id) return unknownMail(handle)

        val body = readableBody(email.toRuleFacts().body)
            .let { if (it.length > MAX_BODY) it.take(MAX_BODY) + "\n\n[... shortened ...]" else it }

        return ToolAnswer(
            buildString {
                appendLine(lineFor(summary, threadsOf(listOf(id))[id]))
                appendLine()
                append(body.ifBlank { "(this mail carries no text)" })
            }
        )
    }

    private suspend fun findTags(arguments: JsonObject): ToolAnswer {
        val names = arguments.strings("names")
        if (names.isEmpty()) return failed("`names` is empty. Give the labels you are thinking of.")

        val vocabulary = tagging.usageForUser(owner)

        return ToolAnswer(
            buildString {
                for (name in names) {
                    val known = similarTo(name, vocabulary)

                    appendLine(
                        "\"$name\": " +
                            if (known.isEmpty()) "nothing like it in the mailbox yet -- filing it " +
                                "makes a new tag."
                            else known.joinToString(", ") { it.asLine() }
                    )
                }
                append(
                    "A label that means the same as one of these is that one. Only a meaning the " +
                        "mailbox has no word for is worth a new tag."
                )
            }
        )
    }

    private suspend fun setTags(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("mail") ?: return failed("`mail` is missing.")
        val id = mailHandles[handle.uppercase()] ?: return unknownMail(handle)
        val reason = arguments.string("reason")
            ?: return failed("`reason` is missing. Every change is stored with why it was made.")

        val wanted = arguments.strings("tags").map { it.trim() }.filter { it.isNotEmpty() }

        if (wanted.size > MAX_TAGS) {
            return failed("$handle would carry ${wanted.size} tags. At most $MAX_TAGS.")
        }
        val tooLong = wanted.firstOrNull { it.length > MAX_TAG_LENGTH }
        if (tooLong != null) {
            return failed("\"$tooLong\" is a sentence rather than a tag.")
        }

        val summary = summaryOf(id) ?: return failed("$handle is gone from the mailbox.")

        val keep = wanted.map { it.lowercase() }.toSet()
        val carried = summary.tags.associateBy { it.tag.name.lowercase() }

        val removed = mutableListOf<String>()
        val refused = mutableListOf<String>()

        for ((name, filing) in carried) {
            if (name in keep) continue

            // Theirs stays. Said back rather than swallowed, so the model stops proposing it.
            if (!filing.createdByAgent) {
                refused += filing.tag.name
                continue
            }

            if (tagging.detach(id, filing.tag, onlyIfAgentAttached = true)) removed += filing.tag.name
        }

        val added = mutableListOf<String>()
        for (name in wanted) {
            if (name.lowercase() in carried) continue

            val tag = tagging.findOrCreate(owner, name, createdByAgent = true)
            if (tagging.attach(id, tag, reason.trim(), createdByAgent = true) != null) added += tag.name
        }

        if (added.isNotEmpty() || removed.isNotEmpty()) {
            written += "Tags von „${summary.subject.ifBlank { "(ohne Betreff)" }}“: " +
                (wanted.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "keine")
        }

        // Said to have been set even where nothing changed: the question the caller asks is whether
        // this step decided about the mail's tags, and deciding to leave them is deciding.
        retagged += id

        val nowCarried = summaryOf(id)?.tags.orEmpty()

        return ToolAnswer(
            buildString {
                append("$handle now: ${nowCarried.asTagList()}")
                if (added.isNotEmpty()) append(" · added: ${added.joinToString(", ")}")
                if (removed.isNotEmpty()) append(" · removed: ${removed.joinToString(", ")}")
                if (refused.isNotEmpty()) {
                    append(" · left alone because the reader attached them: ${refused.joinToString(", ")}")
                }
                if (added.isEmpty() && removed.isEmpty()) append(" · nothing changed")
            }
        )
    }

    private suspend fun createThread(arguments: JsonObject): ToolAnswer {
        val title = arguments.string("title")?.trim()
        if (title.isNullOrEmpty()) return failed("`title` is missing.")
        val reason = arguments.string("reason")
            ?: return failed("`reason` is missing. Every change is stored with why it was made.")

        val handles = arguments.strings("mails")
        val ids = handles.map { it.uppercase() }.map { mailHandles[it] ?: return unknownMail(it) }

        if (ids.distinct().size < 2) {
            return failed(
                "A thread needs at least two mails: one mail on its own is not a matter several " +
                    "mails belong to. Give the mails that are the same affair."
            )
        }

        val thread = threading.create(owner, title, createdByAgent = true)
        val handle = handleFor(thread)

        for (id in ids.distinct()) {
            threading.attach(id, thread, reason.trim(), createdByAgent = true)
        }

        written += "Stapel „${thread.title}“ angelegt mit ${ids.distinct().size} E-Mails"

        return ToolAnswer("$handle \"${thread.title}\" opened, with ${handles.joinToString(", ")} in it.")
    }

    private suspend fun addToThread(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("thread")?.uppercase() ?: return failed("`thread` is missing.")
        val thread = threadHandles[handle] ?: return unknownThread(handle)
        val reason = arguments.string("reason")
            ?: return failed("`reason` is missing. Every change is stored with why it was made.")

        if (!thread.createdByAgent) return theirThread(handle, thread)

        val handles = arguments.strings("mails")
        val ids = handles.map { it.uppercase() }.map { mailHandles[it] ?: return unknownMail(it) }
        if (ids.isEmpty()) return failed("`mails` is empty.")

        var put = 0
        for (id in ids.distinct()) {
            if (threading.attach(id, thread, reason.trim(), createdByAgent = true) != null) put++
        }

        if (put > 0) written += "$put E-Mail(s) dem Stapel „${thread.title}“ zugeordnet"

        return ToolAnswer(
            if (put == 0) "$handle already held all of them; nothing changed."
            else "$put of ${ids.distinct().size} put into $handle \"${thread.title}\"."
        )
    }

    private suspend fun renameThread(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("thread")?.uppercase() ?: return failed("`thread` is missing.")
        val thread = threadHandles[handle] ?: return unknownThread(handle)
        val title = arguments.string("title")?.trim()
        if (title.isNullOrEmpty()) return failed("`title` is missing.")

        if (!thread.createdByAgent) return theirThread(handle, thread)
        if (title == thread.title) return ToolAnswer("$handle is already called that; nothing changed.")

        val renamed = threading.rename(thread, title)
            ?: return failed("$handle is gone and could not be renamed.")

        // The registry keeps the name it now has, so a later line about it does not read the old one.
        threadHandles[handle] = renamed
        written += "Stapel umbenannt: „${thread.title}“ → „${renamed.title}“"

        return ToolAnswer("$handle renamed from \"${thread.title}\" to \"${renamed.title}\".")
    }

    private suspend fun recall(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("memory")?.uppercase() ?: return failed("`memory` is missing.")
        val memory = memories[handle] ?: return unknownMemory(handle)

        // Against the mail's own moment, like the summaries were: a detail about a job that had
        // ended before this mail arrived explains nothing about it.
        val details = remembering.detailsOf(memory.id, at = sentAt)

        return ToolAnswer(
            buildString {
                appendLine("$handle · ${memory.topic ?: "?"} · ${memory.content}")

                if (details.isEmpty()) {
                    append("Nothing further is known about it. What you were shown is all there is.")
                } else {
                    for (detail in details) {
                        val period = detail.periodAsText().takeIf { it.isNotEmpty() }
                            ?.let { " ($it)" } ?: ""

                        appendLine("- ${detail.content}$period")
                    }
                }
            }.trim()
        )
    }

    private suspend fun remember(arguments: JsonObject): ToolAnswer {
        val content = arguments.string("content")?.trim()
        if (content.isNullOrEmpty()) return failed("`content` is missing.")
        if (content.length > MAX_MEMORY) {
            return failed(
                "That is ${content.length} characters. A memory is one line -- what is longer " +
                    "belongs under a topic as a detail, one line at a time."
            )
        }

        val parentHandle = arguments.string("of")?.uppercase()
        val parent = parentHandle?.let { memories[it] ?: return unknownMemory(it) }

        // A core memory is shown for every mail it covers, so it has to say what it is about. A
        // detail is only ever read through its parent, which is where its topic comes from.
        val topic = arguments.string("topic")?.trim()
        if (parent == null && topic.isNullOrEmpty()) {
            return failed(
                "`topic` is missing. Something new needs the word it is about -- \"Studium\", " +
                    "\"Arbeit\" -- or give `of` to hang it under something already known."
            )
        }

        // A date that is not one is refused rather than dropped: a memory with a beginning nobody
        // wrote goes missing for exactly the mail it would have explained.
        val from = arguments.dayOrNull("from") ?: return badDay("from")
        val to = arguments.dayOrNull("to") ?: return badDay("to")

        val written = remembering.remember(
            user = owner,
            topic = if (parent == null) topic else null,
            content = content,
            parentId = parent?.id,
            relevantFrom = from.getOrNull(),
            relevantTo = to.getOrNull(),
            // Where it was learned, which is the provenance a reader wants before believing it.
            learnedFromEmailId = mailId,
            createdByAgent = true,
        )

        if (parent == null) {
            val handle = memories.register(written)
            this.written += "Gemerkt ($handle ${written.topic}): ${written.content}"

            return ToolAnswer("$handle · ${written.topic} · ${written.content} -- written down.")
        }

        this.written += "Gemerkt zu ${parent.topic}: ${written.content}"

        return ToolAnswer("Added to $parentHandle \"${parent.topic}\": ${written.content}")
    }

    private suspend fun closeMemory(arguments: JsonObject): ToolAnswer {
        val handle = arguments.string("memory")?.uppercase() ?: return failed("`memory` is missing.")
        val memory = memories[handle] ?: return unknownMemory(handle)
        val on = arguments.dayOrNull("on")?.getOrNull() ?: return badDay("on")

        if (!memory.createdByAgent) {
            return failed(
                "$handle was written by the reader about their own life, so it is not yours to end."
            )
        }

        val closed = remembering.close(memory.id, on, onlyIfByAgent = true)
            ?: return failed("$handle could not be ended.")

        // The registry keeps it as it now reads, so a later line about it does not show it as open.
        memories.register(closed)
        written += "Beendet (${closed.topic}): ${closed.content}, ${closed.periodAsText()}"

        return ToolAnswer("$handle ends on ${on.asDate()}: ${closed.content}")
    }

    /** One mail as the model sees it: its handle, when it came, who from, and how it is filed. */
    private fun lineFor(summary: MailSummary, thread: MailThread?): String {
        val handle = handleFor(summary.id)
        val sender = summary.sender.name?.takeIf { it.isNotBlank() } ?: summary.sender.address
        val filed = thread?.let { "${handleFor(it)} \"${it.title}\" ${it.byWhom()}" } ?: "none"

        return "$handle · ${summary.sent.asDate()} · $sender · " +
            "\"${summary.subject.ifBlank { "(no subject)" }}\" · " +
            "tags: ${summary.tags.asTagList()} · thread: $filed"
    }

    private fun handleFor(id: Uuid): String {
        mailHandles.entries.firstOrNull { it.value == id }?.let { return it.key }

        val handle = "M${mailHandles.size + 1}"
        mailHandles[handle] = id

        return handle
    }

    private fun handleFor(thread: MailThread): String {
        threadHandles.entries.firstOrNull { it.value.id == thread.id }?.let { return it.key }

        val handle = "T${threadHandles.size + 1}"
        threadHandles[handle] = thread

        return handle
    }

    private suspend fun summaryOf(id: Uuid): MailSummary? = summariesOf(listOf(id)).firstOrNull()

    private suspend fun summariesOf(ids: Collection<Uuid>): List<MailSummary> {
        if (ids.isEmpty()) return emptyList()

        // Through the user's own listing, which is what makes a mail of somebody else unfindable
        // rather than merely unasked for.
        return emails.getSummariesForUser(owner, limit = ids.size, ids = ids).first().mails
    }

    private suspend fun threadsOf(ids: Collection<Uuid>): Map<Uuid, MailThread> =
        threading.threadsOf(owner, ids)

    private fun unknownMail(handle: String) = failed(
        "There is no mail \"$handle\". Use a handle from a listing -- \"M1\" is the mail being read."
    )

    private fun unknownMemory(handle: String) = failed(
        "There is nothing known about the reader under \"$handle\". Use a handle from the list you " +
            "were given with the mail."
    )

    private fun badDay(name: String) = failed(
        "`$name` is not a date. Write YYYY-MM-DD, YYYY-MM or YYYY, or leave it out where the mail " +
            "does not say."
    )

    private fun unknownThread(handle: String) = failed(
        "There is no thread \"$handle\". Use a handle from a listing."
    )

    private fun theirThread(handle: String, thread: MailThread) = failed(
        "$handle \"${thread.title}\" was made by the reader, so it is not yours to change. Leave it as it is."
    )

    private fun failed(why: String) = ToolAnswer(why, failed = true)
}

/** How many earlier mails one search hands back. Past a dozen the model is reading a mailbox. */
private const val MAX_FOUND = 12

/** How much of one mail a read hands over. Every read stays in the conversation to the end. */
private const val MAX_BODY = 3_000

/**
 * Where a memory stops being a line and becomes a note. Anything longer belongs under a topic as a
 * detail, which is read on purpose rather than in front of every mail.
 */
private const val MAX_MEMORY = 200

/**
 * The most tags a revision may leave on a mail.
 *
 * Higher than the tagging step's own limit, and it has to be: that step proposes three or four
 * labels for what a mail is about, while this number covers everything a mail ends up carrying --
 * the organisation, the platform, the repository, the identifier, and what it is about on top. A cap
 * of four here would force the model to throw away facts to make room for opinions.
 */
private const val MAX_TAGS = 8

/** Where a tag stops being a label. */
private const val MAX_TAG_LENGTH = 40

/** Who filed something, in the two words the model is told to read. */
private fun MailThread.byWhom(): String = if (createdByAgent) "(agent)" else "(user)"

/** Tags as the model reads them: the name, and whose filing it is. */
private fun List<EmailTag>.asTagList(): String =
    if (isEmpty()) "none"
    else joinToString(", ") { "${it.tag.name} ${if (it.createdByAgent) "(agent)" else "(user)"}" }

/** The day a mail came, which is all the precision a listing needs. */
private fun Instant.asDate(): String = toString().take(10)

/** One string argument, or null where the model left it out or wrote something else. */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNullIfBlank()

/**
 * A list-of-strings argument.
 *
 * A bare string counts as a list of one, because that is what a model writes when it has one thing
 * to say: `"tags": "Rechnung"` where the schema asked for an array. Refusing that would cost a round
 * to say something the tool already understood.
 */
private fun JsonObject.strings(name: String): List<String> = when (val value = this[name]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfBlank() }
    is JsonPrimitive -> listOfNotNull(value.contentOrNullIfBlank())
    else -> emptyList()
}

private fun JsonPrimitive.contentOrNullIfBlank(): String? = content.takeIf { it.isNotBlank() }

/**
 * A day argument, as the moment that day begins in UTC.
 *
 * Three answers rather than two, which is what the outer null is for: a success holding null means
 * the model left the argument out, and that is fine -- plenty of what a mail teaches has no date in
 * it. The outer null means it wrote something that is not a day, and that has to be said back rather
 * than quietly filed as "no date".
 *
 * Three forms, because a mail states a date in whatever precision it feels like and so does a model
 * reading it: `2024-10-07`, `2024-10`, `2024`. The coarser two resolve to the first day of what they
 * name, which is the honest reading of "seit Oktober 2024" -- and these days are only ever compared
 * against the send times of mails, where a fortnight either way changes nothing.
 *
 * UTC and not the reader's zone: the other end of every one of those comparisons carries no zone of
 * its own either.
 */
private fun JsonObject.dayOrNull(name: String): Result<Instant?>? {
    val written = string(name)?.trim() ?: return Result.success(null)

    val (year, month, dayOfMonth) = when {
        FULL_DAY.matches(written) -> written.split('-').let {
            Triple(it[0].toInt(), it[1].toInt(), it[2].toInt())
        }
        YEAR_MONTH.matches(written) -> written.split('-').let {
            Triple(it[0].toInt(), it[1].toInt(), 1)
        }
        YEAR.matches(written) -> Triple(written.toInt(), 1, 1)
        else -> return null
    }

    return runCatching {
        LocalDate(year, month, dayOfMonth).atStartOfDayIn(TimeZone.UTC)
    }.getOrNull()?.let { Result.success(it) }
}

private val FULL_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
private val YEAR_MONTH = Regex("""\d{4}-\d{2}""")
private val YEAR = Regex("""\d{4}""")
