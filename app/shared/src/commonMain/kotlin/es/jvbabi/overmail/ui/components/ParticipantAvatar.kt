package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import es.jvbabi.overmail.domain.model.Participant

/**
 * A correspondent's picture, shown as it is -- a logo keeps its shape. While there is none, or
 * while it is on its way, round initials stand in for it.
 *
 * Loaded at the size it is shown and kept in the image loader's disk cache, keyed by its path: a
 * new picture is a new path, so nothing stale is ever shown and nothing is asked for twice.
 *
 * @param participant null while they are not in the cache yet; [fallbackName] gives the initials.
 */
@Composable
fun ParticipantAvatar(
    participant: Participant?,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackName: String = participant?.displayName.orEmpty(),
) {
    val path = participant?.avatarUrl
    var loaded by remember(path) { mutableStateOf(false) }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // Under a picture that is not square or has transparent parts they would show through,
        // so they only stand in until it has loaded.
        if (!loaded) Initials(name = fallbackName, size = size)

        if (participant != null && path != null) {
            val context = LocalPlatformContext.current
            val account = participant.overmailAccount
            val request = remember(account.homeserver, path, account.token) {
                val key = account.homeserver + path
                ImageRequest.Builder(context)
                    .data(account.homeserver.trimEnd('/') + path)
                    // Behind the session like everything else on the homeserver.
                    .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer ${account.token}").build())
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

@Composable
private fun Initials(name: String, size: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() },
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Up to two initials, the web's `initials()`: from the name's words, or the address's parts. */
private fun initials(nameOrAddress: String): String = nameOrAddress
    .split(Regex("[\\s.@_-]+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
