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
 * The bytes are immutable once written: a refresh does not update them, it writes a new row and
 * points the address book at it. That is what lets the id be the cache key of the url the browser
 * loads -- a replaced picture is a different url, so no stale one can survive in a cache.
 * [EmailAvatars.circlePadding] is the one column that is written again, because it is derived
 * from those bytes rather than part of them.
 *
 * Loading one through this entity reads [EmailAvatars.data] with it, which is the whole picture.
 * Everything but `GET /api/avatars/{avatarId}` only ever wants the id.
 */
class EmailAvatar(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailAvatar>(EmailAvatars)
    typealias Id = Uuid

    var data by EmailAvatars.data
    var avatarSource by EmailAvatars.avatarSource
    var circlePadding by EmailAvatars.circlePadding
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

    /**
     * How much of its own box the picture has to give up on every side to fit inside a circle,
     * as a fraction: `0.0` for one that can simply be clipped because its corners hold nothing but
     * filler, up to `0.146` for one whose content reaches into them. See `circlePadding`, which is
     * what works it out.
     *
     * Null means nobody looked yet, which is what rows written before this column existed are
     * until the backfill reaches them. A picture nothing can decode is stored as `0.0` rather than
     * left null, so the backfill is finite.
     *
     * Read it off the table rather than through [EmailAvatar]: that entity reads [data] with it.
     */
    val circlePadding = double("circle_padding").nullable()

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
