package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.home.components.HEADER_HEIGHT
import es.jvbabi.overmail.page.home.components.HomeHeader
import es.jvbabi.overmail.page.home.components.stack.EmailStack
import es.jvbabi.overmail.page.home.components.stack.StackSwipe
import es.jvbabi.overmail.page.home.components.stack.emailStackSwipe
import es.jvbabi.overmail.page.home.components.stack.rememberEmailStackState
import es.jvbabi.overmail.ui.lift.liftHost
import es.jvbabi.overmail.ui.lift.rememberLiftState
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_archive_failed
import kotlin.time.Duration.Companion.seconds

/** How long an unread mail lies on top of the pile until it counts as read. */
private val STACK_READ_AFTER = 2.seconds

@Composable
fun StackScreen() {
    val homeViewModel = koinViewModel<HomeViewModel>()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val emailStackViewModel = koinViewModel<EmailStackViewModel>()
    val stackContent by emailStackViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(emailStackViewModel) {
        emailStackViewModel.messages.collect { message ->
            when (message) {
                EmailStackMessage.ArchiveFailed -> snackbarHostState.showSnackbar(getString(Res.string.home_stack_archive_failed))
            }
        }
    }

    StackContent(
        homeState = homeState,
        stackContent = stackContent,
        snackbarHostState = snackbarHostState,
        onStackEvent = emailStackViewModel::onEvent,
    )
}

@Composable
private fun StackContent(
    homeState: HomeState,
    stackContent: EmailStackContentState,
    snackbarHostState: SnackbarHostState,
    onStackEvent: (EmailStackEvent) -> Unit,
) {
    val localDensity = LocalDensity.current
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    // One for the screen: whatever is lifted lies over all of it, see liftHost.
    val liftState = rememberLiftState()
    val emailStackState = rememberEmailStackState(liftState)

    val emailOnTop = stackContent.emails.firstOrNull()
    LaunchedEffect(emailOnTop?.id, emailOnTop?.isRead) {
        if (emailOnTop == null || emailOnTop.isRead) return@LaunchedEffect
        delay(STACK_READ_AFTER)
        onStackEvent(EmailStackEvent.Read(emailOnTop))
    }

    Scaffold { innerPadding ->
        val topOfStack = innerPadding.calculateTopPadding() + HEADER_HEIGHT
        // Over everything in it, the header included.
        Box(Modifier.fillMaxSize().liftHost(liftState)) {
            EmailStack(
                emails = stackContent.emails,
                bodies = stackContent.bodies,
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
                modifier = Modifier
                    .fillMaxSize()
                    // Below the header only, so its buttons still get their touches.
                    .emailStackSwipe(emailStackState, remember(topOfStack, localDensity) {
                        { position -> position.y > with(localDensity) { topOfStack.toPx() } }
                    }),
                contentPaddingValues = PaddingValues(
                    top = topOfStack,
                    bottom = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding()),
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
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

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding())),
            )
        }
    }
}

@Composable
@Preview
private fun StackContentPreview() {
    AppTheme(dynamicColor = false) {
        StackContent(
            homeState = HomeState(currentUser = PREVIEW_ACCOUNT),
            stackContent = EmailStackContentState(emails = PREVIEW_ITEMS.map { it.email }, isLoading = false),
            snackbarHostState = remember { SnackbarHostState() },
            onStackEvent = {},
        )
    }
}
