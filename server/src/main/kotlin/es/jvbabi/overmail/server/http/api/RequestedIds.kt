package es.jvbabi.overmail.server.http.api

import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/** One request may look up this many entities; a client that needs more asks twice. */
const val MAX_LOOKUP_IDS = 100

/**
 * The ids of an `?ids=a,b,c` lookup, without the ones that are not ids at all.
 *
 * An unparseable or unknown id is simply not in the answer: the client asks for what it has in a
 * cache and takes what comes back, and a single bad id must not cost it the rest.
 */
fun ApplicationCall.requestedIds(): List<Uuid> = parameters["ids"]
    .orEmpty()
    .split(',')
    .mapNotNull { id -> Uuid.parseOrNull(id.trim()) }
    .distinct()
    .take(MAX_LOOKUP_IDS)
