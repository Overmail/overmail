package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.bold.ArchiveBold
import com.phosphor.icons.bold.ConfettiBold
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.baseColors
import io.github.vinceglb.confettikit.compose.ConfettiKit
import io.github.vinceglb.confettikit.core.Angle
import io.github.vinceglb.confettikit.core.Party
import io.github.vinceglb.confettikit.core.Position
import io.github.vinceglb.confettikit.core.Spread
import io.github.vinceglb.confettikit.core.emitter.Emitter
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_empty
import overmail.app.shared.generated.resources.home_stack_empty_description
import overmail.app.shared.generated.resources.home_stack_open_archive
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the pile was, once there is nothing left on it: that it is done, and a way on to what was
 * archived, [onOpenArchive].
 */
@Composable
fun StackEnd(onOpenArchive: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.baseColors.emerald
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(colors.container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PhIcons.Bold.ConfettiBold,
                contentDescription = null,
                tint = colors.onContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.home_stack_empty),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.home_stack_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onOpenArchive) {
            Icon(
                imageVector = PhIcons.Bold.ArchiveBold,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.home_stack_open_archive))
        }
    }
}

/** The web app's confetti colors, and the app's green of an archived mail. */
private val CONFETTI_COLORS = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def, 0x34d399)

/**
 * A burst of confetti over the whole screen for a pile that was just worked through: shot up from
 * both bottom corners, and popping where the pile was. [onEnded] once the last piece is gone.
 */
@Composable
fun StackConfetti(onEnded: () -> Unit, modifier: Modifier = Modifier) {
    val parties = remember {
        val corner = Party(
            speed = 30f,
            maxSpeed = 60f,
            damping = 0.9f,
            angle = Angle.TOP + 30,
            spread = Spread.WIDE / 2,
            colors = CONFETTI_COLORS,
            timeToLive = 3000,
            emitter = Emitter(duration = 250.milliseconds).max(120),
            position = Position.Relative(0.0, 1.0),
        )
        listOf(
            corner,
            corner.copy(angle = Angle.TOP - 30, position = Position.Relative(1.0, 1.0)),
            Party(
                speed = 0f,
                maxSpeed = 30f,
                damping = 0.9f,
                spread = Spread.ROUND,
                colors = CONFETTI_COLORS,
                delay = 150,
                emitter = Emitter(duration = 100.milliseconds).max(100),
                position = Position.Relative(0.5, 0.4),
            ),
        )
    }
    ConfettiKit(
        modifier = modifier.fillMaxSize(),
        parties = parties,
        onParticleSystemEnded = { _, active -> if (active == 0) onEnded() },
    )
}

@Preview
@Composable
private fun StackEndPreview() {
    AppTheme(dynamicColor = false) {
        StackEnd(onOpenArchive = {})
    }
}
