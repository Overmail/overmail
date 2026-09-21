package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.domain.model.Participant

data class EmbeddedParticipant(
    @Embedded val participant: DbParticipant,
    @Relation(
        entity = DbOvermailAccount::class,
        parentColumn = "overmail_account_id",
        entityColumn = "id",
    ) val account: DbOvermailAccount
) {
    fun toModel(): Participant = Participant(
        id = participant.id,
        name = participant.name,
        email = participant.email,
        avatarUrl = participant.avatarUrl,
        avatarPadding = participant.avatarPadding,
        emailCount = participant.emailCount,
        overmailAccount = account.toModel(),
    )
}
