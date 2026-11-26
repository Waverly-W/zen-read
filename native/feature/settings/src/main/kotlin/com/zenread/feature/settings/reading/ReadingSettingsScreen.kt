package com.zenread.feature.settings.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zenread.core.datastore.ReaderSettingsRepository
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.core.model.PageTurnStyle
import com.zenread.core.model.ReaderSettings
import com.zenread.feature.fonts.FontPicker
import com.zenread.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReadingSettingsViewModel @Inject constructor(
    private val repository: ReaderSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<ReaderSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}

/** Settings → Reading: layout defaults and the font picker (shared with the reader's sheet). */
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReadingSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_reading)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        FontPicker(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            header = {
                val current = settings ?: return@FontPicker
                item(key = "layout") {
                    LayoutSection(current, viewModel::update)
                }
            },
        )
    }
}

@Composable
private fun LayoutSection(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_reading_layout),
            style = MaterialTheme.typography.titleLarge,
        )
        Labeled(R.string.settings_reading_font_size, "${(settings.fontScale * 100).roundToInt()}%") {
            ZenSlider(
                value = settings.fontScale,
                onValueChange = { v -> onChange { it.copy(fontScale = (v * 10).roundToInt() / 10f) } },
                valueRange = ReaderSettings.FONT_SCALE_MIN..ReaderSettings.FONT_SCALE_MAX,
                steps = 15,
            )
        }
        Labeled(R.string.settings_reading_line_height, String.format(Locale.ROOT, "%.1f", settings.lineHeight)) {
            ZenSlider(
                value = settings.lineHeight,
                onValueChange = { v -> onChange { it.copy(lineHeight = (v * 10).roundToInt() / 10f) } },
                valueRange = ReaderSettings.LINE_HEIGHT_MIN..ReaderSettings.LINE_HEIGHT_MAX,
                steps = 13,
            )
        }
        Labeled(R.string.settings_reading_margins, "${settings.sideMargin.roundToInt()}%") {
            ZenSlider(
                value = settings.sideMargin,
                onValueChange = { v -> onChange { it.copy(sideMargin = v.roundToInt().toFloat()) } },
                valueRange = ReaderSettings.SIDE_MARGIN_MIN..ReaderSettings.SIDE_MARGIN_MAX,
                steps = 13,
            )
        }
        Labeled(R.string.settings_reading_page_turn, null) {
            val options = listOf(
                PageTurnStyle.SLIDE to R.string.settings_reading_turn_slide,
                PageTurnStyle.SCROLL to R.string.settings_reading_turn_scroll,
                PageTurnStyle.NONE to R.string.settings_reading_turn_none,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (style, label) ->
                    SegmentedButton(
                        selected = settings.pageTurn == style,
                        onClick = { onChange { it.copy(pageTurn = style) } },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Labeled(@StringRes title: Int, value: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        content()
    }
}
