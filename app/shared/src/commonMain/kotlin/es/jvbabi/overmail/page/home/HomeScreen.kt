package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.components.ViewSettings
import es.jvbabi.overmail.page.home.components.list.ViewGroupComponent
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen() {
    val viewViewModel = koinViewModel<ViewViewModel>()
    val content by viewViewModel.content.collectAsStateWithLifecycle()
    val viewState by viewViewModel.viewState.collectAsStateWithLifecycle()

    HomeContent(
        viewState = viewState,
        content = content,
        onViewStateChange = { viewViewModel.onEvent(ViewEvent.SetViewState(it)) },
    )
}

val HEADER_HEIGHT = 64.dp

/** Blur at the very top and bottom edge, strong enough that the list behind turns into colour. */
private val EDGE_BLUR_RADIUS = 48.dp
private const val EDGE_TINT_ALPHA = 0.5f

@Composable
private fun HomeContent(
    viewState: ViewState,
    content: ViewContentState,
    onViewStateChange: (ViewState) -> Unit,
) {
    val localDensity = LocalDensity.current
    val hazeState = rememberHazeState()
    var bottomHeight by remember { mutableStateOf(0.dp) }
    // Only a tint: an opaque edge would hide the blur exactly where it is strongest.
    val edgeTint = MaterialTheme.colorScheme.background.copy(alpha = EDGE_TINT_ALPHA)
    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + HEADER_HEIGHT,
                    // Measured with its padding, so the bottom insets are already in there.
                    bottom = bottomHeight,
                )
            ) {
                items(content.results) { result ->
                    when (result) {
                        is ViewResult.Item -> Text("Email ${result.email.subject}")
                        is ViewResult.Group -> ViewGroupComponent(
                            group = result,
                            senders = content.senders,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .progressiveBackgroundBlur(hazeState = hazeState, direction = ProgressiveDirection.TopToBottom, backgroundColor = MaterialTheme.colorScheme.background, startRadius = EDGE_BLUR_RADIUS)
                    .progressiveBackground(edgeTint, ProgressiveDirection.TopToBottom)
                    .padding(top = innerPadding.calculateTopPadding())
                    .height(HEADER_HEIGHT)
            ) {
                Text("Head")
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .progressiveBackgroundBlur(hazeState = hazeState, direction = ProgressiveDirection.BottomToTop, backgroundColor = MaterialTheme.colorScheme.background, startRadius = EDGE_BLUR_RADIUS)
                    .progressiveBackground(edgeTint, ProgressiveDirection.BottomToTop)
                    .onSizeChanged { (_, h) ->
                        bottomHeight = with(localDensity) { h.toDp() }
                    }
                    .consumeWindowInsets(innerPadding)
                    .padding(bottom = innerPadding.calculateBottomPadding())
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

                ViewSettings(
                    viewState = viewState,
                    onViewStateChange = onViewStateChange,
                )
            }
        }
    }
}

@Composable
@Preview
private fun HomeContentPreview() {
    AppTheme(dynamicColor = false) {
        HomeContent(viewState = ViewState.Mailbox, content = PREVIEW_VIEW_CONTENT, onViewStateChange = {})
    }
}
