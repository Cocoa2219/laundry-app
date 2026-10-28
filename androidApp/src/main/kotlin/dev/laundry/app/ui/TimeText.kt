package dev.laundry.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.laundry.app.R
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter

/** Clock time in the device's 12/24-hour style; prefixed with "tomorrow" when after [today]. */
@Composable
internal fun timeText(time: LocalDateTime, today: LocalDate): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    val clock = DateTimeFormatter.ofPattern(pattern).format(time.toJavaLocalDateTime())
    return if (time.date > today) stringResource(R.string.result_tomorrow, clock) else clock
}
