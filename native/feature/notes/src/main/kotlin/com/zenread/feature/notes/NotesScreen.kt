package com.zenread.feature.notes

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.zenread.core.designsystem.component.EmptyState
import kotlinx.serialization.Serializable

@Serializable
data object NotesKey : NavKey

@Composable
fun NotesScreen(modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.notes_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        EmptyState(
            icon = Icons.Rounded.EditNote,
            title = stringResource(R.string.notes_empty_title),
            body = stringResource(R.string.notes_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
