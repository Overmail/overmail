package es.jvbabi.overmail.page.home.components.filter.label_search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import es.jvbabi.overmail.page.home.components.filter.LabelPickerContent

/** Zero width, so the field looks empty with it; see where [LabelPickerContent] uses it. */
const val BACKSPACE_SENTINEL = "\u200B"

/** [query] behind the sentinel, with [selection] counted in the query. */
fun sentinelValue(query: String, selection: TextRange) = TextFieldValue(
    text = BACKSPACE_SENTINEL + query,
    selection = TextRange(selection.start + 1, selection.end + 1),
)