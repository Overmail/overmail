package es.jvbabi.overmail.page.home.components.group

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.*
import es.jvbabi.overmail.domain.model.ViewGrouping
import es.jvbabi.overmail.domain.model.ViewGroupingKind
import es.jvbabi.overmail.domain.model.ViewSorting
import es.jvbabi.overmail.domain.model.ViewSortingKind
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import overmail.app.shared.generated.resources.*

/** What the grouping settings hand back on every change: the categories and the mail sorting. */
data class GroupingSettings(
    val groupings: List<ViewGrouping>,
    val sorting: ViewSorting,
)

/**
 * How a listing is cut up: which categories group it, in which order, which way round each of
 * them sorts, and what orders the mails inside the deepest one. The web app's
 * `GroupingSettings.svelte`, as a list to drag rather than a menu.
 *
 * A category is dragged by its handle, or by the whole row after a long press, between the active
 * ones and the inactive ones. Tapping an active one turns its order around; picking the mail
 * sorting that is already picked does the same for the mails.
 *
 * [viewState] is read, never written: every change goes out through [onGroupingSettingsChanged],
 * a drag once it is dropped. [onDraggingChanged] says when a row is picked up and put down, so
 * a surrounding sheet can hold still in between. The order of the *inactive* categories is this component's own -- it
 * is where a category waits, not something a view is.
 */
