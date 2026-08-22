package es.jvbabi.overmail.server.domain.repository.icon

/** Where a picture for a mail address is looked for, out on the network. */
interface EmailIconRepository {

    /** @return the first picture any resolver had for [address], or null when none had one. */
    suspend fun findIconOnline(address: String, name: String? = null): IconResult?
}

/** A picture as a resolver handed it over, together with which resolver that was. */
class IconResult(
    val source: String,
    val data: ByteArray,
)
