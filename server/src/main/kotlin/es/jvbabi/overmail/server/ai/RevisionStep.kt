package es.jvbabi.overmail.server.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType

/** The tools of [REVISION_STEP], by the names the model calls them. */
object RevisionTool {
    const val FIND_MAILS = "find_mails"
    const val READ_MAIL = "read_mail"
    const val FIND_TAGS = "find_tags"
    const val SET_TAGS = "set_tags"
    const val CREATE_THREAD = "create_thread"
    const val ADD_TO_THREAD = "add_to_thread"
    const val RENAME_THREAD = "rename_thread"
    const val RECALL = "recall"
    const val REMEMBER = "remember"
    const val CLOSE_MEMORY = "close_memory"
}

private val MAIL_HANDLE = ToolParameterDescriptor(
    name = "mail",
    description = "A mail, by the handle it was listed under: \"M1\" is the mail being read now.",
    type = ToolParameterType.String,
)

private val MAIL_HANDLES = ToolParameterDescriptor(
    name = "mails",
    description = "Mails, by the handles they were listed under: [\"M1\", \"M3\"].",
    type = ToolParameterType.List(ToolParameterType.String),
)

private val THREAD_HANDLE = ToolParameterDescriptor(
    name = "thread",
    description = "A thread, by the handle it was listed under: \"T1\".",
    type = ToolParameterType.String,
)

private val MEMORY_HANDLE = ToolParameterDescriptor(
    name = "memory",
    description = "One of the things known about the reader, by the handle it was listed under: \"K1\".",
    type = ToolParameterType.String,
)

private val REASON = ToolParameterDescriptor(
    name = "reason",
    description = "Why, in one short German sentence. It is stored with the change and shown to " +
        "the reader, so write it for them and not for yourself.",
    type = ToolParameterType.String,
)

/**
 * What the step may do to the mailbox.
 *
 * Six tools, in two groups that read very differently. The first two look: they answer with what is
 * in the mailbox and change nothing, and a step that only ever calls those has cost a few requests
 * and done no harm. The other four write, and each of them is one row a reader will see -- which is
 * why every one of them takes a reason, and why none of them can touch anything a reader made
 * themselves.
 *
 * Handles rather than ids. A mail is "M1", a thread is "T1", a thing known about the reader is "K1",
 * all handed out by whatever listed them, and there are no UUIDs anywhere in this conversation: a
 * model asked to copy thirty-six characters of hexadecimal gets one wrong eventually, and a wrong
 * handle is a tool error while a wrong id could be somebody else's mail.
 *
 * The last three are about the reader rather than about the mailbox. They are here because they
 * serve the same decisions: a mail is filed well or badly depending on whether whoever files it
 * knows that "TU" is where the reader studies -- and the only moment anybody learns that is while
 * reading a mail that says so.
 */
