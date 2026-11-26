package com.zenread.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenread.core.model.TocItem
import com.zenread.core.model.flattenToc
import kotlin.math.roundToInt

@Composable
internal fun TocSheet(
    items: List<TocItem>,
    currentHref: String?,
    onSelect: (TocItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val flat = remember(items) { items.flattenToc() }
    val current = remember(flat, currentHref) {
        val path = currentHref?.substringBefore('#')
        flat.indexOfFirst { it.href == currentHref }.takeIf { it >= 0 }
            ?: flat.indexOfFirst { it.href.substringBefore('#') == path }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) listState.scrollToItem(current - 2) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.reader_toc),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (flat.isEmpty()) {
            Text(
                text = stringResource(R.string.reader_toc_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(flat) { index, item ->
                val selected = index == current
                ListItem(
                    modifier = Modifier.clickable { onSelect(item) },
                    trailingContent = {
                        Text(
                            "${(item.startPercentage * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                ) {
                    Text(
                        text = item.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (item.level == 1) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(start = (16 * (item.level - 1)).coerceAtMost(64).dp),
                    )
                }
            }
        }
    }
}
