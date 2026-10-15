package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

/**
 * A key a user made to call the api from a script, see `auth/ApiKeys.kt`. It signs in as its user
 * with everything a session may do.
 *
 * The key itself is never stored, only its hash: it is shown once when it is made, and a leaked
 * table hands nobody a working key.
 */
class ApiKey(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ApiKey>(ApiKeys)

    var user by User referencedOn ApiKeys.user
    var name by ApiKeys.name
    var keyHash by ApiKeys.keyHash
    var keyPrefix by ApiKeys.keyPrefix
    var createdAt by ApiKeys.createdAt
    var lastUsedAt by ApiKeys.lastUsedAt
}

object ApiKeys : UuidTable("api_keys") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /** What the user called it, so a list of keys says which script holds which. */
    val name = varchar("name", 100)

    /** SHA-256 of the key, hex. Looked up on every request a key signs in. */
    val keyHash = varchar("key_hash", 64).uniqueIndex()

    /** The start of the key, which is what lets the user match a key in a script to its row. */
    val keyPrefix = varchar("key_prefix", 16)

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** When a request last signed in with it, to the minute; null for a key never used. */
    val lastUsedAt = timestamp("last_used_at").nullable().default(null)
}
