package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

/**
 * How a listing looks at the user's mail. Only the filter so far; groupings and sorting join it
 * once the app lists anything.
 */
data class ViewState(
    val filter: ViewFilter = ViewFilter(),
)

/**
 * What a view leaves out, before anything is grouped or sorted.
 *
 * Null is no restriction on that attribute, which is not the same as an empty list: a list says
 * which values pass, so an empty one lets nothing through. What is set is read together with the
 * rest, not as alternatives. The ids are the server's -- inboxes, mail addresses, labels.
 *
 * The default is the filter nobody set, which lets every mail through.
 */
data class ViewFilter(
    /** True only read, false only unread, null both. */
    val readState: Boolean? = null,
    val archivedState: List<ArchivedState>? = null,
    val imapAccountIds: List<Uuid>? = null,
    val sentBy: List<Correspondent>? = null,
    val sentTo: List<Correspondent>? = null,
    val hasLabels: List<Uuid>? = null,
)

/** What an archived state can be, the server's `EmailArchiveAction` names. */
enum class ArchivedState {
    Archive,
    Unarchive,
    Spam,
}

/** One value in a correspondent filter. */
sealed interface Correspondent {

    /** An address book entry. */
    data class Contact(val id: Uuid) : Correspondent

    /**
     * The addresses this account sends from, which is what makes a "sent" listing a filter rather
     * than a folder. Resolved by the server against the logins of the mail accounts -- of all of
     * them, or of the ones [ViewFilter.imapAccountIds] names. `self` on the wire.
     */
    data object Self : Correspondent
}
