package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

/**
 * A picture we found for a correspondent, as bytes. Rows only ever exist for pictures that were
 * actually found -- an address nothing could be resolved for is simply an [EmailUsers] row whose
 * `avatar_id` stays null, so retrying it costs no bookkeeping.
 *
 * A row is immutable once written: a refresh does not update the bytes, it writes a new row and
 * points the address book at it. That is what lets the id be the cache key of the url the browser
 * loads -- a replaced picture is a different url, so no stale one can survive in a cache.
 *
 * Loading one through this entity reads [EmailAvatars.data] with it, which is the whole picture.
 * Everything but `GET /api/avatars/{avatarId}` only ever wants the id.
 */
class EmailAvatar(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailAvatar>(EmailAvatars)
    typealias Id = Uuid

    var data by EmailAvatars.data
    var avatarSource by EmailAvatars.avatarSource
    var createdAt by EmailAvatars.createdAt
}

object EmailAvatars : UuidTable("email_avatars") {
    val data = binary("data")

    /**
     * Which resolver produced it, e.g. `bimi`. Not null: no resolver, no row.
     *
     * Named `avatarSource` because a plain `source` would hide `ColumnSet.source`; the column
     * itself is `source`.
     */
    val avatarSource = varchar("source", 64)

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
