package com.zenread.feature.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.zenread.core.designsystem.component.NoteEditorSheet
import com.zenread.core.model.BookFormat
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import com.zenread.reader.engine.ReaderBridge
import com.zenread.reader.engine.ReaderController
import com.zenread.reader.engine.ReaderEvent
import com.zenread.reader.engine.ReaderOrigin
import com.zenread.reader.engine.ReaderStyle
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Opens [bookId]; [cfi] jumps to a location (e.g. a note) instead of the saved position. */
@Serializable
data class ReaderKey(val bookId: Long, val cfi: String? = null) : NavKey

private enum class Sheet { TOC, TYPOGRAPHY }

@Composable
fun ReaderScreen(
    bookId: Long,
    openAt: String?,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory>(
        key = "reader-$bookId-$openAt",
        creationCallback = { it.create(bookId, openAt) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val theme = (settings?.theme ?: ReadingTheme.AUTO).resolve(isSystemInDarkTheme())
    val pageColor = Color(theme.background.toColorInt())
    val textColor = Color(theme.text.toColorInt())

    ImmersiveSystemBars(showBars = state.chromeVisible, darkPage = theme == ReadingTheme.NIGHT)

    val annotations by viewModel.annotations.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<ReaderController?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val context = LocalContext.current
    val copy = { text: String ->
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(state.book?.title.orEmpty(), text))
        Unit
    }

    // Apply the view model's page commands once a page exists. The channel buffers
    // anything sent while the WebView is being (re)built.
    LaunchedEffect(controller) {
        val page = controller ?: return@LaunchedEffect
        viewModel.commands.collect { command ->
            when (command) {
                is PageCommand.ReplaceAnnotations -> page.replaceAnnotations(command.annotations)
                is PageCommand.Show -> page.showAnnotation(command.annotation)
                is PageCommand.Hide -> page.hideAnnotation(command.cfi)
                is PageCommand.GoTo -> page.goToCfi(command.cfi)
                PageCommand.ClearSelection -> page.clearSelection()
                PageCommand.BookmarkHere -> page.bookmarkCurrentPage()
            }
        }
    }

    BackHandler(enabled = state.selection != null || state.tapped != null) {
        if (state.tapped != null) viewModel.dismissTapped() else viewModel.clearSelection()
    }

    Box(Modifier.fillMaxSize().background(pageColor)) {
        val bookUrl = state.bookUrl
        val currentSettings = settings
        if (bookUrl != null && currentSettings != null) {
            ReaderWebView(
                viewModel = viewModel,
                bookUrl = bookUrl,
                settings = currentSettings,
                theme = theme,
                onController = { controller = it },
                onTap = { x ->
                    if (state.tapped != null) {
                        viewModel.dismissTapped()
                        return@ReaderWebView
                    }
                    when (tapAction(x, state.chromeVisible)) {
                        TapAction.PREV -> controller?.prevPage()
                        TapAction.NEXT -> controller?.nextPage()
                        TapAction.TOGGLE_CHROME -> viewModel.setChromeVisible(!state.chromeVisible)
                    }
                },
            )
        }

        if (state.error == null && !state.ready) {
            LoadingIndicator(Modifier.align(Alignment.Center))
        }
        state.error?.let { error ->
            ReaderErrorState(
                error = error,
                onRetry = when (error) {
                    ReaderError.RENDERER_GONE -> viewModel::onReload
                    ReaderError.DOWNLOAD_FAILED -> viewModel::retryOpen
                    else -> null
                },
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        PageFooter(
            location = state.location.takeUnless { state.chromeVisible },
            fixedLayout = state.book?.format.let { it == BookFormat.PDF || it == BookFormat.CBZ },
            color = textColor.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )

        state.selection?.let { selection ->
            SelectionMenu(
                rect = selection.rect,
                overlapsMarks = selection.annotationIds.isNotEmpty(),
                onMark = viewModel::markSelection,
                onNote = viewModel::noteOnSelection,
                onCopy = { copy(selection.text); viewModel.clearSelection() },
                onDelete = viewModel::deleteMarksInSelection,
            )
        }
        state.tapped?.let { (annotation, rect) ->
            AnnotationMenu(
                rect = rect,
                type = annotation.type,
                color = annotation.color,
                onRestyle = { type, color -> viewModel.restyle(annotation, type, color) },
                onNote = { viewModel.editNote(annotation) },
                onCopy = { copy(annotation.text); viewModel.dismissTapped() },
                onDelete = { viewModel.deleteAnnotation(annotation) },
            )
        }

        ReaderChrome(
            visible = state.chromeVisible && state.ready,
            bookmarked = state.location?.bookmarkCfi != null,
            onToggleBookmark = viewModel::toggleBookmark,
            title = state.book?.title.orEmpty(),
            chapter = state.location?.chapterTitle,
            fraction = state.location?.fraction ?: 0f,
            onBack = onBack,
            onToc = { sheet = Sheet.TOC },
            onTypography = { sheet = Sheet.TYPOGRAPHY },
            onPrevChapter = { controller?.prevSection() },
            onNextChapter = { controller?.nextSection() },
            onSeek = { controller?.goToPercent(it) },
        )
    }

    when (sheet) {
        Sheet.TOC -> TocSheet(
            items = state.toc,
            currentHref = state.location?.chapterHref,
            annotations = annotations,
            onSelect = { item ->
                sheet = null
                viewModel.setChromeVisible(false)
                controller?.goToHref(item.href)
            },
            onOpenAnnotation = { annotation ->
                sheet = null
                viewModel.goTo(annotation.cfi)
            },
            onDismiss = { sheet = null },
        )
        Sheet.TYPOGRAPHY -> settings?.let { current ->
            TypographySheet(
                settings = current,
                onChange = { transform -> viewModel.updateSettings(transform) },
                onDismiss = { sheet = null },
            )
        }
        null -> Unit
    }

    state.editingNoteId?.let { id ->
        annotations.firstOrNull { it.id == id }?.let { annotation ->
            NoteEditorSheet(
                quote = annotation.text,
                initialNote = annotation.note.orEmpty(),
                accent = hexColor(annotation.color),
                onSave = { viewModel.saveNote(id, it) },
                onDismiss = viewModel::dismissNoteEditor,
            )
        }
    }
}

@Composable
private fun ReaderWebView(
    viewModel: ReaderViewModel,
    bookUrl: String,
    settings: ReaderSettings,
    theme: ReadingTheme,
    onController: (ReaderController?) -> Unit,
    onTap: (Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val locale = LocalLocale.current.toLanguageTag()
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Space for status/navigation bars, in CSS px (= dp in WebView), constant whether or
    // not the bars are showing, so toggling the menu never re-paginates.
    val topInset = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp().value.roundToInt() }
    val bottomInset = with(density) {
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(this).toDp().value.roundToInt() + FOOTER_HEIGHT_DP
    }
    val styleFor = { s: ReaderSettings, t: ReadingTheme -> ReaderStyle.build(s, t, locale, topInset, bottomInset) }

    // Bumping this rebuilds the WebView, e.g. after the renderer process died.
    var generation by remember { mutableIntStateOf(0) }
    val webView: WebView = remember(bookUrl, generation) {
        viewModel.webViewFactory.create(context, onRendererGone = viewModel::onRendererGone).also { view ->
            ReaderBridge(scope) { name, args ->
                val event = ReaderEvent.parse(name, args)
                when (event) {
                    null -> Unit
                    is ReaderEvent.Tap -> currentOnTap(event.x)
                    else -> viewModel.onEvent(event)
                }
                null
            }.install(view)
            view.loadUrl(
                ReaderOrigin.readerUrl(
                    bookUrl = bookUrl,
                    initialCfi = viewModel.state.value.initialLocation,
                    style = styleFor(settings, theme),
                    importing = false,
                ),
            )
        }
    }
    DisposableEffect(webView) {
        val controller = ReaderController(webView)
        onController(controller)
        onDispose {
            onController(null)
            webView.destroy()
        }
    }

    // A crash shows an error with retry; retry clears the error, which rebuilds the view.
    var lastError by remember { mutableStateOf(state.error) }
    LaunchedEffect(state.error) {
        if (lastError == ReaderError.RENDERER_GONE && state.error == null) generation++
        lastError = state.error
    }

    // Live style updates after the first load.
    LaunchedEffect(webView, settings, theme, state.ready) {
        if (state.ready) ReaderController(webView).changeStyle(styleFor(settings, theme))
    }

    if (state.error != ReaderError.RENDERER_GONE) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

/** Chapter title and page counter in the bottom margin. */
@Composable
private fun PageFooter(
    location: ReaderEvent.Relocated?,
    fixedLayout: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (location == null) return
    Box(modifier.fillMaxWidth()) {
        Text(
            text = location.chapterTitle.orEmpty(),
            color = color,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.7f),
        )
        Text(
            text = when {
                // One section per page: show the page within the whole book.
                fixedLayout && location.sections > 0 -> "${location.section} / ${location.sections}"
                location.chapterPages > 0 -> "${location.chapterPage} / ${location.chapterPages}"
                else -> "${(location.fraction * 100).roundToInt()}%"
            },
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** Hides system bars while reading; shows them with the reader menu. */
@Composable
private fun ImmersiveSystemBars(showBars: Boolean, darkPage: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val systemDark = isSystemInDarkTheme()
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(showBars, darkPage) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !darkPage
        controller.isAppearanceLightNavigationBars = !darkPage
        if (showBars) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
}

private const val FOOTER_HEIGHT_DP = 20
