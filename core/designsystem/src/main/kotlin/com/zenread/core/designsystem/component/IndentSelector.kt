package com.zenread.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.zenread.core.designsystem.R
import kotlin.math.roundToInt

/**
 * First-line indent picker. [options] are em values (one em ≈ one CJK character); a
 * negative value means "keep the book's own indent".
 */
@Composable
fun IndentSelector(
    value: Float,
    options: List<Float>,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = value == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(
                    text = when {
                        option < 0f -> stringResource(R.string.indent_book)
                        option == 0f -> stringResource(R.string.indent_none)
                        else -> stringResource(R.string.indent_chars, option.roundToInt())
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
