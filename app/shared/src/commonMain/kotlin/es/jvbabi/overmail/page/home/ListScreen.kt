package es.jvbabi.overmail.page.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.home.components.HEADER_HEIGHT
import es.jvbabi.overmail.page.home.components.HomeHeader
import es.jvbabi.overmail.page.home.components.SearchField
import es.jvbabi.overmail.page.home.components.ViewSettings
import es.jvbabi.overmail.page.home.components.list.*
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.LocalLiftState
import es.jvbabi.overmail.ui.lift.rememberLiftState
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlin.uuid.Uuid

/** Blur at the very top and bottom edge, strong enough that the list behind turns into colour. */
private val EDGE_BLUR_RADIUS = 48.dp

/**
 * [focusSearch] puts the cursor into the search, once: [onSearchFocused] says it is done, and the
 * request is taken back. [onOpenEmail] opens the page of a mail that was tapped.
 */
@Composable
fun ListScreen(
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    onOpenEmail: (Uuid) -> Unit = {},
) {
    val homeViewModel = koinViewModel<HomeViewModel>()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val viewViewModel = koinViewModel<ViewViewModel>()
    val content by viewViewModel.content.collectAsStateWithLifecycle()
    val viewState by viewViewModel.viewState.collectAsStateWithLifecycle()
    val listBodies by viewViewModel.bodies.collectAsStateWithLifecycle()
    val viewSettingsViewModel = koinViewModel<ViewSettingsViewModel>()
    val viewSettingsState by viewSettingsViewModel.state.collectAsStateWithLifecycle()

    ListContent(
        liftState = LocalLiftState.current,
        homeState = homeState,
        viewState = viewState,
        content = content,
        viewSettingsState = viewSettingsState,
        listBodies = listBodies,
        onViewStateChange = { viewViewModel.onEvent(ViewEvent.SetViewState(it)) },
        onLoadListBody = { viewViewModel.onEvent(ViewEvent.LoadBody(it)) },
        onViewSettingsEvent = viewSettingsViewModel::onEvent,
        focusSearch = focusSearch,
        onSearchFocused = onSearchFocused,
        onOpenEmail = onOpenEmail,
    )
}

@Composable
private fun ListContent(
    /** What a card is lifted by; its host lies over the whole app, see [LocalLiftState]. */
    liftState: LiftState,
    homeState: HomeState,
    viewState: ViewState,
    content: ViewContentState,
    viewSettingsState: ViewSettingsState,
    listBodies: Map<Uuid, StackCardBody>,
    onViewStateChange: (ViewState) -> Unit,
    onLoadListBody: (Uuid) -> Unit,
    onViewSettingsEvent: (ViewSettingsEvent) -> Unit,
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    onOpenEmail: (Uuid) -> Unit = {},
) {
    val localDensity = LocalDensity.current
    val hazeState = rememberHazeState()
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    var topHeight by remember { mutableStateOf(0.dp) }
    // Only a tint: an opaque edge would hide the blur exactly where it is strongest.
    val edgeTint = MaterialTheme.colorScheme.background

    val listPreview = remember(liftState) { ListMailPreview(liftState) }

    // The mail last opened: its row alone grows into the page and back, see ViewItem. Saved, as
    // the list is composed anew on the way back.
    var sharedEmailId by rememberSaveable { mutableStateOf<String?>(null) }
    val openEmail = { id: Uuid ->
        sharedEmailId = id.toString()
        onOpenEmail(id)
    }
    val sharedEmail = sharedEmailId?.let(Uuid::parse)

    // Only before anything is there: a view that changed keeps showing the old mails meanwhile.
    val showsSkeleton = content.isLoading && content.results.isEmpty()
    val skeletonPulse = rememberSkeletonPulse()
    val reveal = remember { ListReveal() }
    reveal.update(showsSkeleton)

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (!focusSearch) return@LaunchedEffect
        searchFocus.requestFocus()
        onSearchFocused()
    }

    // While searching, the header makes room for the results.
    var isSearchFocused by remember { mutableStateOf(false) }
    // Putting the keyboard away is done with searching, though it leaves the focus where it was.
    val focusManager = LocalFocusManager.current
    val ime = WindowInsets.ime
    LaunchedEffect(focusManager, ime, localDensity) {
        snapshotFlow { ime.getBottom(localDensity) > 0 }
            .distinctUntilChanged()
            .drop(1)
            .filter { !it }
            .collect { focusManager.clearFocus() }
    }

    Scaffold { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                contentPadding = PaddingValues(
                    top = topHeight,
                    bottom = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding()),
                ),
            ) {
                if (showsSkeleton) itemsIndexed(LIST_SKELETON, contentType = { _, row -> row }) { _, row ->
                    ListSkeletonRow(row = row, pulse = skeletonPulse)
                }
                else itemsIndexed(content.results) { index, result ->
                    val modifier = Modifier.revealIn(reveal, index)
                    when (result) {
                        is ViewResult.Item -> Box(modifier) {
                            ViewItem(
                                item = result,
                                preview = listPreview,
                                onOpen = { openEmail(result.email.id) },
                                isShared = result.email.id == sharedEmail,
                            )
                        }
                        is ViewResult.Group -> ViewGroupComponent(
                            group = result,
                            senders = content.senders,
                            preview = listPreview,
                            onOpenEmail = openEmail,
                            sharedEmailId = sharedEmail,
                            modifier = modifier,
                        )
                    }
                }
            }

            // The header, the search and the filters in one: all of it at the top, the bottom is
            // the nav bar's.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .progressiveBackgroundBlur(hazeState = hazeState, direction = ProgressiveDirection.TopToBottom, backgroundColor = MaterialTheme.colorScheme.background, startRadius = EDGE_BLUR_RADIUS)
                    .progressiveBackground(edgeTint, ProgressiveDirection.TopToBottom)
                    .onSizeChanged { (_, h) ->
                        topHeight = with(localDensity) { h.toDp() }
                    }
                    .padding(top = innerPadding.calculateTopPadding(), bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = !isSearchFocused,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(HEADER_HEIGHT)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        HomeHeader(
                            currentUser = homeState.currentUser,
                            greeting = homeState.greeting,
                        )
                    }
                }

                SearchField(
                    value = "",
                    onValueChange = {},
                    placeholder = "Q2 Budget report",
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .onFocusChanged { isSearchFocused = it.hasFocus }
                        .focusRequester(searchFocus),
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
            liftState = rememberLiftState(),
            homeState = HomeState(currentUser = PREVIEW_ACCOUNT),
            viewState = ViewState.MailboxWithArchive,
            content = PREVIEW_VIEW_CONTENT,
            viewSettingsState = ViewSettingsState(isFetchingImapAccounts = false),
            listBodies = emptyMap(),
            onViewStateChange = {},
            onLoadListBody = {},
            onViewSettingsEvent = {},
        )
    }
}
