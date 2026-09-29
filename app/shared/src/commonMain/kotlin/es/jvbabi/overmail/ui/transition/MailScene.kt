package es.jvbabi.overmail.ui.transition

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Marks a NavEntry as a mail's page, see [MailSceneStrategy]. */
object MailPageEntry {
    internal const val KEY = "overmail.mail_page"

    val metadata: Map<String, Any> = mapOf(KEY to true)
}

/**
 * Shows an entry marked with [MailPageEntry] as a [MailScene], over the screen it was opened from.
 * Put it before every other strategy.
 */
class MailSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val entry = entries.lastOrNull()?.takeIf { it.metadata.containsKey(MailPageEntry.KEY) } ?: return null
        // Something has to lie below it; a mail's page is never the only screen.
        if (entries.size < 2) return null
        return MailScene(key = entry.contentKey, entry = entry, previousEntries = entries.dropLast(1), onBack = onBack)
    }
}

/**
 * A mail's page over the screen it was opened from, which stays composed and drawn below it the
 * whole time: going back has nothing to build, so the page starts to shrink back into its row the
 * moment back is pressed, and a predictive back follows the finger from its first move.
 *
 * The page's transitions are its own rather than the NavDisplay's, which does not animate an
 * overlay: it comes in on its first composition, a back gesture drives it, and it goes out in
 * [onRemove] once it is off the back stack. How far it is, is its [LocalScreenProgress], which
 * [MailTransition] draws the way from.
 */
internal class MailScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val overlaidEntries: List<NavEntry<T>> = previousEntries

    /** 0 before the page comes in and once it has gone, 1 while it is all there. */
    private val progress = Animatable(0f)

    /** Off the back stack and on its way out: another back is the screen below's again. */
    private var isLeaving by mutableStateOf(false)

    override val content: @Composable () -> Unit = {
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            progress.animateTo(1f, tween(MAIL_TRANSITION_MILLIS, easing = LinearEasing))
        }

        val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        LaunchedEffect(backState) {
            snapshotFlow { backState.transitionState }.collect { gesture ->
                if (gesture is NavigationEventTransitionState.InProgress) progress.snapTo(1f - gesture.latestEvent.progress)
            }
        }
        NavigationBackHandler(
            state = backState,
            isBackEnabled = !isLeaving,
            // Let go before it counted: back to where the page is all there.
            onBackCancelled = { scope.launch { progress.animateTo(1f, tween(remaining(1f), easing = LinearEasing)) } },
            onBackCompleted = {
                isLeaving = true
                onBack()
            },
        )

        val read = remember { { progress.value } }
        CompositionLocalProvider(LocalScreenProgress provides read) {
            entry.Content()
        }
    }

    override suspend fun onRemove() {
        isLeaving = true
        progress.animateTo(0f, tween(remaining(0f), easing = LinearEasing))
    }

    /** How long the rest of the way to [target] takes, at the pace of a whole transition. */
    private fun remaining(target: Float): Int =
        (kotlin.math.abs(target - progress.value) * MAIL_TRANSITION_MILLIS).roundToInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MailScene<*>) return false
        return key == other.key && entry == other.entry && previousEntries == other.previousEntries
    }

    override fun hashCode(): Int = (key.hashCode() * 31 + entry.hashCode()) * 31 + previousEntries.hashCode()

    override fun toString(): String = "MailScene(key=$key, entry=$entry, previousEntries=$previousEntries)"
}
