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
         * and the same name always yields the same color. Only the hue varies.
         *
         * Pale on purpose: a label is a chip behind a subject line, and a screen full of them is
         * a screen full of colour. What has to be told apart is the hue, and it takes far less
         * saturation than it looks like to do that -- so this is a tint, not a paint.
         */
        fun defaultColorFor(name: String): String {
            val hue = (name.trim().lowercase().hashCode().toUInt() % 360u).toInt() / 360f
            val rgb = java.awt.Color.HSBtoRGB(hue, SATURATION, BRIGHTNESS)
            return "#%06X".format(rgb and 0xFFFFFF)
        }

        /**
         * A label name as it is stored and looked up: trimmed, and runs of whitespace as one
         * space. Everything that writes a label goes through this -- the classification agent
         * and a reader typing a name -- so "  Uni   Kram" and "Uni Kram" are one label.
         */
        fun normalizeName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

        private const val SATURATION = 0.25f
        private const val BRIGHTNESS = 0.92f
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