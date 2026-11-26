package com.zenread.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.TocItem
import com.zenread.core.model.flattenToc
import kotlin.math.roundToInt

/** Contents, notes and bookmarks of the open book. */
@Composable
internal fun TocSheet(
    items: List<TocItem>,
    currentHref: String?,
    annotations: List<Annotation>,
    onSelect: (TocItem) -> Unit,
    onOpenAnnotation: (Annotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val marks = remember(annotations) { annotations.filter { it.type != AnnotationType.BOOKMARK } }
    val bookmarks = remember(annotations) { annotations.filter { it.type == AnnotationType.BOOKMARK } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(
                R.string.reader_toc,
                R.string.reader_tab_notes,
                R.string.reader_tab_bookmarks,
            ).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        Box(Modifier.heightIn(min = 240.dp)) {
            when (tab) {
                0 -> ContentsList(items, currentHref, onSelect)
                1 -> AnnotationList(marks, R.string.reader_notes_empty, onOpenAnnotation)
                else -> AnnotationList(bookmarks, R.string.reader_bookmarks_empty, onOpenAnnotation)
            }
        }
    }
}

@Composable
private fun ContentsList(items: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit) {
    val flat = remember(items) { items.flattenToc() }
    val current = remember(flat, currentHref) {
        val path = currentHref?.substringBefore('#')
        flat.indexOfFirst { it.href == currentHref }.takeIf { it >= 0 }
            ?: flat.indexOfFirst { it.href.substringBefore('#') == path }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) listState.scrollToItem(current - 2) }

    if (flat.isEmpty()) {
        EmptyText(R.string.reader_toc_empty)
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(flat) { index, item ->
            val selected = index == current
            ListItem(
                modifier = Modifier.clickable { onSelect(item) },
                trailingContent = {
                    Text("${(item.startPercentage * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun AnnotationList(list: List<Annotation>, empty: Int, onOpen: (Annotation) -> Unit) {
    if (list.isEmpty()) {
        EmptyText(empty)
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(list, key = { it.id }) { annotation ->
            ListItem(
                modifier = Modifier.clickable { onOpen(annotation) },
                overlineContent = {
                    Text(
                        listOf(annotation.chapter, "${(annotation.position * 100).roundToInt()}%")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = annotation.note?.takeIf { it.isNotBlank() }?.let { note ->
                    { Text(note, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                },
                leadingContent = if (annotation.type == AnnotationType.BOOKMARK) {
                    null
                } else {
                    {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(hexColor(annotation.color)),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Text(annotation.text.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyText(text: Int) {
    Column(Modifier.fillMaxWidth().padding(32.dp)) {
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
