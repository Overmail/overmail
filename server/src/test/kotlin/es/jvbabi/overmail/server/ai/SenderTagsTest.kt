package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which parts of the sender reading are filed as tags, see [asTags].
 *
 * The interesting half is what is left out. A tag exists so that the next mail of its kind gets the
 * same one; a handle on a single pull request cannot, and filing it would fill a reader's tag list
 * with categories of one.
 */
class SenderTagsTest {

    private fun names(analysis: SenderAnalysis) = analysis.asTags().map { it.name }

    @Test
    fun `the organisation, the platform and the person are filed`() {
        val tags = names(
            SenderAnalysis(
                person = "Jane Doe",
                organisation = "Musterfirma",
                via = "GitHub",
            )
        )

        // The two that group hundreds of mails first, the person after them.
        assertEquals(listOf("Musterfirma", "GitHub", "Jane Doe"), tags)
    }

    @Test
    fun `a reading that found nothing files nothing`() {
        assertEquals(emptyList(), names(SenderAnalysis()))
    }

    @Test
    fun `the same name twice is one tag`() {
        // Common: GitHub is both the organisation behind the mail and the platform it came through.
        assertEquals(listOf("GitHub"), names(SenderAnalysis(organisation = "GitHub", via = "github")))
    }

    @Test
    fun `a newsletter's name is a tag`() {
        assertEquals(
            listOf("Wochenrückblick"),
            names(SenderAnalysis(context = listOf("Wochenrückblick"))),
        )
    }

    @Test
    fun `a repository is a tag, the pull request in it is not`() {
        // The thing mail keeps coming about, without the one instance this mail is about.
        assertEquals(
            listOf("acme/widgets"),
            names(SenderAnalysis(context = listOf("gh:acme/widgets#412"))),
        )
    }

    @Test
    fun `a project keeps its name and loses its number`() {
        assertEquals(listOf("PROJ"), names(SenderAnalysis(context = listOf("PROJ-123"))))
    }

    @Test
    fun `a handle that is nothing but one occurrence is not a tag`() {
        val tags = names(
            SenderAnalysis(context = listOf("INC0043221", "Rechnung 2024-04", "#412"))
        )

        assertTrue(tags.isEmpty(), tags.toString())
    }

    @Test
    fun `the same repository twice is one tag`() {
        assertEquals(
            listOf("acme/widgets"),
            names(SenderAnalysis(context = listOf("gh:acme/widgets#412", "gh:acme/widgets#418"))),
        )
    }

    @Test
    fun `a name too long to be a label is left out`() {
        val tags = names(SenderAnalysis(organisation = "Musterfirma für Fahrzeugtechnik und Zubehör AG"))

        assertTrue(tags.isEmpty(), tags.toString())
    }

    @Test
    fun `every tag says why it is there`() {
        val filed = SenderAnalysis(organisation = "Sparkasse", via = "LernSax", person = "Jane Doe").asTags()

        assertTrue(filed.all { it.reason.isNotBlank() }, filed.toString())
        assertEquals("Kam über LernSax.", filed.single { it.name == "LernSax" }.reason)
    }
}
