package es.jvbabi.overmail.server.http.users.me.knowledge.item

import es.jvbabi.overmail.server.http.api.notFound
import kotlin.uuid.Uuid

/**
 * The entry a `{knowledgeId}` path parameter points at, or 404.
 *
 * An id that is not an id is a miss like an unknown one: neither says anything about whether
 * somebody has an entry under it.
 */
internal fun knowledgeIdFromPath(raw: String?): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: notFound("knowledge", raw)
