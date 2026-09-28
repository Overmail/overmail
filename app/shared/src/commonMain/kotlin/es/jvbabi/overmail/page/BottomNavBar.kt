package es.jvbabi.overmail.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.filled.ListBulletsFill
import com.phosphor.icons.filled.StackFill
import com.phosphor.icons.regular.ListBullets
import com.phosphor.icons.regular.PaperPlaneTilt
import com.phosphor.icons.regular.Stack
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_nav_list
import overmail.app.shared.generated.resources.home_nav_stack

/**
 * How much of the bottom edge the [BottomNavBar] covers, the system bar below it included. The
 * bar floats over the screens, so a screen keeps its own content clear of it with this.
 */
val LocalBottomNavBarHeight = compositionLocalOf<Dp> { 0.dp }

/** In the order the bar shows them. */
private val TABS = listOf(Screen.Stack, Screen.List)

private val NAV_ITEM_WIDTH = 92.dp
private val NAV_ITEM_HEIGHT = 64.dp
private val NAV_ITEM_SPACING = 4.dp

/** Lifts the bar off whatever scrolls beneath it. */
private val NAV_BAR_ELEVATION = 6.dp

@Composable
fun BottomNavBar(
    selected: Screen.Tab,
    onSelect: (Screen.Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .shadow(NAV_BAR_ELEVATION, RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp)
        ) {
            // Behind the items rather than on one of them, so it can slide from one to the next.
            val indicatorOffset by animateDpAsState(
                targetValue = (NAV_ITEM_WIDTH + NAV_ITEM_SPACING) * TABS.indexOf(selected),
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            )
            Box(
                Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .size(NAV_ITEM_WIDTH, NAV_ITEM_HEIGHT)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NAV_ITEM_SPACING)) {
                NavItem(
                    label = Res.string.home_nav_stack,
                    icon = PhIcons.Regular.Stack,
                    selectedIcon = PhIcons.Filled.StackFill,
                    isSelected = selected == Screen.Stack,
                    onClick = { onSelect(Screen.Stack) },
                )
                NavItem(
                    label = Res.string.home_nav_list,
                    icon = PhIcons.Regular.ListBullets,
                    selectedIcon = PhIcons.Filled.ListBulletsFill,
                    isSelected = selected == Screen.List,
                    onClick = { onSelect(Screen.List) },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .shadow(NAV_BAR_ELEVATION, RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp)
        ) {
            IconButton(
                onClick = {},
                modifier = Modifier
                    .size(64.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = PhIcons.Regular.PaperPlaneTilt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    label: StringResource,
    icon: ImageVector,
    selectedIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    Column(
        Modifier
            .size(NAV_ITEM_WIDTH, NAV_ITEM_HEIGHT)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isSelected) selectedIcon else icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(label),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
@Preview
private fun BottomNavBarPreview() {
    AppTheme(dynamicColor = false) {
        BottomNavBar(selected = Screen.Stack, onSelect = {})
    }
}
