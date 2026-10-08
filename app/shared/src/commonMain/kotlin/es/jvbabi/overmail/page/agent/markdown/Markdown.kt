package es.jvbabi.overmail.page.agent.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.uuid.Uuid

/**
 * An answer's markdown as the app draws it: GitHub-flavoured markdown, parsed by JetBrains'
 * `markdown` library and brought into a shape that holds only what [MarkdownContent] shows.
 *
 * The agent writes three elements of its own into the text -- `<email id>`, `<label id>` and
 * `<person id>`, see `ChatAgent.SYSTEM_PROMPT` on the server -- which become an [MdInline.Entity]
 * wherever they stand. Whatever the agent put between the tags is dropped: the chip shows the
 * thing itself, the way the web app's `ChatAgentContent` does. Any other html is left out, its
 * text kept.
 */
sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    /** [items] each hold the blocks of one item; [start] is the first number of an ordered list. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val text: String, val language: String?) : MdBlock
    /** [header] and every row hold a cell's inlines per column. */
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strikethrough(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val url: String, val children: List<MdInline>) : MdInline
    data class Entity(val kind: EntityKind, val id: Uuid) : MdInline
    data object LineBreak : MdInline
}

enum class EntityKind(val tag: String) { Email("email"), Label("label"), Person("person") }

private val parser = MarkdownParser(GFMFlavourDescriptor())

