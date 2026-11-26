package com.zenread.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderErrorState(
    error: ReaderError,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (error) {
        ReaderError.BOOK_MISSING -> R.string.reader_error_missing
        ReaderError.WEBVIEW_UNSUPPORTED -> R.string.reader_error_webview
        ReaderError.LOAD_FAILED -> R.string.reader_error_load
        ReaderError.RENDERER_GONE -> R.string.reader_error_crash
        ReaderError.DOWNLOAD_FAILED -> R.string.reader_error_download
    }
    Surface(
        modifier = modifier.padding(32.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(message), textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.reader_back)) }
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.reader_retry)) }
                }
            }
        }
    }
}