@Composable
fun GroupingSettingsContent(
    viewState: ViewState,
    onGroupingSettingsChanged: (GroupingSettings) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onDraggingChanged: (Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val editor = remember { GroupingEditor(viewState, listState, scope) }
    val haptics = LocalHapticFeedback.current

    SideEffect {
        editor.haptics = haptics
        editor.onChanged = onGroupingSettingsChanged
        editor.onDraggingChanged = onDraggingChanged
    }

    // A state from outside -- another device, the caller putting it back -- replaces what is on
    // screen, but not in the middle of a drag: the row under the finger would jump away.
    LaunchedEffect(viewState.groupings, viewState.sorting) {
        editor.sync(viewState)
    }

    val entries = buildList {
        add(ListEntry.Header(ACTIVE_HEADER, active = true))
        editor.active.forEach { add(ListEntry.Category(it, isActive = true)) }
        if (editor.active.isEmpty()) {
            add(ListEntry.Empty(ACTIVE_EMPTY, active = true))
        } else {
            // The deepest active category is the last row, so this sits under the group whose
            // mails it actually orders.
            add(ListEntry.MailSortHeader)
            MAIL_SORTS.forEach { add(ListEntry.MailSortOption(it)) }
        }
        add(ListEntry.Header(INACTIVE_HEADER, active = false))
        editor.inactive.forEach { add(ListEntry.Category(it, isActive = false)) }
        if (editor.inactive.isEmpty()) add(ListEntry.Empty(INACTIVE_EMPTY, active = false))
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        // The finger is moving a row, not the list.
        userScrollEnabled = editor.dragged == null,
    ) {
        // One `items` for everything, and every category rendered from the same line below: a
        // row that changes zone mid-drag must stay the same composition, or its pointer input is
        // thrown away with it and the drag ends under the finger. Two `items` blocks, one per
        // zone, are two different call sites to Compose.
        items(entries, key = { it.key }, contentType = { it::class }) { entry ->
            when (entry) {
                is ListEntry.Category -> CategoryItem(editor = editor, kind = entry.kind, isActive = entry.isActive)

                is ListEntry.Header -> ZoneHeader(
                    text = if (entry.active) {
                        stringResource(Res.string.home_grouping_active, editor.active.size, MAX_ACTIVE)
                    } else {
                        stringResource(Res.string.home_grouping_inactive)
                    },
                    modifier = Modifier.animateItem(),
                )

                is ListEntry.Empty -> EmptyHint(
                    text = stringResource(
                        if (entry.active) Res.string.home_grouping_active_empty
                        else Res.string.home_grouping_inactive_empty
                    ),
                    modifier = Modifier.animateItem(),
                )

                ListEntry.MailSortHeader -> MailSortHeader(modifier = Modifier.animateItem())

                is ListEntry.MailSortOption -> MailSortRow(
                    option = entry.option,
                    sorting = editor.sorting,
                    onClick = { editor.selectMailSort(entry.option.kind) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/**
 * The editing state behind [GroupingSettingsContent]: the two zones, the directions and the drag.
 *
 * The rows are the lazy list's, so where they are is read from its layout rather than measured
 * again -- that is also what keeps the dragged row under the finger while the list moves the slot
 * it sits in.
 */
@Stable
private class GroupingEditor(
    initial: ViewState,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var haptics: HapticFeedback? = null
    var onChanged: (GroupingSettings) -> Unit = {}
    var onDraggingChanged: (Boolean) -> Unit = {}

    /** The categories that group, outermost first. */
    var active by mutableStateOf(initial.groupings.map { it.kind })
        private set

    /** Where the others wait, in the order they are offered. */
    var inactive by mutableStateOf(CATEGORIES.map { it.kind }.filter { it !in active })
        private set

    var sorting by mutableStateOf(initial.sorting)
        private set

    /**
     * Which way round each category sorts, the inactive ones included: dragging a category out
     * and back in should not forget which way it was turned.
     */
    private val reversed = mutableStateMapOf<ViewGroupingKind, Boolean>().apply {
        initial.groupings.forEach { put(it.kind, it.reversed) }
    }

    var dragged by mutableStateOf<ViewGroupingKind?>(null)
        private set

    /** Where the dragged row's top is meant to be, in the list's viewport. */
    private var dragTop by mutableFloatStateOf(0f)

    private var activeBeforeDrag: List<ViewGroupingKind> = emptyList()

    /** The row that was just dropped and is still springing into its slot. */
    var settling by mutableStateOf<ViewGroupingKind?>(null)
        private set

    /** Its distance from the slot while it does. */
    private val settleOffset = Animatable(0f)
    private var settleJob: Job? = null

    /**
     * How far the lifted row is raised, 0 to 1: its shadow, its scale and its colour. One value
     * for all three, so they come and go together -- and the row only gives up its place above
     * the others once this is back at 0, or its shadow would be cut off by the rows beside it.
     */
    private val lift = Animatable(0f)
    private var liftJob: Job? = null

    /** Whether [kind] is drawn off its slot -- under the finger, or on its way back. */
    fun isLifted(kind: ViewGroupingKind) = kind == dragged || kind == settling

    fun liftOf(kind: ViewGroupingKind): Float = if (isLifted(kind)) lift.value else 0f

    fun isReversed(kind: ViewGroupingKind) = reversed[kind] ?: false

    fun sync(state: ViewState) {
        if (dragged != null) return

        active = state.groupings.map { it.kind }
        state.groupings.forEach { reversed[it.kind] = it.reversed }
        // The waiting order is kept for what is still waiting; whatever just left the active ones
        // goes behind it.
        inactive = inactive.filter { it !in active } +
            CATEGORIES.map { it.kind }.filter { it !in active && it !in inactive }
        sorting = state.sorting
    }

    fun startDrag(kind: ViewGroupingKind) {
        val row = item(kind) ?: return
        // A row still springing back is put down where it is: only one is ever off its slot.
        settleJob?.cancel()
        settling = null
        dragged = kind
        dragTop = row.offset.toFloat()
        activeBeforeDrag = active
        liftJob = scope.launch { lift.animateTo(1f, LIFT_SPEC) }
        // Right away rather than on the next composition: the sheet must not take the very
        // first moves of this drag for its own.
        onDraggingChanged(true)
        haptics?.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun dragBy(delta: Float) {
        if (dragged == null) return
        dragTop += delta
        reposition()
    }

    fun endDrag() {
        val kind = dragged ?: return
        val from = translationOf(kind)
        dragged = null
        onDraggingChanged(false)

        // Dropped rows spring into their slot instead of jumping there from under the finger, and
        // come down while they do. They stay lifted until both are through, see [lift].
        settling = kind
        liftJob?.cancel()
        settleJob = scope.launch {
            try {
                settleOffset.snapTo(from)
                coroutineScope {
                    launch {
                        settleOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                    launch { lift.animateTo(0f, LIFT_SPEC) }
                }
            } finally {
                if (settling == kind) settling = null
            }
        }

        haptics?.performHapticFeedback(HapticFeedbackType.GestureEnd)
        if (active != activeBeforeDrag) emit()
    }

    /** How far a row is drawn from the slot the list has put it in. */
    fun translationOf(kind: ViewGroupingKind): Float = when (kind) {
        dragged -> item(kind)?.let { dragTop - it.offset } ?: 0f
        settling -> settleOffset.value
        else -> 0f
    }

    fun toggleSort(kind: ViewGroupingKind) {
        val next = !isReversed(kind)
        reversed[kind] = next
        haptics?.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        emit()
    }

    /**
     * Reversing is not an option of its own: picking the already picked field flips it, switching
     * fields starts from that field's natural order.
     */
    fun selectMailSort(kind: ViewSortingKind) {
        sorting = if (sorting.kind == kind) sorting.copy(reversed = !sorting.reversed) else ViewSorting(kind)
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
        emit()
    }

    /**
     * Puts the dragged category where its centre is: in the zone above or below the inactive
     * header, behind every row of that zone whose centre it has passed.
     */
    private fun reposition() {
        val kind = dragged ?: return
        val row = item(kind) ?: return
        val center = dragTop + row.size / 2f

        // Out of sight below means the header is further down than anything being dragged.
        val inactiveTop = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == INACTIVE_HEADER }?.offset?.toFloat() ?: Float.MAX_VALUE
        val toActive = center < inactiveTop
        val wasActive = kind in active

        val zone = (if (toActive) active else inactive) - kind
        val index = zone.count { other ->
            val otherRow = item(other) ?: return@count false
            otherRow.offset + otherRow.size / 2f < center
        }

        var nextActive = active - kind
        var nextInactive = inactive - kind
        var evicted = false
        if (toActive) {
            nextActive = nextActive.toMutableList().apply { add(index, kind) }
            // The active zone holds MAX_ACTIVE and no more, and a drag into a full one is not
            // refused: whoever is pushed past the end moves over to the inactive ones, which is
            // what makes the boundary feel like a shelf rather than a wall. The dragged one keeps
            // the place the finger gave it; the one beside it goes.
            if (nextActive.size > MAX_ACTIVE) {
                val out = if (nextActive.last() == kind) nextActive[nextActive.size - 2] else nextActive.last()
                nextActive = nextActive - out
                nextInactive = listOf(out) + nextInactive
                evicted = true
            }
        } else {
            nextInactive = nextInactive.toMutableList().apply { add(index, kind) }
        }

        if (nextActive == active && nextInactive == inactive) return
        active = nextActive
        inactive = nextInactive

        haptics?.performHapticFeedback(
            when {
                evicted -> HapticFeedbackType.Reject
                toActive && !wasActive -> HapticFeedbackType.ToggleOn
                !toActive && wasActive -> HapticFeedbackType.ToggleOff
                else -> HapticFeedbackType.SegmentTick
            }
        )
    }

    private fun item(kind: ViewGroupingKind): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == kind.name }

    private fun emit() {
        onChanged(
            GroupingSettings(
                groupings = active.map { ViewGrouping(kind = it, reversed = isReversed(it)) },
                sorting = sorting,
            )
        )
    }
}

@Composable
private fun LazyItemScope.CategoryItem(
    editor: GroupingEditor,
    kind: ViewGroupingKind,
    isActive: Boolean,
) {
    val category = CATEGORIES.first { it.kind == kind }
    val dragging = editor.dragged == kind

    val onDragStart = { _: Offset -> editor.startDrag(kind) }
    val onDrag = { change: PointerInputChange, amount: Offset ->
        change.consume()
        editor.dragBy(amount.y)
    }

    CategoryRow(
        category = category,
        isActive = isActive,
        reversed = editor.isReversed(kind),
        dragging = dragging,
        lift = editor.liftOf(kind),
        onClick = { editor.toggleSort(kind) },
        // A lifted row follows the finger or its own spring, not the list: no placement
        // animation, drawn above the others. Everything else slides aside as the list reorders.
        modifier = (if (editor.isLifted(kind)) Modifier.zIndex(1f) else Modifier.animateItem())
            .graphicsLayer {
                translationY = editor.translationOf(kind)
                val scale = 1f + 0.03f * editor.liftOf(kind)
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(kind) {
                detectDragGesturesAfterLongPress(
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = editor::endDrag,
                    onDragCancel = editor::endDrag,
                )
            },
        // The handle is there to drag by, so it picks the row up on touch rather than after the
        // finger has moved past the slop: nothing else on it could want the gesture.
        handleModifier = Modifier.pointerInput(kind) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                editor.startDrag(kind)
                try {
                    drag(down.id) { change ->
                        change.consume()
                        editor.dragBy(change.positionChange().y)
                    }
                } finally {
                    // Lifted, cancelled or the row left the composition: put it down either way.
                    editor.endDrag()
                }
            }
        },
    )
}

@Composable
private fun CategoryRow(
    category: GroupCategory,
    isActive: Boolean,
    reversed: Boolean,
    dragging: Boolean,
    /** How far it is raised, 0 to 1; see `GroupingEditor.lift`. */
    lift: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
) {
    val elevation = 8.dp * lift
    // Never transparent, not even on the way: a shadow under a see-through row shows as an
    // outline for the frames the colour takes to fill in. At rest it is the sheet's own colour.
    val color = lerp(
        MaterialTheme.colorScheme.surfaceContainerLow,
        MaterialTheme.colorScheme.surfaceContainerHighest,
        lift,
    )
    val handleTint by animateColorAsState(
        if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = color,
        shadowElevation = elevation,
    ) {
        Row(
            modifier = Modifier
                // Only an active category has an order to turn around.
                .then(if (isActive) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 32.dp)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = handleModifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhIcons.Regular.DotsSixVertical,
                    contentDescription = null,
                    tint = handleTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.name),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The web app says this in a tooltip; a finger has no hover, so it is written out.
                AnimatedVisibility(visible = isActive) {
                    SortLabel(sort = category.sort, reversed = reversed)
                }
            }
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                SortIcon(reversed = reversed)
            }
        }
    }
}

@Composable
private fun ZoneHeader(text: String, modifier: Modifier = Modifier) {
    // The active header counts, and the count changes under a drag.
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) { current ->
        Text(
            text = current,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * What an order says, in two parts that move on their own: the general part only fades when it is
 * another one, and only the direction rolls when the order is turned around -- "Nach Datum," stays
 * put while "neueste zuerst" rolls over to "älteste zuerst".
 */
@Composable
private fun SortLabel(sort: SortText, reversed: Boolean) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sort.general?.let { general ->
            AnimatedContent(
                targetState = stringResource(Res.string.home_grouping_sort_general, stringResource(general)),
                transitionSpec = { fadeIn(tween(SORT_ANIMATION_MS)) togetherWith fadeOut(tween(SORT_ANIMATION_MS)) },
            ) { current ->
                Text(text = current, style = style, color = color)
            }
        }
        AnimatedContent(
            targetState = stringResource(if (reversed) sort.backward else sort.forward),
            transitionSpec = { rollTransition(forward = reversed) },
        ) { current ->
            Text(text = current, style = style, color = color)
        }
    }
}

@Composable
private fun SortIcon(reversed: Boolean) {
    AnimatedContent(
        targetState = reversed,
        transitionSpec = { rollTransition(forward = targetState) },
    ) { isReversed ->
        Icon(
            imageVector = if (isReversed) PhIcons.Regular.SortDescending else PhIcons.Regular.SortAscending,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Turning a direction around rolls the old one out and the new one in, the way it points. Short
 * and a third of a line: it is a hint that something turned, not a scroll.
 */
private fun <S> AnimatedContentTransitionScope<S>.rollTransition(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return (fadeIn(tween(SORT_ANIMATION_MS)) + slideInVertically(tween(SORT_ANIMATION_MS)) { sign * it / 3 }) togetherWith
        (fadeOut(tween(SORT_ANIMATION_MS)) + slideOutVertically(tween(SORT_ANIMATION_MS)) { -sign * it / 3 }) using
        SizeTransform(clip = false)
}

private const val SORT_ANIMATION_MS = 180

/** Raising and lowering a row. Without bounce: a shadow below nothing is not a thing. */
private val LIFT_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Where an emptied zone still takes a drop, drawn as the dashed outline of a row. */
@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 56.dp)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The line down the left of the mail sorting, which ties it to the deepest category above. */
private fun Modifier.mailSortIndent(color: Color) = this
    .padding(start = 32.dp)
    .drawBehind {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

@Composable
private fun MailSortHeader(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.home_grouping_mail_sort),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .mailSortIndent(MaterialTheme.colorScheme.outlineVariant)
            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun MailSortRow(
    option: MailSort,
    sorting: ViewSorting,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = sorting.kind == option.kind
    val reversed = selected && sorting.reversed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .mailSortIndent(MaterialTheme.colorScheme.outlineVariant)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(option.name),
                style = MaterialTheme.typography.bodyMedium,
            )
            AnimatedVisibility(visible = selected) {
                SortLabel(sort = option.sort, reversed = reversed)
            }
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SortIcon(reversed = reversed)
        }
    }
}

/** One row of the list, whatever it shows. [key] is the lazy list's. */
private sealed interface ListEntry {
    val key: String

    data class Header(override val key: String, val active: Boolean) : ListEntry
    data class Empty(override val key: String, val active: Boolean) : ListEntry

    /** Keyed by the category alone, so it is the same row in either zone. */
    data class Category(val kind: ViewGroupingKind, val isActive: Boolean) : ListEntry {
        override val key: String get() = kind.name
    }

    data object MailSortHeader : ListEntry {
        override val key: String = MAIL_SORT_HEADER
    }

    data class MailSortOption(val option: MailSort) : ListEntry {
        override val key: String get() = MAIL_SORT_PREFIX + option.kind.name
    }
}

/**
 * What an order says: what is ordered, if there is anything to name, and which way round, per
 * direction. See [SortLabel].
 */
private data class SortText(
    val general: StringResource?,
    val forward: StringResource,
    val backward: StringResource,
)

private val NEWEST_FIRST = Res.string.home_grouping_sort_newest_first to Res.string.home_grouping_sort_oldest_first
private val A_TO_Z = Res.string.home_grouping_sort_az to Res.string.home_grouping_sort_za

private fun sortText(general: StringResource?, directions: Pair<StringResource, StringResource>) =
    SortText(general, directions.first, directions.second)

/** One category the listing can be cut by, as the list shows it. */
private data class GroupCategory(
    val kind: ViewGroupingKind,
    val name: StringResource,
    val icon: ImageVector,
    val sort: SortText,
)

private data class MailSort(
    val kind: ViewSortingKind,
    val name: StringResource,
    val sort: SortText,
)

/** How deep a listing may be cut. Past that the groups hold a handful of mails each. */
private const val MAX_ACTIVE = 4

private const val ACTIVE_HEADER = "header:active"
private const val ACTIVE_EMPTY = "empty:active"
private const val INACTIVE_HEADER = "header:inactive"
private const val INACTIVE_EMPTY = "empty:inactive"
private const val MAIL_SORT_HEADER = "header:mail-sort"
private const val MAIL_SORT_PREFIX = "mail-sort:"

private val CATEGORIES = listOf(
    GroupCategory(
        ViewGroupingKind.DateSmart, Res.string.home_grouping_category_date_smart, PhIcons.Regular.CalendarStar,
        sortText(Res.string.home_grouping_sort_by_date, NEWEST_FIRST),
    ),
    GroupCategory(
        ViewGroupingKind.Read, Res.string.home_grouping_category_read, PhIcons.Regular.Eyeglasses,
        sortText(null, Res.string.home_grouping_sort_read_first to Res.string.home_grouping_sort_unread_first),
    ),
    GroupCategory(
        ViewGroupingKind.Month, Res.string.home_grouping_category_month, PhIcons.Regular.CalendarDots,
        sortText(Res.string.home_grouping_sort_by_month, NEWEST_FIRST),
    ),
    GroupCategory(
        ViewGroupingKind.Year, Res.string.home_grouping_category_year, PhIcons.Regular.CalendarDot,
        sortText(Res.string.home_grouping_sort_by_year, NEWEST_FIRST),
    ),
    GroupCategory(
        ViewGroupingKind.Day, Res.string.home_grouping_category_day, PhIcons.Regular.Calendar,
        sortText(Res.string.home_grouping_sort_by_day, NEWEST_FIRST),
    ),
    GroupCategory(
        ViewGroupingKind.Sender, Res.string.home_grouping_category_sender, PhIcons.Regular.PersonSimple,
        sortText(Res.string.home_grouping_sort_sender, A_TO_Z),
    ),
    GroupCategory(
        ViewGroupingKind.ImapAccount, Res.string.home_grouping_category_imap_account, PhIcons.Regular.Users,
        sortText(Res.string.home_grouping_sort_account, A_TO_Z),
    ),
    GroupCategory(
        ViewGroupingKind.Archived, Res.string.home_grouping_category_archived, PhIcons.Regular.Archive,
        sortText(null, Res.string.home_grouping_sort_active_first to Res.string.home_grouping_sort_archived_first),
    ),
)

private val MAIL_SORTS = listOf(
    MailSort(
        ViewSortingKind.Date, Res.string.home_grouping_mail_sort_date,
        sortText(Res.string.home_grouping_sort_by_date, NEWEST_FIRST),
    ),
    MailSort(
        ViewSortingKind.Sender, Res.string.home_grouping_mail_sort_sender,
        sortText(Res.string.home_grouping_sort_sender, A_TO_Z),
    ),
    MailSort(
        ViewSortingKind.Subject, Res.string.home_grouping_mail_sort_subject,
        sortText(Res.string.home_grouping_sort_subject, A_TO_Z),
    ),
)

/**
 * One preview's frame. The state is held here and fed back, so dragging and tapping work in
 * interactive mode the way they do in the sheet.
 */
@Composable
private fun GroupingSettingsPreviewFrame(initial: ViewState, darkTheme: Boolean = false) {
    var viewState by remember { mutableStateOf(initial) }

    AppTheme(darkTheme = darkTheme, dynamicColor = false) {
        // The sheet's colour, which the rows are drawn in at rest.
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            GroupingSettingsContent(
                viewState = viewState,
                onGroupingSettingsChanged = {
                    viewState = viewState.copy(groupings = it.groupings, sorting = it.sorting)
                },
                modifier = Modifier.height(760.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            )
        }
    }
}

/** What the mailbox is grouped by in the web app: by date, newest first. */
@Composable
@Preview
private fun GroupingSettingsMailboxPreview() {
    GroupingSettingsPreviewFrame(ViewState.Mailbox)
}

/** Nothing groups: the active zone is only the place to drop one, and there is no mail sorting. */
@Composable
@Preview
private fun GroupingSettingsUngroupedPreview() {
    GroupingSettingsPreviewFrame(ViewState())
}

private val NESTED = ViewState(
    groupings = listOf(
        ViewGrouping(ViewGroupingKind.ImapAccount),
        ViewGrouping(ViewGroupingKind.Read, reversed = true),
        ViewGrouping(ViewGroupingKind.Month),
    ),
    sorting = ViewSorting(ViewSortingKind.Sender, reversed = true),
)

/** Three levels, some of them turned around, the mails by sender from Z. */
@Composable
@Preview
private fun GroupingSettingsNestedPreview() {
    GroupingSettingsPreviewFrame(NESTED)
}

@Composable
@Preview
private fun GroupingSettingsNestedDarkPreview() {
    GroupingSettingsPreviewFrame(NESTED, darkTheme = true)
}

/** As deep as it goes; a fifth category dragged in pushes one of these out. */
@Composable
@Preview
private fun GroupingSettingsFullPreview() {
    GroupingSettingsPreviewFrame(
        ViewState(
            groupings = listOf(
                ViewGrouping(ViewGroupingKind.Year),
                ViewGrouping(ViewGroupingKind.Month, reversed = true),
                ViewGrouping(ViewGroupingKind.Sender),
                ViewGrouping(ViewGroupingKind.Archived),
            ),
        )
    )
}
