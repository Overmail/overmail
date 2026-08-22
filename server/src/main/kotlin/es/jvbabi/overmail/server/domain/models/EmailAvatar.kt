package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A cached avatar as an address book entry points at it. The bytes are deliberately not part of
 * this: a `ByteArray` compares by identity, which would break the `distinctUntilChanged` every
 * repository flow here relies on -- and a listing wants the urls, not a megabyte of pictures.
 * Load them through
 * [es.jvbabi.overmail.server.domain.repository.EmailAvatarRepository.getImage].
 *
 * One picture can be reached through several addresses, so the same [id] may appear more than
 * once in a listing, each time with the [address] that points at it.
 */
data class EmailAvatar(
    val id: Uuid,
    val address: String,
    /** Which resolver found it, e.g. `bimi`. */
    val source: String,
    val createdAt: Instant,
)
