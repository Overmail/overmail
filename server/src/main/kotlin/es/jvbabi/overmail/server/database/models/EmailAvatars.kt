package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * A picture we found for a correspondent, as bytes. Rows only ever exist for pictures that were
 * actually found -- an address nothing could be resolved for is simply an [EmailUsers] row whose
 * `avatar_id` stays null, so retrying it costs no bookkeeping.
 *
 * A row is immutable once written: refreshing does not update the bytes, it writes a new row and
 * points the address book at it. That is what lets the id be the cache key of the url the browser
 * loads -- a replaced picture is a different url, so no stale one can survive in a cache.
 */
object EmailAvatars : UuidTable("email_avatars") {
    val data = binary("data")

    /**
     * Which resolver produced it, e.g. `bimi`. Not null: no resolver, no row.
     *
     * Named `avatarSource` because a plain `source` would hide `ColumnSet.source`; the column
     * itself is `source`.
     */
    val avatarSource = varchar("source", 64)

    val createdAt = timestamp("created_at")
}
