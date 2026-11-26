package com.zenread.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SettingsKey : NavKey

private data class SettingsEntry(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
)

private val entries = listOf(
    SettingsEntry(Icons.Rounded.Palette, R.string.settings_appearance, R.string.settings_appearance_summary),
    SettingsEntry(Icons.AutoMirrored.Rounded.MenuBook, R.string.settings_reading, R.string.settings_reading_summary),
    SettingsEntry(Icons.Rounded.CloudSync, R.string.settings_sync, R.string.settings_sync_summary),
    SettingsEntry(Icons.Rounded.AutoAwesome, R.string.settings_ai, R.string.settings_ai_summary),
    SettingsEntry(Icons.Rounded.Headphones, R.string.settings_tts, R.string.settings_tts_summary),
    SettingsEntry(Icons.Rounded.Translate, R.string.settings_translate, R.string.settings_translate_summary),
    SettingsEntry(Icons.Rounded.Info, R.string.settings_about, R.string.settings_about_summary),
)

@Composable
fun SettingsScreen(
    onOpenCategory: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(entries) { index, entry ->
                SegmentedRow(
                    entry = entry,
                    shape = segmentShape(index, entries.size),
                    onClick = { onOpenCategory(entry.title) },
                )
            }
        }
    }
}

/** Expressive grouped-list shape: large outer corners, small inner corners. */
private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
private fun SegmentedRow(
    entry: SettingsEntry,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ListItem(
            supportingContent = { Text(stringResource(entry.summary)) },
            leadingContent = { Icon(entry.icon, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Text(stringResource(entry.title))
        }
    }
}
