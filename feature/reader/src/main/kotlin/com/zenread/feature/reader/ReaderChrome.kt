package com.zenread.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.core.designsystem.motion.ZenMotion
import kotlin.math.roundToInt

@Composable
internal fun ReaderChrome(
    visible: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    title: String,
    chapter: String?,
    fraction: Float,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onTypography: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    translating: Boolean = false,
    onToggleTranslation: (() -> Unit)? = null,
    /** Read aloud (phase 3); hidden when null. */
    onListen: (() -> Unit)? = null,
    listening: Boolean = false,
    onAskAi: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = ZenMotion.enterFromTop,
            exit = ZenMotion.exitToTop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!chapter.isNullOrBlank()) {
                            Text(
                                chapter,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_back))
                    }
                },
                actions = {
                    if (onToggleTranslation != null) {
                        IconToggleButton(checked = translating, onCheckedChange = { onToggleTranslation() }) {
                            Icon(
                                Icons.Rounded.Translate,
                                stringResource(if (translating) R.string.reader_translate_page_off else R.string.reader_translate_page_on),
                            )
                        }
                    }
                    IconToggleButton(checked = bookmarked, onCheckedChange = { onToggleBookmark() }) {
                        Icon(
                            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            stringResource(if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = ZenMotion.enterFromBottom,
            exit = ZenMotion.exitToBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProgressCard(fraction = fraction, onSeek = onSeek)
                HorizontalFloatingToolbar(expanded = true) {
                    IconButton(onClick = onPrevChapter) {
                        Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.reader_prev_chapter))
                    }
                    IconButton(onClick = onToc) {
                        Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.reader_toc))
                    }
                    IconButton(onClick = onTypography) {
                        Icon(Icons.Rounded.FormatSize, stringResource(R.string.reader_typography))
                    }
                    if (onListen != null) {
                        IconToggleButton(checked = listening, onCheckedChange = { onListen() }) {
                            Icon(Icons.Rounded.Headphones, stringResource(R.string.reader_tts_listen))
                        }
                    }
                    if (onAskAi != null) {
                        IconButton(onClick = onAskAi) {
                            Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.ai_ask))
                        }
                    }
                    IconButton(onClick = onNextChapter) {
                        Icon(Icons.Rounded.SkipNext, stringResource(R.string.reader_next_chapter))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(fraction: Float, onSeek: (Float) -> Unit) {
    // Follow the book while idle; keep the thumb under the finger while dragging.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: fraction
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZenSlider(
                value = value,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let(onSeek)
                    dragging = null
                },
            )
        }
    }
}
