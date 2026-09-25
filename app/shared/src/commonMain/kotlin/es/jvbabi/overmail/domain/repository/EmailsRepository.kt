package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.ViewState
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

interface EmailsRepository {
    fun getView(
        viewSettingsState: ViewState,
        instantLocalEmission: Boolean = false,
        user: OvermailAccount
    ): Flow<List<ViewResult>>
}

sealed class ViewResult {
    data class Item(val email: Email): ViewResult()

    /**
     * One group of a grouped listing, one subclass per `ViewGroupingKind`. What sets a group apart
     * from its siblings is its key -- the same one the server groups by, see `listGrouping.kt`
     * there. What a group is called and where it goes is the client's.
     */
    sealed class Group: ViewResult() {
        abstract val items: List<ViewResult>

        data class DateSmart(val stretch: Stretch, override val items: List<ViewResult>): Group() {
            /**
             * The server's `SmartDateBucket`. A near stretch only exists while it is not empty by
             * definition: on a Monday there is no rest of the week, see `smartDateBoundaries` there.
             */
            sealed interface Stretch {
                data object Today : Stretch
                data object Yesterday : Stretch
                /** This week, before yesterday. */
                data object Week : Stretch
                /** This month, before this week. */
                data object Month : Stretch
                /** A calendar month, older than every near stretch. */
                data class CalendarMonth(val year: Int, val month: kotlinx.datetime.Month) : Stretch
            }
        }

        data class Year(val year: Int, override val items: List<ViewResult>): Group()

        data class Month(val year: Int, val month: kotlinx.datetime.Month, override val items: List<ViewResult>): Group()

        data class Day(val date: LocalDate, override val items: List<ViewResult>): Group()

        /** The mails of one sender, a participant id. */
        data class Sender(val participantId: Uuid, override val items: List<ViewResult>): Group()

        data class ImapAccount(val imapAccount: es.jvbabi.overmail.domain.model.ImapAccount, override val items: List<ViewResult>): Group()

        data class Read(val isRead: Boolean, override val items: List<ViewResult>): Group()

        data class Archived(val state: ArchivedState, override val items: List<ViewResult>): Group()
    }
}