private val ENTITY_OPEN = Regex("""^<(email|label|person)\s+id\s*=\s*"([^"]*)"\s*(/?)>$""", RegexOption.IGNORE_CASE)
private val ENTITY_ELEMENT = Regex("""<(email|label|person)\s+id\s*=\s*"([^"]*)"\s*(?:/>|>[\s\S]*?</\1\s*>)""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")

/** A tag the answer is still in the middle of writing; shown as text, it would flicker in and out. */
private val UNFINISHED_TAG = Regex("""<[a-zA-Z/][^>\n]*$""")

/** [streaming]: the answer is still being written, and its end may be half a tag. */
fun parseMarkdown(source: String, streaming: Boolean = false): List<MdBlock> {
    val text = if (streaming) source.replace(UNFINISHED_TAG, "") else source
    return MarkdownSource(text).blocks(parser.buildMarkdownTreeFromString(text).children)
}

private class MarkdownSource(val text: String) {

    fun ASTNode.text(): String = getTextInNode(text).toString()

    fun blocks(nodes: List<ASTNode>): List<MdBlock> = nodes.mapNotNull { block(it) }

    fun block(node: ASTNode): MdBlock? = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> MdBlock.Paragraph(inlines(node.children).trimmed())
            .takeIf { it.inlines.isNotEmpty() }

        in HEADINGS -> {
            val content = node.children.firstOrNull {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            }
            MdBlock.Heading(
                level = HEADINGS.getValue(node.type),
                inlines = content?.let { inlines(it.children.ifEmpty { listOf(it) }) }.orEmpty().trimmed(),
            )
        }

        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
            val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
            val start = items.firstOrNull()
                ?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                ?.text()?.trim()?.trimEnd('.', ')')?.toIntOrNull()
            MdBlock.ListBlock(
                ordered = node.type == MarkdownElementTypes.ORDERED_LIST,
                start = start ?: 1,
                items = items.map { item -> blocks(item.children) },
            )
        }

        MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(blocks(node.children))

        MarkdownElementTypes.CODE_FENCE -> {
            val contentStart = node.children.indexOfFirst { it.type == MarkdownTokenTypes.EOL }
            val contentEnd = node.children.indexOfLast { it.type == MarkdownTokenTypes.CODE_FENCE_END }
                .takeIf { it > contentStart } ?: node.children.size
            val code = if (contentStart == -1) "" else node.children
                .subList(contentStart + 1, contentEnd)
                .joinToString("") { it.text() }
                .removeSuffix("\n")
            MdBlock.Code(
                text = code,
                language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.text()?.trim(),
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> MdBlock.Code(
            text = node.children
                .filter { it.type == MarkdownTokenTypes.CODE_LINE || it.type == MarkdownTokenTypes.EOL }
                .joinToString("") { if (it.type == MarkdownTokenTypes.EOL) "\n" else it.text().removePrefix("    ") }
                .trimEnd('\n'),
            language = null,
        )

        GFMElementTypes.TABLE -> {
            fun cells(row: ASTNode) = row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .map { inlines(it.children).trimmed() }
            MdBlock.Table(
                header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.let(::cells).orEmpty(),
                rows = node.children.filter { it.type == GFMElementTypes.ROW }.map(::cells),
            )
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.Rule

        // An element the agent put on lines of its own: the parser sees an html block in it, but
        // it is a sentence like any other.
        MarkdownElementTypes.HTML_BLOCK -> MdBlock.Paragraph(htmlInlines(node.text()).trimmed())
            .takeIf { it.inlines.isNotEmpty() }

        else -> null
    }

    fun inlines(nodes: List<ASTNode>): List<MdInline> {
        val result = mutableListOf<MdInline>()
        fun add(inline: MdInline) {
            val last = result.lastOrNull()
            if (inline is MdInline.Text && last is MdInline.Text) result[result.lastIndex] = MdInline.Text(last.text + inline.text)
            else result += inline
        }

        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            index++
            when (node.type) {
                MarkdownElementTypes.STRONG -> add(MdInline.Strong(inlines(node.children.trimDelimiters(2))))
                MarkdownElementTypes.EMPH -> add(MdInline.Emphasis(inlines(node.children.trimDelimiters(1))))
                GFMElementTypes.STRIKETHROUGH -> add(MdInline.Strikethrough(inlines(node.children.trimDelimiters(2))))

                MarkdownElementTypes.CODE_SPAN -> add(
                    MdInline.Code(node.children.filter { it.type != MarkdownTokenTypes.BACKTICK }.joinToString("") { it.text() }.trim())
                )

                MarkdownElementTypes.INLINE_LINK -> {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    val children = label?.children?.trimDelimiters(1)?.let(::inlines).orEmpty()
                    val url = destination?.text()?.trim()?.removeSurrounding("<", ">")
                    if (url == null) children.forEach(::add) else add(MdInline.Link(url, children))
                }

                MarkdownElementTypes.IMAGE -> {
                    // No pictures in an answer: what it shows is said by its text, and it leads where it points.
                    val link = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                    if (link != null) inlines(listOf(link)).forEach(::add)
                }

                MarkdownElementTypes.AUTOLINK -> {
                    val url = node.text().removeSurrounding("<", ">")
                    add(MdInline.Link(url, listOf(MdInline.Text(url))))
                }

                GFMTokenTypes.GFM_AUTOLINK -> {
                    val shown = node.text()
                    val url = if ("://" in shown || shown.startsWith("mailto:")) shown else "https://$shown"
                    add(MdInline.Link(url, listOf(MdInline.Text(shown))))
                }

                MarkdownTokenTypes.HTML_TAG -> {
                    val open = ENTITY_OPEN.matchEntire(node.text().trim())
                    val kind = open?.let { kindOf(it.groupValues[1]) }
                    val id = open?.let { Uuid.parseOrNull(it.groupValues[2]) }
                    if (kind == null || id == null) continue
                    add(MdInline.Entity(kind, id))
                    // What stands inside is the agent's guess at the name; the chip knows it.
                    if (open.groupValues[3] != "/") {
                        val close = (index until nodes.size).firstOrNull { i ->
                            nodes[i].type == MarkdownTokenTypes.HTML_TAG &&
                                nodes[i].text().trim().equals("</${kind.tag}>", ignoreCase = true)
                        }
                        index = (close ?: nodes.lastIndex) + 1
                    }
                }

                MarkdownTokenTypes.HARD_LINE_BREAK -> add(MdInline.LineBreak)
                // A line break inside a paragraph is a space, as everywhere markdown is rendered.
                MarkdownTokenTypes.EOL -> add(MdInline.Text(" "))
                // Markup the parser did not take for markdown -- an unused bracket, say -- is text.
                else -> if (node.children.isEmpty()) add(MdInline.Text(node.text())) else inlines(node.children).forEach(::add)
            }
        }
        return result
    }

    /** The text of an html block, with the entities in it taken out as chips and any other tag dropped. */
    fun htmlInlines(html: String): List<MdInline> {
        val result = mutableListOf<MdInline>()
        fun text(raw: String) {
            val cleaned = raw.replace(HTML_TAG, "").replace(Regex("\\s+"), " ")
            if (cleaned.isNotEmpty()) result += MdInline.Text(cleaned)
        }
        var index = 0
        ENTITY_ELEMENT.findAll(html).forEach { match ->
            text(html.substring(index, match.range.first))
            val kind = kindOf(match.groupValues[1])
            val id = Uuid.parseOrNull(match.groupValues[2])
            if (kind != null && id != null) result += MdInline.Entity(kind, id)
            index = match.range.last + 1
        }
        text(html.substring(index))
        return result
    }
}

private val HEADINGS: Map<IElementType, Int> = mapOf(
    MarkdownElementTypes.ATX_1 to 1,
    MarkdownElementTypes.ATX_2 to 2,
    MarkdownElementTypes.ATX_3 to 3,
    MarkdownElementTypes.ATX_4 to 4,
    MarkdownElementTypes.ATX_5 to 5,
    MarkdownElementTypes.ATX_6 to 6,
    MarkdownElementTypes.SETEXT_1 to 1,
    MarkdownElementTypes.SETEXT_2 to 2,
)

private fun kindOf(tag: String): EntityKind? = EntityKind.entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

/** The children of `**x**`, `*x*` or `~~x~~` without the [count] delimiter tokens on either side. */
private fun List<ASTNode>.trimDelimiters(count: Int): List<ASTNode> =
    if (size <= count * 2) emptyList() else subList(count, size - count)

/** Without the space a block begins or ends with: it is the gap between it and its marker. */
private fun List<MdInline>.trimmed(): List<MdInline> {
    val result = toMutableList()
    (result.firstOrNull() as? MdInline.Text)?.let { first ->
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) result.removeAt(0) else result[0] = MdInline.Text(trimmed)
    }
    (result.lastOrNull() as? MdInline.Text)?.let { last ->
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = MdInline.Text(trimmed)
    }
    return result
}

/** The ids of every entity in [source], by kind, for looking them up before they are drawn. */
fun entityIds(source: String): Map<EntityKind, Set<Uuid>> = ENTITY_ELEMENT.findAll(source)
    .mapNotNull { match ->
        val kind = kindOf(match.groupValues[1]) ?: return@mapNotNull null
        val id = Uuid.parseOrNull(match.groupValues[2]) ?: return@mapNotNull null
        kind to id
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, ids) -> ids.toSet() }
