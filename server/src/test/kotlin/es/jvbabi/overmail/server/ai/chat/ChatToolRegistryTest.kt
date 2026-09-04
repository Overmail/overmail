package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.ai.chat.tools.CreateLabelTool
import es.jvbabi.overmail.server.ai.chat.tools.LabelEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.WriteKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchEmailsTool
import es.jvbabi.overmail.server.ai.chat.tools.UnlabelEmailTool
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The registry the agent runs with, built the same way here. What is under test is the wiring:
 * a tool that reads a mail has to leave its mark in the answer.
 */
class ChatToolRegistryTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:chat-tool-registry;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    @Test
    fun `a mail the agent reads is written into the stream`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        stream.append("Ich schaue nach.")

        val registry = registry(fixture.userId, stream)
        val tool = registry.tools.filterIsInstance<ReadEmailTool>().single()

        tool.execute(ReadEmailTool.Args(emailId = fixture.emailId.toString()))

        assertEquals(
            "Ich schaue nach.\n\n"
                + """<toolcall-read-email emailId="${fixture.emailId}" avatarUrl="" avatarPadding="" subject="Invoice 42"></toolcall-read-email>""",
            stream.snapshot().content,
        )
    }

    @Test
    fun `a search the agent runs is written into the stream`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()

        val registry = registry(fixture.userId, stream)
        val tool = registry.tools.filterIsInstance<SearchEmailsTool>().single()

        tool.execute(SearchEmailsTool.Args(subject = "Invoice"))

        assertEquals(
            """<toolcall-search-emails subject="Invoice" sender=""></toolcall-search-emails>""",
            stream.snapshot().content,
        )
    }

    @Test
    fun `a mail that was not read leaves nothing behind`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()

        val registry = registry(fixture.userId, stream)
        val tool = registry.tools.filterIsInstance<ReadEmailTool>().single()

        tool.execute(ReadEmailTool.Args(emailId = Uuid.random().toString()))

        assertTrue(stream.snapshot().content.isEmpty())
    }

    @Test
    fun `a label the agent makes is written into the stream, and the one that was there is not`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        val tool = registry(fixture.userId, stream).tools.filterIsInstance<CreateLabelTool>().single()

        val created = tool.execute(CreateLabelTool.Args(name = "  Uni   Kram ")) as CreateLabelTool.Result.Label
        // Normalized, coloured here, and announced in the answer.
        assertEquals("Uni Kram", created.name)
        assertTrue(Regex("^#[0-9A-Fa-f]{6}$").matches(created.color))
        assertTrue(!created.existed)
        assertEquals(CreateLabelTool.markup(Uuid.parse(created.labelId)), stream.snapshot().content)

        // The same name again is that label, not a second one -- and nothing new to show.
        val again = tool.execute(CreateLabelTool.Args(name = "uni kram")) as CreateLabelTool.Result.Label
        assertEquals(created.labelId, again.labelId)
        assertTrue(again.existed)
        assertEquals(1, database.query { Labels.selectAll().where { Labels.owner eq fixture.userId }.count().toInt() })
        assertEquals(CreateLabelTool.markup(Uuid.parse(created.labelId)), stream.snapshot().content)
    }

    @Test
    fun `a label goes on a mail and comes off again`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        val registry = registry(fixture.userId, stream)
        val attach = registry.tools.filterIsInstance<LabelEmailTool>().single()
        val detach = registry.tools.filterIsInstance<UnlabelEmailTool>().single()
        val labelId = labelOfSignedIn(fixture.userId)

        val attached = attach.execute(
            LabelEmailTool.Args(emailId = fixture.emailId.toString(), labelId = labelId.toString())
        ) as LabelEmailTool.Result.Attached
        assertTrue(!attached.alreadyThere)
        assertEquals(1, assignments(fixture.emailId, labelId))
        assertEquals(LabelEmailTool.markup(fixture.emailId, labelId), stream.snapshot().content)

        // Twice is once, and the second time leaves nothing in the answer.
        val twice = attach.execute(
            LabelEmailTool.Args(emailId = fixture.emailId.toString(), labelId = labelId.toString())
        ) as LabelEmailTool.Result.Attached
        assertTrue(twice.alreadyThere)
        assertEquals(1, assignments(fixture.emailId, labelId))
        assertEquals(LabelEmailTool.markup(fixture.emailId, labelId), stream.snapshot().content)

        val detached = detach.execute(
            UnlabelEmailTool.Args(emailId = fixture.emailId.toString(), labelId = labelId.toString())
        ) as UnlabelEmailTool.Result.Detached
        assertTrue(!detached.wasNotThere)
        assertEquals(0, assignments(fixture.emailId, labelId))
        assertTrue(stream.snapshot().content.endsWith(UnlabelEmailTool.markup(fixture.emailId, labelId)))
    }

    @Test
    fun `a label of somebody else is not put on a mail`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        val tool = registry(fixture.userId, stream).tools.filterIsInstance<LabelEmailTool>().single()

        val foreign = database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }
            Label.new {
                name = "Privat"
                color = "#ffffff"
                owner = stranger
                createdByAgent = false
            }.id.value
        }

        val result = tool.execute(
            LabelEmailTool.Args(emailId = fixture.emailId.toString(), labelId = foreign.toString())
        )

        // Unknown, exactly like an id that is nothing at all -- and nothing was written.
        assertTrue(result is LabelEmailTool.Result.NotFound)
        assertEquals(0, assignments(fixture.emailId, foreign))
        assertTrue(stream.snapshot().content.isEmpty())
    }

    @Test
    fun `what the agent writes down is found again and shows up in the answer`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        val registry = registry(fixture.userId, stream)

        val write = registry.tools.filterIsInstance<WriteKnowledgeTool>().single()
        val search = registry.tools.filterIsInstance<SearchKnowledgeTool>().single()
        val read = registry.tools.filterIsInstance<ReadKnowledgeTool>().single()

        val written = write.execute(
            WriteKnowledgeTool.Args(
                name = "Stromvertrag",
                description = "Bei Rheinenergie, Abschlag 89 EUR.",
                keywords = listOf("Rheinenergie", "Strom"),
                relevantOn = "2026-11-01",
            )
        )
        assertTrue(written is WriteKnowledgeTool.Result.Written)
        assertTrue(!written.replaced)

        // Found by a word out of a subject line, and read in full by the id the search handed out.
        val hits = search.execute(SearchKnowledgeTool.Args(query = "rheinenergie rechnung")).entries
        assertEquals(listOf("Stromvertrag"), hits.map { it.name })
        assertEquals("2026-11-01", hits.single().relevantOn)

        val entry = read.execute(ReadKnowledgeTool.Args(knowledgeId = hits.single().knowledgeId))
        assertTrue(entry is ReadKnowledgeTool.Result.Knowledge)
        assertEquals("Bei Rheinenergie, Abschlag 89 EUR.", entry.description)

        // Every one of the three left its line in the answer, in the order they ran.
        assertEquals(
            listOf(
                """<toolcall-write-knowledge name="Stromvertrag" replaced="false"></toolcall-write-knowledge>""",
                """<toolcall-search-knowledge query="rheinenergie rechnung"></toolcall-search-knowledge>""",
                """<toolcall-read-knowledge name="Stromvertrag"></toolcall-read-knowledge>""",
            ),
            stream.snapshot().content.split("\n\n"),
        )
    }

    @Test
    fun `knowledge of somebody else is not read`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()

        val stranger = database.query {
            User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }.id.value
        }
        val theirs = KnowledgeStore(database).write(
            userId = stranger,
            name = "Nicht deins",
            description = "Geheim.",
            keywords = listOf("geheim"),
            relevantOn = null,
            byAgent = true,
        )

        val registry = registry(fixture.userId, stream)
        assertTrue(registry.tools.filterIsInstance<SearchKnowledgeTool>().single()
            .execute(SearchKnowledgeTool.Args(query = "geheim")).entries.isEmpty())
        assertTrue(registry.tools.filterIsInstance<ReadKnowledgeTool>().single()
            .execute(ReadKnowledgeTool.Args(knowledgeId = theirs.entry.id.toString()))
                is ReadKnowledgeTool.Result.NotFound)
    }

    private fun registry(userId: User.Id, stream: AiChatMessageStream) = chatToolRegistry(
        userId = userId,
        database = database,
        mailNotifier = MailNotifier(),
        knowledgeStore = KnowledgeStore(database),
        stream = stream,
    )

    private suspend fun labelOfSignedIn(userId: User.Id): Uuid = database.query {
        Label.new {
            name = "Studium"
            color = "#eeeeff"
            owner = User.findById(userId)!!
            createdByAgent = false
        }.id.value
    }

    private suspend fun assignments(emailId: Uuid, labelId: Uuid): Int = database.query {
        EmailLabel.find { (EmailLabels.email eq emailId) and (EmailLabels.label eq labelId) }.count().toInt()
    }

    private data class Fixture(val userId: User.Id, val emailId: Email.Id)

    private suspend fun setUp(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
            val account = ImapAccount.new {
                this.user = user
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                this.user = user
                address = "sender@example.com"
            }
            val email = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Invoice 42"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                textContent = "Please pay."
            }

            Fixture(userId = user.id.value, emailId = email.id.value)
        }
    }
}
