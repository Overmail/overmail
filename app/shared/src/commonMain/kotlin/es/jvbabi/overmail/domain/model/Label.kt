package es.jvbabi.overmail.domain.model

import androidx.compose.ui.graphics.Color
import kotlin.uuid.Uuid

data class Label(
    val id: Uuid,
    val name: String,
    val color: Color,
    val emailCount: Long,
    val overmailAccount: OvermailAccount,
)

/** A label as a mention of it shows it: what it is called and its color, nothing it holds. */
data class LabelReference(
    val id: Uuid,
    val name: String,
    val color: Color,
)

fun Label.toReference() = LabelReference(id = id, name = name, color = color)
