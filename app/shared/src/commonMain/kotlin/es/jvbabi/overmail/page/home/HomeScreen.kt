package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.components.HomeHeader
import es.jvbabi.overmail.page.home.components.ViewSettings
import es.jvbabi.overmail.page.home.components.list.ViewGroupComponent
import es.jvbabi.overmail.page.home.components.stack.EmailStack
import es.jvbabi.overmail.page.home.components.stack.StackSwipe
import es.jvbabi.overmail.page.home.components.stack.emailStackSwipe
import es.jvbabi.overmail.page.home.components.stack.rememberEmailStackState
import es.jvbabi.overmail.page.home.components.stack.rememberStackSnapFlingBehavior
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun HomeScreen() {
    val homeViewModel = koinViewModel<HomeViewModel>()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val viewViewModel = koinViewModel<ViewViewModel>()
    val content by viewViewModel.content.collectAsStateWithLifecycle()
    val viewState by viewViewModel.viewState.collectAsStateWithLifecycle()
    val viewSettingsViewModel = koinViewModel<ViewSettingsViewModel>()
    val viewSettingsState by viewSettingsViewModel.state.collectAsStateWithLifecycle()
    val emailStackViewModel = koinViewModel<EmailStackViewModel>()
    val stackContent by emailStackViewModel.state.collectAsStateWithLifecycle()

    HomeContent(
        homeState = homeState,
        viewState = viewState,
        content = content,
        viewSettingsState = viewSettingsState,
        stackContent = stackContent,
        onViewStateChange = { viewViewModel.onEvent(ViewEvent.SetViewState(it)) },
        onViewSettingsEvent = viewSettingsViewModel::onEvent,
        onStackEvent = emailStackViewModel::onEvent,
    )
}

val HEADER_HEIGHT = 64.dp

/** How much of the listing shows below the pile while the pile is up. */
private val LIST_PEEK_BELOW_STACK = 92.dp

/** Blur at the very top and bottom edge, strong enough that the list behind turns into colour. */
private val EDGE_BLUR_RADIUS = 48.dp

@Composable
private fun HomeContent(
    homeState: HomeState,
    viewState: ViewState,
    content: ViewContentState,
    viewSettingsState: ViewSettingsState,
    stackContent: EmailStackContentState,
    onViewStateChange: (ViewState) -> Unit,
    onViewSettingsEvent: (ViewSettingsEvent) -> Unit,
    onStackEvent: (EmailStackEvent) -> Unit,
) {
    val localDensity = LocalDensity.current
    val hazeState = rememberHazeState()
    var bottomHeight by remember { mutableStateOf(0.dp) }
    // Only a tint: an opaque edge would hide the blur exactly where it is strongest.
    val edgeTint = MaterialTheme.colorScheme.background

    var containerHeight by remember { mutableStateOf(0.dp) }

    val emailsListState = rememberLazyListState()
    val emailStackState = rememberEmailStackState()

    Scaffold{ innerPadding ->
        val topOfStack = innerPadding.calculateTopPadding() + HEADER_HEIGHT
        // How far the listing scrolls until it covers the pile, which is what the pile is tall.
        val stackHeight = (containerHeight - bottomHeight - LIST_PEEK_BELOW_STACK - HEADER_HEIGHT - innerPadding.calculateTopPadding() - innerPadding.calculateBottomPadding()).coerceAtLeast(0.dp)
        val stackHeightPx = with(localDensity) { stackHeight.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { (_, h) ->
                    containerHeight = with(localDensity) { h.toDp() }
                }
        ) {
            val verticalPercentageOfStackVisible by remember(stackHeightPx) {
                derivedStateOf {
                    if (emailsListState.firstVisibleItemIndex > 0 || stackHeightPx <= 0f) return@derivedStateOf 0f
                    (1 - emailsListState.firstVisibleItemScrollOffset / stackHeightPx).coerceIn(0f, 1f)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    // The listing lies on top of the pile and takes every touch, so the swipe is
                    // picked up here. Only where the pile is not covered: below the header and
                    // above the listing, which scrolls up over it.
                    .emailStackSwipe(emailStackState, remember(topOfStack, stackHeight, localDensity) {
                        { position ->
                            val stackTop = with(localDensity) { topOfStack.toPx() }
                            val topOfList = stackTop + with(localDensity) { stackHeight.toPx() } - emailsListState.firstVisibleItemScrollOffset
                            emailsListState.firstVisibleItemIndex == 0 && position.y > stackTop && position.y < topOfList
                        }
                    })
            ) {
                EmailStack(
                    emails = stackContent.emails,
                    isLoading = stackContent.isLoading,
                    state = emailStackState,
                    onSwiped = { email, swipe ->
                        onStackEvent(
                            when (swipe) {
                                StackSwipe.Archive -> EmailStackEvent.Archive(email)
                                StackSwipe.Keep -> EmailStackEvent.Keep(email)
                            }
                        )
                    },
                    // In the layer, not in composition: read there, the scroll position would
                    // recompose the whole screen on every frame of a scroll.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = verticalPercentageOfStackVisible
                            scaleX = verticalPercentageOfStackVisible * 0.15f + 0.85f
                            scaleY = scaleX
                        },
                    contentPaddingValues = PaddingValues(
                        top = topOfStack,
                        bottom = innerPadding.calculateBottomPadding() + bottomHeight + LIST_PEEK_BELOW_STACK
                    )
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = topOfStack + stackHeight,
                        bottom = bottomHeight,
                    ),
                    state = emailsListState,
                    flingBehavior = rememberStackSnapFlingBehavior(emailsListState, stackHeightPx),
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

            if (verticalPercentageOfStackVisible < 1f) Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(x = 0, y = (with(localDensity) { 92.dp.toPx() } * verticalPercentageOfStackVisible).roundToInt()) }
                    .fillMaxWidth()
                    .alpha(1-verticalPercentageOfStackVisible)
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
                    state = viewSettingsState,
                    onViewStateChange = onViewStateChange,
                    onEvent = onViewSettingsEvent,
                )
            }
        }
    }
}

@Composable
@Preview
private fun HomeContentPreview() {
    AppTheme(dynamicColor = false) {
        HomeContent(
            homeState = HomeState(
                currentUser = PREVIEW_ACCOUNT,
            ),
            viewState = ViewState.Mailbox,
            content = PREVIEW_VIEW_CONTENT,
            viewSettingsState = ViewSettingsState(isFetchingImapAccounts = false),
            stackContent = EmailStackContentState(emails = PREVIEW_ITEMS.map { it.email }, isLoading = false),
            onViewStateChange = {},
            onViewSettingsEvent = {},
            onStackEvent = {},
        )
    }
}
