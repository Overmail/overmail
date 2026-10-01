package es.jvbabi.overmail.page.home

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import es.jvbabi.overmail.page.home.components.search.ActiveSearchFilters
import es.jvbabi.overmail.page.home.components.search.SearchEvent
import es.jvbabi.overmail.page.home.components.search.SearchState
import es.jvbabi.overmail.page.home.components.search.SearchSuggestions
import es.jvbabi.overmail.page.home.components.search.SearchViewModel
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.LocalLiftState
import es.jvbabi.overmail.ui.lift.rememberLiftState
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_search_placeholder
import kotlin.uuid.Uuid

/** Blur at the very top and bottom edge, strong enough that the list behind turns into colour. */
private val EDGE_BLUR_RADIUS = 48.dp

/**
 * [focusSearch] puts the cursor into the search, once: [onSearchFocused] says it is done, and the
 * request is taken back. [openArchive] turns the view to the archive the same way, see
 * [onArchiveOpened]. [onOpenEmail] opens the page of a mail that was tapped.
 */
@Composable
fun ListScreen(
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    openArchive: Boolean = false,
    onArchiveOpened: () -> Unit = {},
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
    val searchViewModel = koinViewModel<SearchViewModel>()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(openArchive) {
        if (!openArchive) return@LaunchedEffect
        viewViewModel.onEvent(ViewEvent.SetViewState(ViewState.Archive))
        onArchiveOpened()
    }

    ListContent(
        liftState = LocalLiftState.current,
        homeState = homeState,
        viewState = viewState,
        searchState = searchState,
        content = content,
        viewSettingsState = viewSettingsState,
        listBodies = listBodies,
        onSearchEvent = searchViewModel::onEvent,
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
    searchState: SearchState,
    content: ViewContentState,
    viewSettingsState: ViewSettingsState,
    listBodies: Map<Uuid, StackCardBody>,
    onViewStateChange: (ViewState) -> Unit,
    onLoadListBody: (Uuid) -> Unit,
    onViewSettingsEvent: (ViewSettingsEvent) -> Unit,
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    onOpenEmail: (Uuid) -> Unit = {},
    onSearchEvent: (event: SearchEvent) -> Unit,
) {
    val localDensity = LocalDensity.current
    val hazeState = rememberHazeState()
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    var topHeight by remember { mutableStateOf(0.dp) }
    // Only a tint: an opaque edge would hide the blur exactly where it is strongest.
    val edgeTint = MaterialTheme.colorScheme.background

    val listPreview = remember(liftState) { ListMailPreview(liftState) }
    // A finger on a row may be a tap that opens the mail as well as a press that previews it:
    // either way what it says is wanted, and it is on its way before the finger is lifted.
    LaunchedEffect(listPreview.email?.id) { listPreview.email?.let { onLoadListBody(it.id) } }

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
            // the nav bar's. Held above the keyboard, so the suggestions end where it begins and
            // scroll from there instead of going on behind it.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))) {
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
                        value = searchState.query,
                        onValueChange = { onSearchEvent(SearchEvent.SetQuery(it)) },
                        placeholder = stringResource(Res.string.home_search_placeholder),
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = animateDpAsState(if (isSearchFocused) 16.dp else 0.dp).value)
                            .fillMaxWidth()
                            .onFocusChanged { isSearchFocused = it.hasFocus }
                            .focusRequester(searchFocus),
                    )

                    // Under the field rather than in the suggestions: what the search is on stays in
                    // sight once the keyboard is put away.
                    ActiveSearchFilters(state = searchState, onEvent = onSearchEvent)

                    AnimatedVisibility(
                        visible = isSearchFocused,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        // Whatever is left of the height, and no more than it needs.
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        SearchSuggestions(state = searchState, onEvent = onSearchEvent)
                    }

                    AnimatedVisibility(
                        visible = !isSearchFocused,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        ViewSettings(
                            viewState = viewState,
                            state = viewSettingsState,
                            onViewStateChange = onViewStateChange,
                            onEvent = onViewSettingsEvent,
                        )
                    }
                }
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
            searchState = SearchState(),
            onSearchEvent = {},
            content = PREVIEW_VIEW_CONTENT,
            viewSettingsState = ViewSettingsState(isFetchingImapAccounts = false),
            listBodies = emptyMap(),
            onViewStateChange = {},
            onLoadListBody = {},
            onViewSettingsEvent = {},
        )
    }
}

@Composable
@Preview
private fun ListContentSearchPreview() {
    AppTheme(dynamicColor = false) {
        ListContent(
            liftState = rememberLiftState(),
            homeState = HomeState(currentUser = PREVIEW_ACCOUNT),
            viewState = ViewState.MailboxWithArchive,
            searchState = SearchState(
                query = "test",
                suggestedLabels = PREVIEW_ITEMS.first().email.labels,
                activeLabels = PREVIEW_ITEMS.first().email.labels.take(1),
            ),
            onSearchEvent = {},
            content = PREVIEW_VIEW_CONTENT,
            viewSettingsState = ViewSettingsState(isFetchingImapAccounts = false),
            listBodies = emptyMap(),
            onViewStateChange = {},
            onLoadListBody = {},
            onViewSettingsEvent = {},
            focusSearch = true,
        )
    }
}
