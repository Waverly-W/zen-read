package com.zenread.feature.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.core.model.PageTurnStyle
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import com.zenread.feature.fonts.FontSettingRow
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun TypographySheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(R.string.reader_theme) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReadingTheme.entries.forEach { theme ->
                        ThemeSwatch(theme, selected = settings.theme == theme) {
                            onChange { it.copy(theme = theme) }
                        }
                    }
                }
            }

            // Opens the font picker sheet (feature:fonts).
            FontSettingRow()

            Section(R.string.reader_font_size) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { onChange { it.copy(fontScale = (it.fontScale - 0.1f).coerceAtLeast(ReaderSettings.FONT_SCALE_MIN)) } },
                        enabled = settings.fontScale > ReaderSettings.FONT_SCALE_MIN + 0.01f,
                    ) { Icon(Icons.Rounded.Remove, stringResource(R.string.reader_smaller)) }
                    Text(
                        text = "${(settings.fontScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FilledTonalIconButton(
                        onClick = { onChange { it.copy(fontScale = (it.fontScale + 0.1f).coerceAtMost(ReaderSettings.FONT_SCALE_MAX)) } },
                        enabled = settings.fontScale < ReaderSettings.FONT_SCALE_MAX - 0.01f,
                    ) { Icon(Icons.Rounded.Add, stringResource(R.string.reader_larger)) }
                }
            }

            Section(R.string.reader_line_height, value = String.format(Locale.ROOT, "%.1f", settings.lineHeight)) {
                ZenSlider(
                    value = settings.lineHeight,
                    onValueChange = { v -> onChange { it.copy(lineHeight = (v * 10).roundToInt() / 10f) } },
                    valueRange = ReaderSettings.LINE_HEIGHT_MIN..ReaderSettings.LINE_HEIGHT_MAX,
                    steps = 13,
                )
            }

            Section(R.string.reader_margins, value = "${settings.sideMargin.roundToInt()}%") {
                ZenSlider(
                    value = settings.sideMargin,
                    onValueChange = { v -> onChange { it.copy(sideMargin = v.roundToInt().toFloat()) } },
                    valueRange = ReaderSettings.SIDE_MARGIN_MIN..ReaderSettings.SIDE_MARGIN_MAX,
                    steps = 13,
                )
            }

            Section(R.string.reader_page_turn) {
                val options = listOf(
                    PageTurnStyle.SLIDE to R.string.reader_turn_slide,
                    PageTurnStyle.SCROLL to R.string.reader_turn_scroll,
                    PageTurnStyle.NONE to R.string.reader_turn_none,
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
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, value: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun ThemeSwatch(theme: ReadingTheme, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .padding(4.dp)
            .background(
                if (theme == ReadingTheme.AUTO) MaterialTheme.colorScheme.surfaceContainerHighest else theme.background.toColor(),
                CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (theme == ReadingTheme.AUTO) {
            Icon(Icons.Rounded.BrightnessAuto, stringResource(R.string.reader_theme_auto))
        } else {
            Text("A", color = theme.text.toColor(), fontWeight = FontWeight.Medium)
        }
    }
}

private fun String.toColor() = Color(toColorInt())
