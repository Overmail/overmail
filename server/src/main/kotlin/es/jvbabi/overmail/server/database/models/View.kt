package es.jvbabi.overmail.server.database.models

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

class View(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<View>(Views)

    val user by User referencedOn Views.user
    var name by Views.name
    var view by Views.view
}

object Views : UuidTable("views") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val view = jsonb<ViewSettings>("view", OvermailDatabase.json)
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