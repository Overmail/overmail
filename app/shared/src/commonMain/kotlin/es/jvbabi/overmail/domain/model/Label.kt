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