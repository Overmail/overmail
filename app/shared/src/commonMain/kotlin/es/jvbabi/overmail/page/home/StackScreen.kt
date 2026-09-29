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
import es.jvbabi.overmail.hapticFirework
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.home.components.HEADER_HEIGHT
import es.jvbabi.overmail.page.home.components.HomeHeader
import es.jvbabi.overmail.page.home.components.stack.EmailStack
import es.jvbabi.overmail.page.home.components.stack.StackSwipe
import es.jvbabi.overmail.page.home.components.stack.StackToolbar
import es.jvbabi.overmail.page.home.components.stack.StackConfetti
import es.jvbabi.overmail.page.home.components.stack.StackEnd
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import es.jvbabi.overmail.page.home.components.stack.emailStackSwipe
import es.jvbabi.overmail.page.home.components.stack.rememberEmailStackState
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.LocalLiftState
import es.jvbabi.overmail.ui.lift.rememberLiftState
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_archive_failed
import overmail.app.shared.generated.resources.home_stack_undo_failed
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How long an unread mail lies on top of the pile until it counts as read. */
private val STACK_READ_AFTER = 2.seconds

/**
 * [onOpenEmail] opens the page of the mail on top, tapped twice; [onOpenArchive] the archive, from
 * a stack worked through.
 */
@Composable
fun StackScreen(onOpenEmail: (Uuid) -> Unit = {}, onOpenArchive: () -> Unit = {}) {
    val homeViewModel = koinViewModel<HomeViewModel>()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val emailStackViewModel = koinViewModel<EmailStackViewModel>()
    val stackContent by emailStackViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(emailStackViewModel) {
        emailStackViewModel.messages.collect { message ->
            when (message) {
                EmailStackMessage.ArchiveFailed -> snackbarHostState.showSnackbar(getString(Res.string.home_stack_archive_failed))
                EmailStackMessage.UndoFailed -> snackbarHostState.showSnackbar(getString(Res.string.home_stack_undo_failed))
            }
        }
    }

    StackContent(
        liftState = LocalLiftState.current,
        homeState = homeState,
        stackContent = stackContent,
        snackbarHostState = snackbarHostState,
        onStackEvent = emailStackViewModel::onEvent,
        onOpenEmail = onOpenEmail,
        onOpenArchive = onOpenArchive,
    )
}

@Composable
private fun StackContent(
    /** What a card is lifted by; its host lies over the whole app, see [LocalLiftState]. */
    liftState: LiftState,
    homeState: HomeState,
    stackContent: EmailStackContentState,
    snackbarHostState: SnackbarHostState,
    onStackEvent: (EmailStackEvent) -> Unit,
    onOpenEmail: (Uuid) -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
    val localDensity = LocalDensity.current
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    val emailStackState = rememberEmailStackState(liftState)

    val emailOnTop = stackContent.emails.firstOrNull()
    LaunchedEffect(emailOnTop?.id, emailOnTop?.isRead) {
        if (emailOnTop == null || emailOnTop.isRead) return@LaunchedEffect
        delay(STACK_READ_AFTER)
        onStackEvent(EmailStackEvent.Read(emailOnTop))
    }

    // Confetti only for a pile worked through here, not for one that was empty to begin with.
    val isDone = !stackContent.isLoading && emailStackState.isEmpty
    var hadMails by remember { mutableStateOf(false) }
    var celebrates by remember { mutableStateOf(false) }
    LaunchedEffect(isDone) {
        if (!isDone) hadMails = !stackContent.isLoading
        else if (hadMails) {
            celebrates = true
            hapticFirework()
        }
    }

    Scaffold { innerPadding ->
        val topOfStack = innerPadding.calculateTopPadding() + HEADER_HEIGHT
        Box(Modifier.fillMaxSize()) {
            EmailStack(
                emails = stackContent.emails,
                bodies = stackContent.bodies,
                state = emailStackState,
                onSwiped = { email, swipe ->
                    onStackEvent(
                        when (swipe) {
                            StackSwipe.Archive -> EmailStackEvent.Archive(email)
                            StackSwipe.Keep -> EmailStackEvent.Keep(email)
                        }
                    )
                },
                onOpen = { onOpenEmail(it.id) },
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

            // Where the pile was; over it rather than in it, so its button gets its touches.
            AnimatedVisibility(
                visible = isDone,
                enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topOfStack, bottom = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding())),
            ) {
                StackEnd(onOpenArchive = onOpenArchive, modifier = Modifier.fillMaxSize())
            }

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

            // Over the pile rather than in it: the pile takes every touch that lands on it.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxOf(bottomNavBarHeight, innerPadding.calculateBottomPadding())),
            ) {
                SnackbarHost(hostState = snackbarHostState)

                // Kept while it leaves, so it goes as it was rather than empty.
                val lastHandled = stackContent.handled.lastOrNull()
                var shownHandled by remember { mutableStateOf(lastHandled) }
                if (lastHandled != null) shownHandled = lastHandled
                fun <T> toolbarSpring() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy)
                AnimatedVisibility(
                    visible = lastHandled != null,
                    enter = fadeIn() + slideInVertically(toolbarSpring()) { it / 3 } + scaleIn(initialScale = .8f, animationSpec = toolbarSpring()),
                    exit = fadeOut() + slideOutVertically { it / 3 } + scaleOut(targetScale = .8f),
                ) {
                    val shown = shownHandled ?: return@AnimatedVisibility
                    StackToolbar(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .padding(horizontal = 32.dp),
                        last = shown,
                        done = stackContent.handled.size,
                        total = stackContent.handled.size + stackContent.emails.size,
                        onUndo = {
                            val undone = stackContent.handled.lastOrNull() ?: return@StackToolbar
                            emailStackState.bringBack(undone.email, undone.swipe)
                            onStackEvent(EmailStackEvent.Undo(undone.email))
                        },
                    )
                }
            }

            if (celebrates) StackConfetti(onEnded = { celebrates = false })
        }
    }
}

@Composable
@Preview
private fun StackContentPreview() {
    AppTheme(dynamicColor = false) {
        StackContent(
            liftState = rememberLiftState(),
            homeState = HomeState(currentUser = PREVIEW_ACCOUNT),
            stackContent = EmailStackContentState(emails = PREVIEW_ITEMS.map { it.email }, isLoading = false),
            snackbarHostState = remember { SnackbarHostState() },
            onStackEvent = {},
        )
    }
}
