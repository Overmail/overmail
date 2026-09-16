package es.jvbabi.overmail.ui.overlay.update_available

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ThemeWrapper
import es.jvbabi.overmail.domain.model.AppVersions
import es.jvbabi.overmail.domain.model.Feature
import es.jvbabi.overmail.domain.model.Version
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*

/** Corner radius of the block the changelog sits in, matching the app icon above it. */
private val ChangelogCornerSize = 16.dp

/**
 * Corner radius of a single release inside that block.
 *
 * Tight on purpose: the releases are segments of one block, not cards of their own. Only where a
 * segment meets the outside does the block's own radius win, which is what makes the run of them
 * read as a single shape.
 */
private val SegmentCornerSize = 4.dp

/** Gap between two releases — a seam, wide enough to tell them apart and no wider. */
private val SegmentSpacing = 2.dp

/** Padding between a release and its content. */
private val ChangelogPadding = 16.dp

private val GroupSpacing = 12.dp
private val EntrySpacing = 8.dp

/** Width reserved for an issue link, so the entries next to it line up. */
private val IssueLinkMinWidth = 36.dp

/**
 * What every release between the running build and the update changed, newest release first.
 *
 * One segment per release, run together into a single block: the outer corners are the block's, the
 * seams between them are barely rounded. Scrolls inside whatever height the caller hands it, so the
 * block passes under whatever floats above it — [bottomPadding] has to be worth that height, or the
 * last entry can never be scrolled out from under it.
 *
 * Versions without a single entry are the caller's job to filter out; this renders every version it
 * is given, including an empty heading for one that has nothing to say.
 *
 * @param onIssueClick called with the issue number behind an entry when its link is tapped.
 * @param bottomPadding extra room below the last segment, for whatever floats above the list.
 */
@Composable
fun ChangelogList(
    versions: List<Version>,
    onIssueClick: (issue: Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    Column(
        modifier = modifier
            // The block's own shape, and the only thing that carries it: the segments below are
            // square-ish, and it is this clip that rounds off the ones at either end.
            .clip(RoundedCornerShape(ChangelogCornerSize))
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(SegmentSpacing),
    ) {
        versions.forEach { version ->
            ChangelogVersion(
                version = version,
                onIssueClick = onIssueClick,
            )
        }
    }
}

/**
 * Modest hint that the changelog is still on its way.
 *
 * The list is fetched after the overlay is already up, and it is a nice-to-have rather than the
 * reason the overlay is there - so it gets a single line instead of a skeleton of the real thing.
 */
@Composable
fun ChangelogLoadingPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(Res.string.update_changelog_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A single release as its own segment: its name, followed by every group that has entries. */
@Composable
private fun ChangelogVersion(
    version: Version,
    onIssueClick: (issue: Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SegmentCornerSize),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(ChangelogPadding),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        ) {
            Text(
                text = AppVersions.tagOf(version.name),
                style = MaterialTheme.typography.titleMedium,
            )

            if (version.features.isNotEmpty()) ChangelogGroup(title = stringResource(Res.string.update_changelog_features)) {
                version.features.forEach { (issue, feature) ->
                    ChangelogEntry(issue = issue, onIssueClick = onIssueClick) {
                        Text(
                            text = feature.title,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (version.bugfixes.isNotEmpty()) ChangelogGroup(title = stringResource(Res.string.update_changelog_fixes)) {
                version.bugfixes.forEach { (issue, description) ->
                    ChangelogEntry(issue = issue, onIssueClick = onIssueClick) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (version.tasks.isNotEmpty()) ChangelogGroup(title = stringResource(Res.string.update_changelog_tasks)) {
                version.tasks.forEach { (issue, description) ->
                    ChangelogEntry(issue = issue, onIssueClick = onIssueClick) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** One kind of change inside a release, e.g. its bugfixes. */
@Composable
private fun ChangelogGroup(
    title: String,
    entries: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EntrySpacing)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries()
    }
}

/** A single change, led by the link to the issue it was tracked in. */
@Composable
private fun ChangelogEntry(
    issue: Int,
    onIssueClick: (issue: Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(EntrySpacing)) {
        IssueLink(
            issue = issue,
            onClick = { onIssueClick(issue) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/**
 * The issue number as a link, e.g. `#17`.
 *
 * Styled as a link rather than as a button: it is a reference next to the text it belongs to, and
 * anything with a border or a background would outweigh the entry itself.
 */
@Composable
private fun IssueLink(
    issue: Int,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.update_changelog_issue, HumanReadable.number(issue)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        maxLines = 1,
        modifier = Modifier
            .widthIn(min = IssueLinkMinWidth)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick),
    )
}

@Preview(showBackground = true)
@PreviewWrapper(wrapper = ThemeWrapper::class)
@Composable
private fun ChangelogListPreview() {
    ChangelogList(
        versions = previewChangelogVersions,
        onIssueClick = {},
        modifier = Modifier
            .padding(16.dp)
            .heightIn(max = 400.dp),
    )
}

/** Two releases covering all three groups, a feature with a title, and an unparseable version. */
internal val previewChangelogVersions = listOf(
    Version(
        name = "20260731_1812",
        features = mapOf(
            17 to Feature(
                title = "Changes in the update prompt",
                description = "The update prompt now shows what changed since your version.",
            ),
            15 to Feature(
                title = "Attachments",
                description = "Attachments can be previewed and downloaded.",
            ),
        ),
        bugfixes = mapOf(
            16 to "The importer recovers from stuck imports.",
        ),
        tasks = mapOf(
            14 to "Updated dependencies.",
        ),
    ),
    Version(
        name = "20260714_0930",
        features = mapOf(
            13 to Feature(
                title = "Automatic updates",
                description = "The app checks for a newer version on startup.",
            ),
        ),
        bugfixes = mapOf(
            11 to "The email no longer flickers when it is opened.",
            9 to "Mailboxes are listed in the right order.",
        ),
        tasks = mapOf(
            8 to "Internal database cleanup.",
        ),
    ),
)
