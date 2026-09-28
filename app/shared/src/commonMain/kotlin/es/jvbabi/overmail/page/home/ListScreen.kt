package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.home.components.HEADER_HEIGHT
import es.jvbabi.overmail.page.home.components.HomeHeader
import es.jvbabi.overmail.page.home.components.ViewSettings
import es.jvbabi.overmail.page.home.components.list.ListMailPreview
import es.jvbabi.overmail.page.home.components.list.ListMailPreviewCard
import es.jvbabi.overmail.page.home.components.list.ViewGroupComponent
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.ui.lift.liftHost
import es.jvbabi.overmail.ui.lift.rememberLiftState
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/** Blur at the very top and bottom edge, strong enough that the list behind turns into colour. */
private val EDGE_BLUR_RADIUS = 48.dp

@Composable
fun ListScreen() {
    val homeViewModel = koinViewModel<HomeViewModel>()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val viewViewModel = koinViewModel<ViewViewModel>()
    val content by viewViewModel.content.collectAsStateWithLifecycle()
    val viewState by viewViewModel.viewState.collectAsStateWithLifecycle()
    val listBodies by viewViewModel.bodies.collectAsStateWithLifecycle()
    val viewSettingsViewModel = koinViewModel<ViewSettingsViewModel>()
    val viewSettingsState by viewSettingsViewModel.state.collectAsStateWithLifecycle()

    ListContent(
        homeState = homeState,
        viewState = viewState,
        content = content,
        viewSettingsState = viewSettingsState,
        listBodies = listBodies,
        onViewStateChange = { viewViewModel.onEvent(ViewEvent.SetViewState(it)) },
        onLoadListBody = { viewViewModel.onEvent(ViewEvent.LoadBody(it)) },
        onViewSettingsEvent = viewSettingsViewModel::onEvent,
    )
}

@Composable
private fun ListContent(
    homeState: HomeState,
    viewState: ViewState,
    content: ViewContentState,
    viewSettingsState: ViewSettingsState,
    listBodies: Map<Uuid, StackCardBody>,
    onViewStateChange: (ViewState) -> Unit,
    onLoadListBody: (Uuid) -> Unit,
    onViewSettingsEvent: (ViewSettingsEvent) -> Unit,
) {
    val localDensity = LocalDensity.current
    val hazeState = rememberHazeState()
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    var bottomHeight by remember { mutableStateOf(0.dp) }
    // Only a tint: an opaque edge would hide the blur exactly where it is strongest.
    val edgeTint = MaterialTheme.colorScheme.background

    // One for the screen: whatever is lifted lies over all of it, see liftHost.
    val liftState = rememberLiftState()
    val listPreview = remember(liftState) { ListMailPreview(liftState) }

    Scaffold { innerPadding ->
        val bottomBar = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding())
        // Over everything in it, the header and the search included.
        Box(Modifier.fillMaxSize().liftHost(liftState)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + HEADER_HEIGHT,
                    bottom = bottomHeight,
                ),
            ) {
                items(content.results) { result ->
                    when (result) {
                        is ViewResult.Item -> Text("Email ${result.email.subject}")
                        is ViewResult.Group -> ViewGroupComponent(
                            group = result,
                            senders = content.senders,
                            preview = listPreview,
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
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                HomeHeader(
                    currentUser = homeState.currentUser,
                    greeting = homeState.greeting,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { (_, h) ->
                        bottomHeight = with(localDensity) { h.toDp() }
                    }
                    // Above the nav bar; with the keyboard up, above that instead.
                    .consumeWindowInsets(PaddingValues(bottom = bottomBar))
                    .padding(bottom = bottomBar + 8.dp)
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
                    state = viewSettingsState,
                    onViewStateChange = onViewStateChange,
                    onEvent = onViewSettingsEvent,
                )
            }

            ListMailPreviewCard(preview = listPreview, bodies = listBodies, onLoadBody = onLoadListBody)
        }
    }
}

@Composable
@Preview
private fun ListContentPreview() {
    AppTheme(dynamicColor = false) {
        ListContent(
            homeState = HomeState(currentUser = PREVIEW_ACCOUNT),
            viewState = ViewState.Mailbox,
            content = PREVIEW_VIEW_CONTENT,
            viewSettingsState = ViewSettingsState(isFetchingImapAccounts = false),
            listBodies = emptyMap(),
            onViewStateChange = {},
            onLoadListBody = {},
            onViewSettingsEvent = {},
        )
    }
}
