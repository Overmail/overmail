package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.domain.model.Labels

data class EmbeddedLabel(
    @Embedded val label: DbLabels,
    @Relation(
        entity = DbOvermailAccount::class,
        parentColumn = "overmail_account_id",
        entityColumn = "id",
    ) val account: DbOvermailAccount
) {
    fun toModel(): Labels = Labels(
        id = label.id,
        name = label.name,
        color = label.color,
        emailCount = label.emailCount,
        overmailAccount = account.toModel()
    )
}