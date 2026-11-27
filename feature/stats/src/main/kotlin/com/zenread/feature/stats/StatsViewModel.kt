package com.zenread.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.stats.BookReading
import com.zenread.data.stats.DayTotal
import com.zenread.data.stats.ReadingOverview
import com.zenread.data.stats.ReadingStats
import com.zenread.data.stats.ReadingStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    /** Today as an epoch day in the device's zone. */
    val today: Long,
    val firstDayOfWeek: DayOfWeek,
    val days: Map<Long, DayTotal>,
    val overview: ReadingOverview,
    val books: List<BookReading>,
    val finishedBooks: Int,
) {
    val isEmpty: Boolean get() = overview.totalMs <= 0 && finishedBooks == 0
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    repository: ReadingStatsRepository,
) : ViewModel() {

    /** Re-checked every minute so the page rolls over at midnight while open. */
    private val today = flow {
        while (true) {
            emit(repository.today())
            delay(60_000)
        }
    }.distinctUntilChanged()

    /** Null while loading. */
    val state: StateFlow<StatsUiState?> = combine(
        repository.observeDays(),
        repository.observeBooks(),
        repository.observeFinishedCount(),
        today,
    ) { days, books, finished, today ->
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        StatsUiState(
            today = today,
            firstDayOfWeek = firstDayOfWeek,
            days = days,
            overview = ReadingStats.overview(days, today, firstDayOfWeek),
            books = books,
            finishedBooks = finished,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
