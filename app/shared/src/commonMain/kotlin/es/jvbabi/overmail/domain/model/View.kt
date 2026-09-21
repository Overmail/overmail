package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

/**
 * How a listing looks at the user's mail: what it leaves out, how it is cut up and what orders
 * the mails inside the deepest group.
 */
data class ViewState(
    val filter: ViewFilter = ViewFilter(),
    /** The categories the listing groups by, outermost first. Empty is an ungrouped listing. */
    val groupings: List<ViewGrouping> = emptyList(),
    val sorting: ViewSorting = ViewSorting(),
) {
    companion object {
        /**
         * The listing the mailbox itself is, the web app's `mailboxView()`: what has not been
         * archived, by date, newest first. A mailbox is what is left to do, so its archive filter
         * starts at the inbox rather than at nothing.
         */
        val Mailbox = ViewState(
            filter = ViewFilter(archivedState = listOf(ArchivedState.Unarchive)),
            groupings = listOf(ViewGrouping(ViewGroupingKind.DateSmart)),
            sorting = ViewSorting(ViewSortingKind.Date),
        )
    }
}

/** One category a view groups by. [reversed] turns that category's order around. */
data class ViewGrouping(
    val kind: ViewGroupingKind,
    val reversed: Boolean = false,
)

/** What a listing can be cut by. The server's `ViewSettings.Grouping` names are these in snake_case. */
enum class ViewGroupingKind {
    /** Today, yesterday, this week, this month, then by month. */
    DateSmart,
    Year,
    Month,
    Day,
    Sender,
    /** The mail account a mail was imported through. */
    ImapAccount,
    Read,
    Archived,
}

/** What the mails inside the deepest group are ordered by. [reversed] turns it around. */
data class ViewSorting(
    val kind: ViewSortingKind = ViewSortingKind.Date,
    val reversed: Boolean = false,
)

/** The server's `EmailSorting` names, in snake_case. */
enum class ViewSortingKind {
    Date,
    Sender,
    Subject,
}

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
