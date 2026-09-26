package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbEmailRecipients
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.domain.model.EmailRecipient

data class EmbeddedEmailRecipient(
    @Embedded val recipient: DbEmailRecipients,
    @Relation(
        entity = DbParticipant::class,
        parentColumn = "participant_id",
        entityColumn = "id",
    ) val participant: EmbeddedParticipant,
) {
    fun toModel(): EmailRecipient = EmailRecipient(
        participant = participant.toModel(),
        type = recipient.type,
    )
}