val REVISION_TOOLS: List<ToolDescriptor> = listOf(
    ToolDescriptor(
        name = RevisionTool.FIND_MAILS,
        description = "Find earlier mails of this mailbox that may belong with the one being read " +
            "-- mails filed under any of the given tags, or mails about the matter the given " +
            "identifier names. Only mails that arrived before this one. Answers with a numbered " +
            "list: handle, date, sender, subject, the tags each one carries and the thread it " +
            "sits in. Nothing is changed by looking.",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "tags",
                description = "Tag names to look under, as they are written: [\"Bewerbung\"].",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
            ToolParameterDescriptor(
                name = "identifier",
                description = "An identifier the matter goes by, e.g. \"RE-2024-00123\".",
                type = ToolParameterType.String,
            ),
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.READ_MAIL,
        description = "Read one of the mails that were listed: its sender, its subject and its " +
            "text, shortened the same way the mail being read was. Look before you retag: a " +
            "subject line is not enough to tell what a mail is about.",
        requiredParameters = listOf(MAIL_HANDLE),
    ),
    ToolDescriptor(
        name = RevisionTool.FIND_TAGS,
        description = "Ask what the mailbox already uses for a label you are thinking of. Answers " +
            "with the existing tags closest to each name and how many mails carry them -- " +
            "\"Rechnungen\" comes back as \"Rechnung (42 Mails)\". Use it before filing a label " +
            "you have not seen in a listing: the same matter under two spellings is findable under " +
            "neither. Nothing is changed by asking.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "names",
                description = "The labels you are considering: [\"Bewerbung Musterfirma\"].",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.SET_TAGS,
        description = "Set what one mail is filed under: the tags given are what it carries " +
            "afterwards, so name the ones it already has as well unless you mean to take them off. " +
            "Tags it has that are not in the list are taken off, tags in the list that it does not " +
            "have are attached. Tags a reader attached themselves are never touched -- they stay " +
            "whatever you send, and the answer says which those were.",
        requiredParameters = listOf(
            MAIL_HANDLE,
            ToolParameterDescriptor(
                name = "tags",
                description = "Everything the mail is to be filed under afterwards, in German, " +
                    "most general first: [\"Rechnung\", \"Hosting\"]. An empty list files it " +
                    "under nothing.",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
            REASON,
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.CREATE_THREAD,
        description = "Open a thread for a matter several mails belong to, and put those mails in " +
            "it. Only worth doing where the mails really are one matter -- a thread of mails that " +
            "merely resemble each other is worse than no thread.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "title",
                description = "What the matter is called, in German, specific enough to tell it " +
                    "from the next one of its kind: \"Bewerbung Musterfirma\", not \"Bewerbung\".",
                type = ToolParameterType.String,
            ),
            MAIL_HANDLES,
            REASON,
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.ADD_TO_THREAD,
        description = "Put mails into a thread that already exists. Only into threads that were " +
            "opened by the agent -- a thread a reader made is theirs, and adding to it is refused.",
        requiredParameters = listOf(THREAD_HANDLE, MAIL_HANDLES, REASON),
    ),
    ToolDescriptor(
        name = RevisionTool.RECALL,
        description = "Ask what else is known about one of the things listed about the reader. The " +
            "lines you were given are summaries; this answers with the detail behind one of them. " +
            "Worth calling when the mail turns on something you only half know -- which course, " +
            "which employer, what the project is -- and not worth calling otherwise. Nothing is " +
            "changed by asking.",
        requiredParameters = listOf(MEMORY_HANDLE),
    ),
    ToolDescriptor(
        name = RevisionTool.REMEMBER,
        description = "Write down something about the reader that this mail taught you and that " +
            "will still matter for the mail after next: what they study, where they work, a " +
            "project they are running, a club they are in, a move they are making. Not the " +
            "contents of this mail -- a parcel arriving, an invoice being due and a newsletter " +
            "going out teach nothing about anybody. Nothing that is already in the list, and " +
            "nothing you inferred: only what the mail says.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "content",
                description = "The one line, in German, as somebody would say it: \"Studiert " +
                    "Informatik an der TU Dresden\". Short: it is shown for every mail it covers.",
                type = ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "topic",
                description = "What it is about, in a word or two: \"Studium\", \"Arbeit\", " +
                    "\"Umzug\". Give it for a new thing; leave it out when adding a detail to one " +
                    "that is already listed.",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "of",
                description = "The handle of the thing this is a detail of: \"K1\". Leave it out " +
                    "for something new. A detail is only ever read when somebody asks about its " +
                    "topic, so this is where everything belongs that is worth knowing but not " +
                    "worth reading before every mail.",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "from",
                description = "The day it started, as YYYY-MM-DD, YYYY-MM or YYYY -- and only " +
                    "where the mail says. Leave it out otherwise: an invented beginning makes the " +
                    "memory go missing for exactly the mail it would have explained.",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "to",
                description = "The day it stopped, same format, for something already over when " +
                    "you learned of it. Leave it out for anything still going on.",
                type = ToolParameterType.String,
            ),
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.CLOSE_MEMORY,
        description = "End something known about the reader as of a day, where this mail says it is " +
            "over -- a degree finished, a job left, a flat given up. It is kept rather than " +
            "deleted: the mail of its own years is still read against it, it just stops being " +
            "shown for mail after that day. Only for what the agent wrote itself; what the reader " +
            "wrote about their own life is theirs.",
        requiredParameters = listOf(
            MEMORY_HANDLE,
            ToolParameterDescriptor(
                name = "on",
                description = "The day it ended, as YYYY-MM-DD, YYYY-MM or YYYY.",
                type = ToolParameterType.String,
            ),
        ),
    ),
    ToolDescriptor(
        name = RevisionTool.RENAME_THREAD,
        description = "Rename a thread that was opened by the agent, where its name turned out to " +
            "be too general for what is in it -- \"Bewerbung\" for what is only ever the one " +
            "application. Refused for a thread a reader named themselves.",
        requiredParameters = listOf(
            THREAD_HANDLE,
            ToolParameterDescriptor(
                name = "title",
                description = "The new name, in German.",
                type = ToolParameterType.String,
            ),
            REASON,
        ),
    ),
)

/**
 * The step that looks at what the mailbox already holds and tidies up after the tagging.
 *
 * Every step before this one sees one mail and nothing else, which is what makes them cheap and
 * repeatable -- and also what they cannot do anything about: the second mail about an application
 * gets the same tags as the first only by luck, and neither of them knows the other exists. This
 * step is the one that gets to look. It searches for the earlier mail on the same tags and the same
 * identifier, reads what it finds, and then has three things it may do: put right the tags on any
 * of those mails, put them together into a thread, or rename a thread whose name turned out to be
 * too general for what ended up in it.
 *
 * A conversation rather than an answer, because none of that can be decided in one shot: what to do
 * depends on what the search turns up, and the search depends on what the mail is. So it is given
 * tools and left to work, see [MailToolStep], and what it did is in the mailbox rather than in a
 * return value.
 *
 * It may only touch what the agent itself made. Tags a reader attached stay; threads a reader named
 * keep their names and their contents. That is not a safety rail bolted on afterwards -- it is what
 * makes a step like this allowed to exist at all: a model that can undo a person's filing is a model
 * nobody would let near their mailbox, and the rule is enforced by the tools rather than asked for
 * in the prompt.
 */
