package io.github.davidvornholt.glimpse.shared.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.davidvornholt.glimpse.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** The clock time of [instant], in the phone's 12 or 24 hour format. */
fun formatTime(
    context: Context,
    instant: Instant,
): String = DateFormat.getTimeFormat(context).format(Date.from(instant))

@Composable
@ReadOnlyComposable
fun formatTime(instant: Instant): String {
    // Read so the text follows a change of locale or clock format.
    LocalConfiguration.current
    return formatTime(LocalContext.current, instant)
}

/** A short date such as "18 Dec", for when a photo goes. */
@Composable
@ReadOnlyComposable
fun formatShortDate(instant: Instant): String = formatDate(instant, "dMMM")

@Composable
@ReadOnlyComposable
private fun formatDate(
    instant: Instant,
    skeleton: String,
): String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneId.systemDefault()))
}

/** "Today", "Yesterday", or the weekday and date. */
@Composable
@ReadOnlyComposable
fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> {
            stringResource(R.string.day_today)
        }

        today.minusDays(1) -> {
            stringResource(R.string.day_yesterday)
        }

        else -> {
            val locale: Locale = LocalConfiguration.current.locales[0]
            val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
            DateTimeFormatter.ofPattern(pattern, locale).format(day)
        }
    }
}

fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()
