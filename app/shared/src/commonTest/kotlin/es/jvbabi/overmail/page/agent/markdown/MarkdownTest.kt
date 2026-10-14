package es.jvbabi.overmail.page.agent.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MarkdownTest {
    private val person = Uuid.parse("016bcadd-0267-4e08-bf28-e8089b1dd159")
    private val email = Uuid.parse("fac0800e-606d-43fb-8e1e-83704611c3f1")

    @Test
    fun entitiesBecomeChipsAndWhatTheyHoldIsDropped() {
        val blocks = parseMarkdown(
            "Liegt **im Anhang** von <person id=\"$person\">Stefan Neubert</person> " +
                "(<email id=\"$email\">Start</email>)."
        )

        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdInline.Text("Liegt "),
                        MdInline.Strong(listOf(MdInline.Text("im Anhang"))),
                        MdInline.Text(" von "),
                        MdInline.Entity(EntityKind.Person, person),
                        MdInline.Text(" ("),
                        MdInline.Entity(EntityKind.Email, email),
                        MdInline.Text(")."),
                    )
                )
            ),
            blocks,
        )
    }

    @Test
    fun listsKeepTheirItemsAndNesting() {
        val blocks = parseMarkdown("3. **Start:** heute\n4. two\n   - nested <label id=\"$email\"></label>")

        assertEquals(
            listOf(
                MdBlock.ListBlock(
                    ordered = true,
                    start = 3,
                    items = listOf(
                        listOf(MdBlock.Paragraph(listOf(MdInline.Strong(listOf(MdInline.Text("Start:"))), MdInline.Text(" heute")))),
                        listOf(
                            MdBlock.Paragraph(listOf(MdInline.Text("two"))),
                            MdBlock.ListBlock(
                                ordered = false,
                                start = 1,
                                items = listOf(listOf(MdBlock.Paragraph(listOf(MdInline.Text("nested "), MdInline.Entity(EntityKind.Label, email))))),
                            ),
                        ),
                    ),
                )
            ),
            blocks,
        )
    }

    @Test
    fun inlineMarkupAndLinks() {
        val blocks = parseMarkdown("*a* ~~b~~ `c` [d](https://e.com) www.f.com\nsoft  \nhard")

        assertEquals(
            listOf(
                MdBlock.Paragraph(
                    listOf(
                        MdInline.Emphasis(listOf(MdInline.Text("a"))),
                        MdInline.Text(" "),
                        MdInline.Strikethrough(listOf(MdInline.Text("b"))),
                        MdInline.Text(" "),
                        MdInline.Code("c"),
                        MdInline.Text(" "),
                        MdInline.Link("https://e.com", listOf(MdInline.Text("d"))),
                        MdInline.Text(" "),
                        MdInline.Link("https://www.f.com", listOf(MdInline.Text("www.f.com"))),
                        MdInline.Text(" soft"),
                        MdInline.LineBreak,
                        MdInline.Text(" hard"),
                    )
                )
            ),
            blocks,
        )
    }

    @Test
    fun blocks() {
        val blocks = parseMarkdown("# Title\n\n> quoted\n\n```kotlin\nval x = 1\n```\n\n| A | B |\n|---|---|\n| a | **b** |\n\n---")

        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(MdInline.Text("Title"))),
                MdBlock.Quote(listOf(MdBlock.Paragraph(listOf(MdInline.Text("quoted"))))),
                MdBlock.Code("val x = 1", "kotlin"),
                MdBlock.Table(
                    header = listOf(listOf(MdInline.Text("A")), listOf(MdInline.Text("B"))),
                    rows = listOf(listOf(listOf(MdInline.Text("a")), listOf(MdInline.Strong(listOf(MdInline.Text("b")))))),
                ),
                MdBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun anElementOnLinesOfItsOwnIsStillAChip() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Entity(EntityKind.Email, email), MdInline.Text(" said so")))),
            parseMarkdown("<email id=\"$email\">\nStart</email> said so"),
        )
    }

    @Test
    fun aTagStillBeingWrittenIsNotShown() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdInline.Text("From")))),
            parseMarkdown("From <person id=\"016b", streaming = true),
        )
    }

    @Test
    fun entityIdsAreFoundByKind() {
        assertEquals(
            mapOf(EntityKind.Person to setOf(person), EntityKind.Email to setOf(email)),
            entityIds("<person id=\"$person\">S</person> <email id=\"$email\"/> <email id=\"$email\"></email>"),
        )
    }
}