val REVISION_STEP = MailToolStep(
    id = "revision",
    systemPrompt = """
        You keep one person's private mailbox in order. You are given a mail that has just been
        read and filed, and your job is to look at what the mailbox already holds about the same
        matter and put right what the first reading could not know.

        The mailbox holds what its owner received as well as what they wrote, so they can be the
        sender just as well as a recipient. Which one they are is stated as "Direction": take it
        from there rather than working it out from the addresses.

        Work in this order, and stop as soon as there is nothing left worth doing:

        1. Look for what came before. Call find_mails with the tags this mail was filed under, and
           with its identifier where it has one. Most mails have no earlier company at all -- an
           empty result is the ordinary case and the end of the job.
           Search on the tags that say what the mail is about, not on the ones that name who sent
           it: "GitHub", "Sparkasse" or a person's name will find everything they ever sent, and a
           sender is not a matter. The identifier is the best thing to search on where there is one.
        2. Read the ones that might belong. A subject line and a sender are not enough to tell
           whether two mails are the same matter; read_mail is cheap and being wrong here is not.
        3. File the mail's tags, and put right the ones on the earlier mails while you are there.
           The tags proposed for this mail are not filed yet -- a step that saw this one mail
           thought of them, and it is your job to decide what actually goes on. Three things to
           weigh, in this order:
           - Is the mailbox already using a word for this? The proposals come with what it has, and
             find_tags answers for any label you think of yourself. "Rechnungen" where the mailbox
             has "Rechnung" is not a second tag, it is the same one spelled again, and a mailbox
             that collects those is searchable under none of them. Reuse the existing label whenever
             it means the same thing -- including when it is spelled a little differently.
           - Does the meaning really match? A word that looks close is not always the same thing:
             "Rechnung" is not "Mahnung", "Bewerbung" is not "Job" if the mailbox uses "Job" for
             payslips. Where no existing label means what this mail is about, file the proposal and
             a new tag is made -- that is what it is for.
           - Is what the earlier mails carry consistent with it? The same matter filed under
             "Bewerbung" here and "Job" there is two categories where the reader wanted one. Set the
             earlier mail's tags too where that is what is wrong.
           set_tags replaces the whole list of a mail, so name everything it is to carry afterwards
           -- the tags it already has included. Those were read off the mail itself: who sent it,
           the platform it came through, the number it names. They are facts about the mail rather
           than opinions about it, and leaving one out of the list takes it off.
           Tags stay German, ordinary, one to three words. Keep at least one general one where the
           mail allows it -- "Studium", "Schule", "Wohnung", "Rechnung", "Newsletter" are the words a
           reader actually browses by; a mailbox of nothing but specific labels is a mailbox with no
           categories at all.
        4. Put the matter together where there is one. Mails that are steps of the same affair --
           an application and the answer to it, an order and its invoice and its parcel, a
           conversation a platform numbers -- belong in one thread. Mails that are merely the same
           kind of thing do not: "Newsletter" is not a matter, and a thread of everything tagged
           "Rechnung" helps nobody.
        5. Learn about the reader, where this mail actually teaches something. You were given what
           the mailbox knows about them -- one line each, only the things that were true when this
           mail was sent. Two things to do with it:
           - recall the detail behind a line when the mail turns on it and the summary is not
             enough: which course, which employer, what the project is. Do not guess at those.
           - remember what the mail teaches that will still matter for the mail after next: a
             course started, a job taken, a project running, a move being made, a club joined. Give
             it a beginning only where the mail says one. Something already in the list is not
             remembered again, and something over is closed rather than left standing.
           What is not a memory: what this one mail is about. A parcel arriving, an invoice falling
           due, a newsletter going out -- those are the mail, not the reader. Nor is anything you
           worked out rather than read.

        6. Rename a thread whose name is too general for what is in it. A thread called "Bewerbung"
           that only ever holds the one application is better called "Bewerbung Musterfirma". Only
           threads the agent opened, and only where the name is genuinely wrong for the contents --
           not to make it prettier.

        What you may not do: touch anything the reader made themselves. Their tags stay on their
        mails, their threads keep their names and their contents, and what they wrote about their own
        life is not yours to end. The tools refuse it anyway, and
        every listing tells you which is which -- "(agent)" is yours, "(user)" is theirs.

        Rules for the whole job:
        - Change something only where it is an improvement a reader would recognise. Doing nothing
          is a good outcome -- except for the proposed tags, which nothing carries until you file
          them: those are decided every run, even when the decision is to file them as proposed.
        - Never guess at a mail you have not read.
        - Every change carries a reason, in one short German sentence, and it is written for the
          reader: what the change is, and what in the mail says so.
        - Do not repeat a call that has already been answered, and do not undo what you just did.
        - When there is nothing left to do, answer with one sentence saying what you changed, or
          that you changed nothing. Do not call any more tools then.
    """.trimIndent(),
    tools = REVISION_TOOLS,
    tier = ModelTier.CAPABLE,
    maxOutputTokens = 900,
)
