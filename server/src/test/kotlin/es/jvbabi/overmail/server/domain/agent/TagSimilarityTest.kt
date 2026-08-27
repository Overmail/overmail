package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TagUsage
import es.jvbabi.overmail.server.domain.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Which existing tags are put in front of the model when it is about to make a new one.
 *
 * The point of the whole thing is that a mailbox does not end up with "Rechnung", "Rechnungen" and
 * "Rechnnung" side by side. What is tested is that those turn up as candidates -- not that they are
 * merged, which is the model's call and depends on the mail.
 */
class TagSimilarityTest {

    private val owner = User(
        id = Uuid.random(),
        username = "julius",
        email = "julius@example.org",
        name = "Julius Babies",
    )

    private fun usage(name: String, mails: Int) = TagUsage(
        tag = Tag(
            id = Uuid.random(),
            user = owner,
            name = name,
            description = null,
            createdAt = Clock.System.now(),
            createdByAgent = true,
        ),
        mails = mails,
    )

    private val mailbox = listOf(
        usage("Rechnung", 42),
        usage("Studium", 30),
        usage("Bewerbung", 12),
        usage("Kündigung", 3),
        usage("Reise", 8),
    )

    private fun candidates(name: String) = similarTo(name, mailbox).map { it.usage.tag.name }

    @Test
    fun `a plural finds its singular`() {
        assertEquals(listOf("Rechnung"), candidates("Rechnungen"))
    }

    @Test
    fun `case is not a difference`() {
        assertEquals(listOf("Studium"), candidates("studium"))
    }

    @Test
    fun `an umlaut written out finds the umlaut`() {
        assertEquals(listOf("Kündigung"), candidates("Kuendigung"))
    }

    @Test
    fun `a compound finds its head`() {
        assertTrue(candidates("Stromrechnung").contains("Rechnung"))
    }

    @Test
    fun `a typo finds the word it was meant to be`() {
        assertTrue(candidates("Bewebung").contains("Bewerbung"))
    }

    @Test
    fun `a word the mailbox has no version of finds nothing`() {
        assertEquals(emptyList(), candidates("Versicherung"))
    }

    @Test
    fun `the closest comes first, and the more used of two equals`() {
        val crowded = listOf(usage("Rechnung", 42), usage("Rechnung", 1), usage("Reise", 8))
        val ranked = similarTo("Rechnungen", crowded)

        assertEquals(42, ranked.first().usage.mails)
    }

    @Test
    fun `the same word is as close as it gets`() {
        assertEquals(1.0, tagSimilarity("Rechnung", "Rechnung"))
    }

    @Test
    fun `two different words are not close`() {
        assertTrue(tagSimilarity("Rechnung", "Bewerbung") < SIMILAR_ENOUGH)
    }
}
