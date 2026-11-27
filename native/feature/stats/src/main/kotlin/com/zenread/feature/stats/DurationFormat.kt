package com.zenread.feature.stats

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A duration rounded down to whole minutes, for display. */
internal data class DurationParts(val hours: Long, val minutes: Long) {
    companion object {
        fun of(ms: Long): DurationParts {
            val totalMinutes = (ms.coerceAtLeast(0) / 60_000)
            return DurationParts(totalMinutes / 60, totalMinutes % 60)
        }
    }
}

/** "1h 23m" / "1 小时 23 分"; under a minute but not zero is "<1m" / "不到 1 分钟". */
internal fun Resources.formatDuration(ms: Long): String {
    val parts = DurationParts.of(ms)
    return when {
        parts.hours > 0 && parts.minutes > 0 -> getString(R.string.stats_duration_hm, parts.hours, parts.minutes)
        parts.hours > 0 -> getString(R.string.stats_duration_h, parts.hours)
        parts.minutes == 0L && ms > 0 -> getString(R.string.stats_duration_under_minute)
        else -> getString(R.string.stats_duration_m, parts.minutes)
    }
}

@Composable
internal fun formatDuration(ms: Long): String = LocalResources.current.formatDuration(ms)

/** Localized pattern for a date skeleton such as "MMMdEEE" ("Fri, Nov 27" / "11月27日周五"). */
internal fun datePattern(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

internal fun LocalDate.format(locale: Locale, skeleton: String): String = format(datePattern(locale, skeleton))
