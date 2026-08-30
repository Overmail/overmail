package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class EmailAiClassificationEvent(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailAiClassificationEvent>(EmailAiClassificationEvents)

    var email by Email referencedOn EmailAiClassificationEvents.email
    var provider by EmailAiClassificationEvents.provider
    var model by EmailAiClassificationEvents.model
    var startedAt by EmailAiClassificationEvents.startedAt
    var finishedAt by EmailAiClassificationEvents.finishedAt
    var tokensIn by EmailAiClassificationEvents.tokensIn
    var tokensOut by EmailAiClassificationEvents.tokensOut
    var log by EmailAiClassificationEvents.log
}

object EmailAiClassificationEvents : UuidTable("email_ai_classification_events") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 100)
    val model = varchar("model", 100)
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    val tokensIn = integer("tokens_in").nullable()
    val tokensOut = integer("tokens_out").nullable()

    /** Complete log of the classification run: prompts, raw model responses, decisions, errors. */
    val log = text("log").nullable()
}