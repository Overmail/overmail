package es.jvbabi.overmail.server.http.users.me.views.item

import es.jvbabi.overmail.server.http.api.notFound
import kotlin.uuid.Uuid

/**
 * The view a `{viewId}` path parameter points at, or 404.
 *
 * An id that is not an id is a miss like an unknown one: neither says anything about whether
 * somebody has a view under it.
 */
internal fun viewIdFromPath(raw: String?): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: notFound("view", raw)
