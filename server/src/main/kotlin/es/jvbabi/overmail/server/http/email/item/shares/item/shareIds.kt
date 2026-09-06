package es.jvbabi.overmail.server.http.email.item.shares.item

import es.jvbabi.overmail.server.http.api.notFound
import kotlin.uuid.Uuid

/**
 * The share a `{shareId}` path parameter points at, or 404.
 *
 * An id that is not an id is a miss like an unknown one. Whether the share is this mail's is not
 * asked here -- the routes below scope their write to the `{emailId}` as well, so a share of
 * another mail never matches in the first place.
 */
internal fun shareIdFromPath(raw: String?): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: notFound("share", raw)
