package es.jvbabi.overmail.page.agent.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.EnvelopeSimple
import es.jvbabi.overmail.page.agent.AgentReferences
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.utils.labelContainerColor
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_tool_deleted
import kotlin.uuid.Uuid

/** Past this a chip is cut: one mail must not take a whole line of an answer. */
private val CHIP_MAX_WIDTH = 220.dp
private val CHIP_ICON_SIZE = 14.dp
private val CHIP_GAP = 4.dp
private val CHIP_PADDING = 5.dp

/** Wider than this, a column of a table wraps its text rather than pushing the others out. */
private val TABLE_COLUMN_MAX_WIDTH = 240.dp

/** Above this a subject is cut; up to it, it is shown whole. See the web app's `emailSubject.ts`. */
private const val MAX_SUBJECT_LENGTH = 50
private const val KEPT_SUBJECT_LENGTH = 40

/** What a tap on something in an answer leads to. */
class MarkdownActions(
    val onOpenEmail: (Uuid) -> Unit,
    /** A link the agent wrote: where it goes is the agent's word, so the screen asks first. */
    val onOpenLink: (String) -> Unit,
)

/**
 * An answer's text, drawn from what [parseMarkdown] made of it. [references] is where the chips
 * get the mail, label or person they stand for.
 */
@Composable
fun MarkdownContent(
    blocks: List<MdBlock>,
    references: AgentReferences,
    actions: MarkdownActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> Block(block, references, actions) }
    }
}

@Composable
private fun Block(block: MdBlock, references: AgentReferences, actions: MarkdownActions) {
    val body = MaterialTheme.typography.bodyLarge
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inlines, body, references, actions)

        is MdBlock.Heading -> InlineText(
            inlines = block.inlines,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(fontWeight = FontWeight.SemiBold),
            references = references,
            actions = actions,
            modifier = Modifier.padding(top = 4.dp),
        )

        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${block.start + index}." else "•",
                        style = body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (block.ordered) 28.dp else 20.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.forEach { Block(it, references, actions) }
                    }
                }
            }
        }

        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    block.blocks.forEach { Block(it, references, actions) }
                }
            }
        }

        // Code keeps its lines: wider than the answer, the box scrolls rather than wrapping it.
        is MdBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
            )
        }

        is MdBlock.Table -> Table(block, references, actions)

        MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A table whose columns are as wide as their widest cell, up to [TABLE_COLUMN_MAX_WIDTH]; wider
 * than the answer as a whole, it scrolls sideways.
 */
