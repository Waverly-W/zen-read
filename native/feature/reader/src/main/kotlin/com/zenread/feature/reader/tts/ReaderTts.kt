package com.zenread.feature.reader.tts

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.model.Book
import com.zenread.reader.engine.ReaderController
import kotlinx.coroutines.flow.Flow

/** What the reader screen needs from read-aloud. */
@Stable
internal class ReaderTtsHandle(
    /** Read-aloud is playing or paused on this book. */
    val active: Boolean,
    /** Starts reading from the visible page (asks for the notification permission first). */
    val start: () -> Unit,
)

/**
 * Connects the reader's page to the read-aloud session: builds the WebView text source,
 * re-binds it after a configuration change, tells the page when the app goes to the
 * background, and asks for the notification permission before the first start.
 */
@Composable
internal fun rememberReaderTts(
    book: Book?,
    page: ReaderController?,
    pageReady: Boolean,
    chapters: Flow<String>,
    viewModel: ReaderTtsViewModel,
): ReaderTtsHandle {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bookId = book?.id
    val active = state.active && bookId != null && state.book?.bookId == bookId

    val source = remember(page, bookId) {
        if (page != null && bookId != null) WebViewTtsSource(bookId, page) else null
    }

    // A rebuilt page (rotation, theme change) picks up a session left by the previous one.
    LaunchedEffect(source, pageReady) {
        if (source != null && pageReady) viewModel.attach(source)
    }
    DisposableEffect(source) {
        onDispose {
            if (source != null) {
                source.dispose()
                val changingConfig = (context as? Activity)?.isChangingConfigurations == true
                viewModel.detach(source, resumable = changingConfig)
            }
        }
    }

    // In the background the page cannot lay out: stop drawing the highlight, keep the cursor.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, source) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> source?.setBackground(false)
                Lifecycle.Event.ON_STOP -> source?.setBackground(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(page, active) { page?.keepRendererAlive(active) }

    LaunchedEffect(chapters, bookId) {
        if (bookId != null) chapters.collect { viewModel.onChapter(bookId, it) }
    }

    val currentBook by rememberUpdatedState(book)
    val currentSource by rememberUpdatedState(source)
    val startNow = {
        val b = currentBook
        val s = currentSource
        if (b != null && s != null) viewModel.start(s, b)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Media notifications are exempt on most devices; start either way.
        startNow()
    }
    return ReaderTtsHandle(
        active = active,
        start = {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else startNow()
        },
    )
}
