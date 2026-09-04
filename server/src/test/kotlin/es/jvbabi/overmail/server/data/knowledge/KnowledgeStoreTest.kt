package es.jvbabi.overmail.server.data.knowledge

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** What the assistant knows about a user: how it is found again, and how it is kept current. */
class KnowledgeStoreTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:knowledge-store;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val store = KnowledgeStore(database)

    @Test
    fun `an entry is found by its keywords, fuzzily`() = runTest {
        val (user, _) = setUp()

        store.write(
            userId = user,
            name = "Stromvertrag",
            description = "Der Nutzer ist bei Rheinenergie und zahlt monatlich per SEPA.",
            keywords = listOf("Rheinenergie", "Strom", "Abschlag"),
            relevantOn = null,
            byAgent = true,
        )

        // The stored keyword is "rheinenergie"; the query is a word out of a subject line.
        assertEquals("Stromvertrag", store.search(user, "rheinenergie rechnung", limit = 5).single().name)
        // Fuzzy the way the rest of the search in this app is: a stored keyword is looked in for
        // the query, so a beginning of it finds the entry.
        assertEquals(1, store.search(user, "rheinen", limit = 5).size)
        // And nothing that has nothing to do with it.
        assertTrue(store.search(user, "zahnarzt", limit = 5).isEmpty())
    }

    @Test
    fun `more of the query beats less of it`() = runTest {
        val (user, _) = setUp()

        store.write(user, "Umzug", "Der Nutzer zieht nach Köln.", listOf("umzug", "köln"), null, true)
        store.write(user, "Kölner Verkehrsbetriebe", "Abo läuft.", listOf("köln", "kvb", "abo"), null, true)

        // Two words hit the move, one hits the transport company. "koeln" without the umlaut
        // finds both, which is what the fuzzy matching is there for.
        val hits = store.search(user, "umzug koeln", limit = 5)
        assertEquals(listOf("Umzug", "Kölner Verkehrsbetriebe"), hits.map { it.name })
    }

    @Test
    fun `an empty query is what is known most recently`() = runTest {
        val (user, _) = setUp()

        store.write(user, "Erstes", "Alt.", listOf("alt"), null, true)
        store.write(user, "Zweites", "Neu.", listOf("neu"), null, true)

        assertEquals(listOf("Zweites", "Erstes"), store.search(user, "   ", limit = 5).map { it.name })
        assertEquals(1, store.search(user, "", limit = 1).size)
    }

    @Test
    fun `writing a name again rewrites that entry`() = runTest {
        val (user, _) = setUp()

        val first = store.write(user, "Stromvertrag", "Bei Rheinenergie.", listOf("strom"), null, true)
        assertTrue(!first.existed)

        val second = store.write(
            userId = user,
            // Different casing and spacing: the same entry, and the stored spelling stays.
            name = "  stromvertrag ",
            description = "Bei Rheinenergie, Abschlag 89 EUR ab dem 1.11.",
            keywords = listOf("strom", "Rheinenergie", "abschlag"),
            relevantOn = LocalDate(2026, 11, 1),
            byAgent = true,
        )

        assertTrue(second.existed)
        assertEquals(first.entry.id, second.entry.id)
        assertEquals("Stromvertrag", second.entry.name)
        assertEquals(LocalDate(2026, 11, 1), second.entry.relevantOn)
        assertEquals(listOf("strom", "rheinenergie", "abschlag"), second.entry.keywords)
        assertEquals(1, database.query { Knowledges.selectAll().where { Knowledges.owner eq user }.count() })
    }

    @Test
    fun `knowledge belongs to one user`() = runTest {
        val (user, stranger) = setUp()

        val mine = store.write(user, "Stromvertrag", "Bei Rheinenergie.", listOf("strom"), null, true)
        store.write(stranger, "Stromvertrag", "Woanders.", listOf("strom"), null, true)

        // The same name for both, and two entries -- neither sees the other's.
        assertEquals(1, store.search(user, "strom", limit = 5).size)
        assertEquals("Bei Rheinenergie.", store.search(user, "strom", limit = 5).single().description)
        assertNull(store.read(stranger, mine.entry.id))
        assertEquals("Bei Rheinenergie.", store.read(user, mine.entry.id)?.description)
    }

    @Test
    fun `an excerpt is the beginning of a long entry`() = runTest {
        val (user, _) = setUp()

        val long = "Sehr lange Notiz. ".repeat(40)
        val written = store.write(user, "Lang", long, listOf("lang"), null, true)

        assertTrue(written.entry.excerpt.length < long.length)
        assertTrue(written.entry.excerpt.endsWith("..."))
        // The whole text is still there for whoever reads the entry itself.
        assertEquals(long.trim(), store.read(user, written.entry.id)?.description)
    }

    /** Two users, so everything can be checked against somebody else's knowledge. */
    private suspend fun setUp(): Pair<User.Id, User.Id> {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }
            user.id.value to stranger.id.value
        }
    }
}
