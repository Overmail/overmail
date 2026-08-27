package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.ai.ProposedTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TagUsage
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * That filing a tag does not spell a word the mailbox already has a second time.
 *
 * Only spelling. Whether "Beleg" means the mailbox's "Rechnung" is a question about the mail, and
 * this class is not allowed to answer it -- see the revision step, which can read the mail.
 */
class TagFilingTest {

    private val owner = User(
        id = Uuid.random(),
        username = "julius",
        email = "julius@example.org",
        name = "Julius Babies",
    )

    private val mailId = Uuid.random()

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

    private fun filed(vararg proposed: String, mailbox: List<TagUsage>): List<String> {
        val tags = FakeTags()
        tags.existing = mailbox

        val out = runBlocking {
            TagFiling(owner, tags).file(mailId, proposed.map { ProposedTag(it, "Weil.") })
        }

        assertEquals(out.map { it.name }, tags.attached.map { it.tag.name })

        return out.map { it.name }
    }

    @Test
    fun `the mailbox's own spelling wins`() {
        assertEquals(
            listOf("GitHub"),
            filed("Github", mailbox = listOf(usage("GitHub", 17))),
        )
    }

    @Test
    fun `a plural is filed as the singular the mailbox has`() {
        assertEquals(
            listOf("Rechnung"),
            filed("Rechnungen", mailbox = listOf(usage("Rechnung", 42))),
        )
    }

    @Test
    fun `a word the mailbox has no version of is filed as it is`() {
        assertEquals(
            listOf("Versicherung"),
            filed("Versicherung", mailbox = listOf(usage("Rechnung", 42))),
        )
    }

    @Test
    fun `a compound is not quietly turned into its head`() {
        // Close, but a decision about the mail rather than about spelling: left to the step that
        // can read it.
        assertEquals(
            listOf("Stromrechnung"),
            filed("Stromrechnung", mailbox = listOf(usage("Rechnung", 42))),
        )
    }

    @Test
    fun `nothing proposed files nothing`() {
        val tags = FakeTags()

        runBlocking { TagFiling(owner, tags).file(mailId, emptyList()) }

        assertEquals(emptyList(), tags.attached)
    }
}
