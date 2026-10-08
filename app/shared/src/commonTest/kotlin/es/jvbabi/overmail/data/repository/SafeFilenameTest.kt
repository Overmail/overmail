package es.jvbabi.overmail.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeFilenameTest {

    @Test
    fun keepsAnOrdinaryName() {
        assertEquals("Rechnung 2026-09.pdf", safeFilename("Rechnung 2026-09.pdf"))
    }

    @Test
    fun aSeparatorLeadsNowhereElse() {
        assertEquals(".._.._etc_passwd", safeFilename("../../etc/passwd"))
        assertEquals("C__Windows_evil.exe", safeFilename("C:\\Windows\\evil.exe"))
    }

    @Test
    fun aNameThatIsNoNameBecomesOne() {
        assertEquals("attachment", safeFilename(""))
        assertEquals("attachment", safeFilename("  "))
        assertEquals("attachment", safeFilename(".."))
    }

    @Test
    fun aLongNameIsCutAndKeepsItsExtension() {
        val name = safeFilename("a".repeat(300) + ".docx")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".docx"))
    }
}
