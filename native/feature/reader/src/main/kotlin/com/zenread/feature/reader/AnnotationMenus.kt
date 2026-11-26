package com.zenread.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.HighlightColor
import com.zenread.reader.engine.PageRect

/** Menu shown for a fresh text selection. */
@Composable
internal fun SelectionMenu(
    rect: PageRect,
    overlapsMarks: Boolean,
    onMark: (AnnotationType, HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    AnchoredMenu(rect) {
        MenuCard {
            ColorRow(selected = null) { onMark(AnnotationType.HIGHLIGHT, it) }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { onMark(AnnotationType.UNDERLINE, HighlightColor.DEFAULT) }) {
                    Icon(Icons.Rounded.FormatUnderlined, stringResource(R.string.reader_underline))
                }
                IconButton(onClick = onNote) { Icon(Icons.Rounded.EditNote, stringResource(R.string.reader_add_note)) }
                IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.reader_copy)) }
                if (overlapsMarks) {
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.reader_delete_mark)) }
                }
            }
        }
    }
}

/** Menu shown when an existing highlight or underline is tapped. */
@Composable
internal fun AnnotationMenu(
    rect: PageRect,
    type: AnnotationType,
    color: String,
    onRestyle: (AnnotationType, HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val current = HighlightColor.entries.firstOrNull { it.hex.equals(color, ignoreCase = true) }
    AnchoredMenu(rect) {
        MenuCard {
            ColorRow(selected = current) { onRestyle(type, it) }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val other = if (type == AnnotationType.UNDERLINE) AnnotationType.HIGHLIGHT else AnnotationType.UNDERLINE
                IconButton(onClick = { onRestyle(other, current ?: HighlightColor.DEFAULT) }) {
                    if (other == AnnotationType.UNDERLINE) {
                        Icon(Icons.Rounded.FormatUnderlined, stringResource(R.string.reader_underline))
                    } else {
                        Icon(Icons.Rounded.FormatColorFill, stringResource(R.string.reader_highlight))
                    }
                }
                IconButton(onClick = onNote) { Icon(Icons.Rounded.EditNote, stringResource(R.string.reader_add_note)) }
                IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.reader_copy)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.reader_delete_mark)) }
            }
        }
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        // Intrinsic width: the divider must match the rows instead of stretching the card.
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun ColorRow(selected: HighlightColor?, onPick: (HighlightColor) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HighlightColor.entries.forEach { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .padding(if (isSelected) 5.dp else 2.dp)
                    .background(color.toComposeColor(), CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(color) }),
                contentAlignment = Alignment.Center,
            ) {}
        }
    }
}

internal fun HighlightColor.toComposeColor() = Color(0xFF000000 or hex.toLong(16))

internal fun hexColor(hex: String) = Color(0xFF000000 or (hex.toLongOrNull(16) ?: 0xF6C945))

/**
 * Places its single child just above [rect] (normalized page coordinates), or below it
 * when there is no room above. Only the child takes touches; the page stays interactive.
 */
@Composable
private fun AnchoredMenu(rect: PageRect, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val edge = with(density) { 8.dp.roundToPx() }
    val topSafe = WindowInsets.statusBarsIgnoringVisibility.getTop(density) + edge
    Layout(content = content) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val menu = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val centerX = ((rect.left + rect.right) / 2f * width).toInt()
        val x = (centerX - menu.width / 2).coerceIn(edge, (width - menu.width - edge).coerceAtLeast(edge))
        val above = (rect.top * height).toInt() - menu.height - gap
        val below = (rect.bottom * height).toInt() + gap
        val y = if (above >= topSafe) above else below.coerceAtMost(height - menu.height - edge)
        layout(width, height) { menu.place(x, y) }
    }
}
