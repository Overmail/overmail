package es.jvbabi.overmail.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import com.phosphor.icons.regular.TreeStructure
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.page.home.components.filter.Chip
import es.jvbabi.overmail.page.home.components.filter.ViewController
import es.jvbabi.overmail.page.home.components.group.GroupModal
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_grouping_title

@Composable
fun HomeScreen() {
    HomeContent()
}

@Composable
private fun HomeContent() {
    var viewState by remember { mutableStateOf(ViewState.Mailbox) }

    var showGroupSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp + innerPadding.calculateBottomPadding())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = "",
                    onValueChange = {},
                    leadingIcon = {
                        Icon(
                            imageVector = PhIcons.Regular.MagnifyingGlass,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    placeholder = { Text("Q2 Budget report") },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(percent = 50),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(
                        text = stringResource(Res.string.home_grouping_title),
                        leading = {
                            Icon(
                                imageVector = PhIcons.Regular.TreeStructure,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = { showGroupSettings = true }
                    )

                    Spacer(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(32.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    ViewController(
                        viewState = viewState,
                        onFilterChange = { viewState = viewState.copy(filter = it) },
                    )
                }
            }
        }
    }

    GroupModal(
        visible = showGroupSettings,
        viewState = viewState,
        onGroupingSettingsChanged = {
            viewState = viewState.copy(groupings = it.groupings, sorting = it.sorting)
        },
        onDismiss = { showGroupSettings = false },
    )
}

@Composable
@Preview
private fun HomeContentPreview() {
    HomeContent()
}
