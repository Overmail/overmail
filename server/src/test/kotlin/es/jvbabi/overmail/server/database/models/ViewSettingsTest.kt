package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** What the `views.view` column has to read back as; there is no migration tool to fix a row. */
class ViewSettingsTest {

    private val json = OvermailDatabase.json

    @Test
    fun `a row written before views had a filter reads as filtering nothing`() {
        val stored = """
            {"groupings":[{"type":"date_smart","sort_reversed":false}],
             "email_sorting":{"type":"date","sort_reversed":false}}
        """.trimIndent()

        val settings = json.decodeFromString<ViewSettings>(stored)

        assertEquals(ViewSettings.Filter.NONE, settings.filter)
    }

    @Test
    fun `a filter only says what it names`() {
        val stored = """
            {"groupings":[],"filter":{"read_state":true},
             "email_sorting":{"type":"date","sort_reversed":false}}
        """.trimIndent()

        val filter = json.decodeFromString<ViewSettings>(stored).filter

        assertEquals(true, filter.readState)
        // Null, not an empty set: the one lets everything through, the other nothing.
        assertEquals(null, filter.archivedState)
        assertEquals(null, filter.hasLabels)
    }

    @Test
    fun `the filter is written out with the settings`() {
        val settings = ViewSettings(
            groupings = emptyList(),
            filter = ViewSettings.Filter(
                readState = false,
                archivedState = setOf(EmailArchiveAction.Archive),
                hasLabels = setOf(Uuid.parse("00000000-0000-4000-8000-000000000001")),
            ),
            emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
        )

        val written = json.encodeToString(settings)

        assertTrue(written.contains("\"read_state\":false"), written)
        assertTrue(written.contains("\"archived_state\":[\"Archive\"]"), written)
        assertEquals(settings, json.decodeFromString<ViewSettings>(written))
    }
}
