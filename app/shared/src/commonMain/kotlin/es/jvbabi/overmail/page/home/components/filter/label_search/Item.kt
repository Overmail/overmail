package es.jvbabi.overmail.page.home.components.filter.label_search

/** One row of the list: menu-sized rather than a full list item, so a screen holds many. */
@Composable
private fun LabelRow(
    label: Label,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick takes the tag's place rather than a column of its own, flipping over to it and
        // back. Past the halfway point the other face shows, turned round so it does not read
        // mirrored.
        val rotation by animateFloatAsState(targetValue = if (picked) 180f else 0f)
        Icon(
            imageVector = if (rotation > 90f) PhIcons.Regular.Check else PhIcons.Regular.Tag,
            contentDescription = null,
            tint = label.color.labelContentColor(),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    rotationY = if (rotation > 90f) rotation - 180f else rotation
                    cameraDistance = 12f * density
                },
        )
        Text(
            text = label.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(
                Res.plurals.home_labels_email_count,
                label.emailCount.toInt(),
                label.emailCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}