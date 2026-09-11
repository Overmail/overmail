package es.jvbabi.overmail.server.database.models

import com.davidarvelo.fractionalindexing.FractionalIndexing
import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.Uuid

class View(id: EntityID<Id>): UuidEntity(id) {
    companion object : UuidEntityClass<View>(Views)

    typealias Id = Uuid

    val user by User referencedOn Views.user
    var name by Views.name
    var view by Views.view
    var sortKey by Views.sortKey
}

object Views : UuidTable("views") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val view = jsonb<ViewSettings>("view", OvermailDatabase.json)
    val sortKey = varchar("sort_key", 255).default(FIRST_VIEW_SORT_KEY)
}

val FIRST_VIEW_SORT_KEY: String = FractionalIndexing.generateFractionalIndexBetween(null, null)

/**
 * A key that sorts in front of everything in [keys] -- the top of the list.
 *
 * The counterpart of [viewSortKeyAfter] for a move: there is no view to sit behind up there, and
 * `after = null` already means the other end.
 */
fun viewSortKeyFirst(keys: List<String>): String =
    FractionalIndexing.generateFractionalIndexBetween(null, keys.minOrNull())

fun viewSortKeyAfter(keys: List<String>, after: String?): String {
    val sorted = keys.sorted()
    val before = after ?: sorted.lastOrNull()
    val successor = if (before == null) null else sorted.firstOrNull { key -> key > before }
    return FractionalIndexing.generateFractionalIndexBetween(before, successor)
}

@Serializable
data class ViewSettings(
    @SerialName("groupings") val groupings: List<Grouping>,
    @SerialName("email_sorting") val emailSorting: EmailSorting,
) {
    @Serializable
    sealed class Grouping {

        @SerialName("sort_reversed") abstract val reversed: Boolean

        @Serializable
        @SerialName("date_smart")
        data class DateSmartGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("year")
        data class YearGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("month")
        data class MonthGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("day")
        data class DayGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("sender")
        data class SenderGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("imap_account")
        data class ImapAccountGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("read")
        data class ReadGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()

        @Serializable
        @SerialName("archived")
        data class ArchivedGrouping(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : Grouping()
    }

    @Serializable
    sealed class EmailSorting {
        @SerialName("sort_reversed") abstract val reversed: Boolean

        @Serializable
        @SerialName("date")
        data class DateSorting(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : EmailSorting()

        @Serializable
        @SerialName("sender")
        data class SenderSorting(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : EmailSorting()

        @Serializable
        @SerialName("subject")
        data class SubjectSorting(
            @SerialName("sort_reversed") override val reversed: Boolean,
        ) : EmailSorting()
    }
}