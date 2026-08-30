package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class Label(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Label>(Labels) {
        /**
         * Derives a stable default color from the label name, so labels created without an
         * explicit color pick (e.g. by the classification agent) are visually distinguishable
         * and the same name always yields the same color. Saturation and brightness are fixed
         * to keep the colors readable; only the hue varies.
         */
        fun defaultColorFor(name: String): String {
            val hue = (name.trim().lowercase().hashCode().toUInt() % 360u).toInt() / 360f
            val rgb = java.awt.Color.HSBtoRGB(hue, 0.55f, 0.80f)
            return "#%06X".format(rgb and 0xFFFFFF)
        }
    }

    var name by Labels.name
    var color by Labels.color
    var owner by User referencedOn Labels.owner
    var createdAt by Labels.createdAt
    var createdByAgent by Labels.createdByAgent
    var description by Labels.description

    val emails by EmailLabel referrersOn EmailLabels.label
}

object Labels : UuidTable("labels") {
    val name = varchar("name", 255)
    val color = varchar("color", 7)
    val owner = reference("owner_id", Users, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val createdByAgent = bool("created_by_agent")
    val description = varchar("description", 512).nullable()
}