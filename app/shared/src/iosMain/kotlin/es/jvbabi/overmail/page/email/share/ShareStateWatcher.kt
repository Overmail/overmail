package es.jvbabi.overmail.page.email.share

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * Follows [ShareViewModel.state] for Swift, which cannot collect a flow itself: [onChange] is
 * called on the main thread with the state as it is now and with every change after, until
 * [cancel]. `ShareSheetModel.swift` turns it into an `ObservableObject`.
 */
class ShareStateWatcher(viewModel: ShareViewModel, onChange: (ShareState) -> Unit) {
    private val scope = MainScope()

    init {
        scope.launch { viewModel.state.collect { onChange(it) } }
    }

    fun cancel() = scope.cancel()
}

/** The month of [date], counted from 1 -- `Month.number` is an extension Swift does not see. */
fun shareMonthOf(date: LocalDate): Int = date.month.number
