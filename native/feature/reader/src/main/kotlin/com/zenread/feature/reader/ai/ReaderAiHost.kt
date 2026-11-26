package com.zenread.feature.reader.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zenread.reader.engine.ReaderController

/** Why the AI panel is open: from the toolbar ([quote] null) or "Ask AI" on a selection. */
data class AiPanelRequest(val quote: String? = null)

/**
 * Glue between the reader screen and [AiPanelSheet]: reads the on-screen chapter through
 * the live [controller] only when a request needs it.
 */
@Composable
internal fun ReaderAiHost(
    bookId: Long,
    request: AiPanelRequest,
    controller: ReaderController?,
    chapterTitle: String?,
    onJump: (cfi: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentController by rememberUpdatedState(controller)
    val currentTitle by rememberUpdatedState(chapterTitle)
    val source = remember {
        ChapterSource { ChapterSnapshot(currentTitle, currentController?.chapterText()) }
    }
    AiPanelSheet(
        bookId = bookId,
        quote = request.quote,
        chapterSource = source,
        onJump = onJump,
        onDismiss = onDismiss,
    )
}