@Composable
private fun Table(table: MdBlock.Table, references: AgentReferences, actions: MarkdownActions) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val rows = listOf(table.header) + table.rows
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
    ) {
        Layout(
            content = {
                rows.forEachIndexed { rowIndex, row ->
                    repeat(columns) { column ->
                        val isHeader = rowIndex == 0
                        Box(
                            Modifier
                                .then(if (isHeader) Modifier.background(headerBackground) else Modifier)
                                .border(0.5.dp, border)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            InlineText(
                                inlines = row.getOrNull(column).orEmpty(),
                                style = MaterialTheme.typography.bodyMedium.let {
                                    if (isHeader) it.copy(fontWeight = FontWeight.SemiBold) else it
                                },
                                references = references,
                                actions = actions,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val maxColumn = TABLE_COLUMN_MAX_WIDTH.roundToPx()
            val cells = measurables.chunked(columns)
            // Intrinsics first, so every cell of a column and of a row can be given the same size
            // -- the borders only line up that way.
            val widths = (0 until columns).map { column ->
                cells.maxOf { row -> row[column].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(maxColumn)
            }
            val heights = cells.map { row -> row.indices.maxOf { column -> row[column].minIntrinsicHeight(widths[column]) } }
            val placeables = cells.mapIndexed { rowIndex, row ->
                row.mapIndexed { column, cell -> cell.measure(Constraints.fixed(widths[column], heights[rowIndex])) }
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                placeables.forEachIndexed { rowIndex, row ->
                    var x = 0
                    row.forEachIndexed { column, placeable ->
                        placeable.place(x, y)
                        x += widths[column]
                    }
                    y += heights[rowIndex]
                }
            }
        }
    }
}

/** Text with its markup, its links and its chips. */
@Composable
private fun InlineText(
    inlines: List<MdInline>,
    style: TextStyle,
    references: AgentReferences,
    actions: MarkdownActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val chips = chipsOf(inlines, references)
    val chipStyle = style.copy(fontSize = style.fontSize * 0.9f)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val text = remember(inlines, chips, colors) {
        buildAnnotatedString {
            fun append(inline: MdInline) {
                when (inline) {
                    is MdInline.Text -> append(inline.text)
                    is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline.children.forEach(::append) }
                    is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline.children.forEach(::append) }
                    is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        inline.children.forEach(::append)
                    }
                    is MdInline.Code -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.9.em,
                            background = colors.surfaceContainerHighest,
                        )
                    ) { append(inline.text) }
                    is MdInline.Link -> withLink(
                        LinkAnnotation.Clickable(
                            tag = inline.url,
                            styles = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = { actions.onOpenLink(inline.url) },
                        )
                    ) { inline.children.forEach(::append) }
                    is MdInline.Entity -> {
                        val chip = chips.getValue(inline)
                        appendInlineContent(chip.key, chip.label)
                    }
                    MdInline.LineBreak -> append("\n")
                }
            }
            inlines.forEach(::append)
        }
    }

    // A placeholder has the size it is given and does not grow with what is drawn into it, so the
    // chip's label is measured here first.
    val inlineContent = chips.values.associate { chip ->
        val labelWidth = textMeasurer.measure(
            text = AnnotatedString(chip.label),
            style = chipStyle,
            maxLines = 1,
            constraints = Constraints(maxWidth = with(density) { CHIP_MAX_WIDTH.roundToPx() }),
        ).size.width
        val chipWidth = with(density) {
            labelWidth + (CHIP_PADDING * 2 + 2.dp).roundToPx() +
                (if (chip.hasIcon) (CHIP_ICON_SIZE + CHIP_GAP).roundToPx() else 0)
        }
        chip.key to InlineTextContent(
            placeholder = Placeholder(
                width = with(density) { chipWidth.toSp() },
                height = 1.45.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) { EntityChip(chip, chipStyle, actions) }
    }

    Text(text = text, style = style, inlineContent = inlineContent, modifier = modifier)
}

private data class Chip(
    val key: String,
    val entity: MdInline.Entity,
    val label: String,
    /** Null where the thing behind it was not found: deleted since, or still on its way. */
    val labelColor: Color?,
    val hasIcon: Boolean,
    val references: AgentReferences,
)

/** A chip per entity in [inlines], however deep in the markup it stands. */
@Composable
private fun chipsOf(inlines: List<MdInline>, references: AgentReferences): Map<MdInline.Entity, Chip> {
    val deleted = stringResource(Res.string.agent_tool_deleted)
    val entities = buildList {
        fun collect(inline: MdInline) {
            when (inline) {
                is MdInline.Entity -> add(inline)
                is MdInline.Strong -> inline.children.forEach(::collect)
                is MdInline.Emphasis -> inline.children.forEach(::collect)
                is MdInline.Strikethrough -> inline.children.forEach(::collect)
                is MdInline.Link -> inline.children.forEach(::collect)
                else -> Unit
            }
        }
        inlines.forEach(::collect)
    }
    return entities.distinct().associateWith { entity ->
        val key = "${entity.kind.tag}-${entity.id}"
        when (entity.kind) {
            EntityKind.Email -> {
                val email = references.emails[entity.id]
                Chip(key, entity, email?.subject?.let(::shortSubject) ?: deleted, null, hasIcon = true, references)
            }
            EntityKind.Label -> {
                val label = references.labels[entity.id]
                Chip(key, entity, label?.name ?: deleted, label?.color, hasIcon = false, references)
            }
            EntityKind.Person -> {
                val person = references.persons[entity.id]
                Chip(key, entity, person?.displayName ?: deleted, null, hasIcon = true, references)
            }
        }
    }
}

/**
 * The web app's `EmailSegment`, `LabelSegment` and `SenderSegment`: what an element of the answer
 * stands for, outlined in the text. A mail opens on a tap.
 */
@Composable
private fun EntityChip(chip: Chip, style: TextStyle, actions: MarkdownActions) {
    val shape = RoundedCornerShape(4.dp)
    val colors = MaterialTheme.colorScheme
    val fill = chip.labelColor?.labelContainerColor() ?: colors.surfaceContainerHigh

    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(fill)
            .border(1.dp, if (chip.labelColor != null) Color.Transparent else colors.outlineVariant, shape)
            .then(
                if (chip.entity.kind == EntityKind.Email) Modifier.clickable { actions.onOpenEmail(chip.entity.id) }
                else Modifier
            )
            .padding(horizontal = CHIP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        when (chip.entity.kind) {
            EntityKind.Email -> {
                val sender = chip.references.emails[chip.entity.id]?.sentBy
                if (sender != null) ParticipantAvatar(sender, size = CHIP_ICON_SIZE)
                else Icon(PhIcons.Regular.EnvelopeSimple, contentDescription = null, modifier = Modifier.size(CHIP_ICON_SIZE))
            }
            EntityKind.Person -> ParticipantAvatar(
                participant = chip.references.persons[chip.entity.id],
                size = CHIP_ICON_SIZE,
                fallbackName = chip.label,
            )
            EntityKind.Label -> Unit
        }
        Text(
            text = chip.label,
            style = style,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = CHIP_MAX_WIDTH),
        )
    }
}

/** Long subjects are cut, so one mail cannot take a whole line of an answer. */
internal fun shortSubject(subject: String): String =
    if (subject.length <= MAX_SUBJECT_LENGTH) subject
    // Trailing whitespace before the dots would read as a gap.
    else subject.take(KEPT_SUBJECT_LENGTH).trimEnd() + "..."
