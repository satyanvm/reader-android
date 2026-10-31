package io.github.satyanvm.reader.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.TextFlow
import io.github.satyanvm.reader.text.TextTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val ZOOM_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f)
private val TEXT_STEPS = listOf(0.7f, 0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val flow by vm.flow.collectAsStateWithLifecycle()
    val paged = flow == TextFlow.PAGED
    val snackbar = remember { SnackbarHostState() }
    val textMode = state.mode == ReadingMode.TEXT
    val colors = if (textMode) theme.colors() else TextTheme.LIGHT.colors()

    // Periodic debounced saves are the primary mechanism; these are just prompt extras.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.flush() }
    DisposableEffect(controller) { onDispose { controller.flush() } }

    NoticeEffect(state.notice, snackbar, controller)

    // The status bar stays, so the time and battery are always readable; the page sits below it.
    // A tap shows the app bar and the scrubber, like Play Books: the page shrinks (scaled, so the
    // text never reflows) and the bars fill the space it leaves above and below. The next tap
    // brings the page back to full size.
    var controlsShown by rememberSaveable { mutableStateOf(false) }
    val showControls = controlsShown || state.document == null
    ImmersiveEffect(immersive = !showControls, darkPage = colors.background.luminance() < 0.5f)
    val toggleControls = { controlsShown = !controlsShown }
    val book = remember { BookProgress() }
    val meter = remember { LoadingMeter() }
    val shrink by animateFloatAsState(
        targetValue = if (showControls && state.document != null) 1f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "shrink",
    )
    val chrome = lerp(colors.background, colors.text, 0.07f)
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = lerp(colors.background, chrome, shrink),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val density = LocalDensity.current
            val fullHeight = constraints.maxHeight.toFloat()
            val gapPx = with(density) { 12.dp.toPx() }
            // Small enough to sit between the bars, centred in the space they leave.
            val shrunk = ((fullHeight - topBarPx - bottomBarPx - gapPx * 2) / fullHeight).coerceIn(0.6f, 1f)
            val scale = 1f - (1f - shrunk) * shrink
            val corner = with(density) { 14.dp.toPx() } * shrink
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationY = (topBarPx - bottomBarPx) / 2f * shrink
                        shape = RoundedCornerShape(corner)
                        clip = shrink > 0f
                        shadowElevation = 10.dp.toPx() * shrink
                    }
                    .background(colors.background)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars)),
                contentAlignment = Alignment.Center,
            ) {
                val doc = state.document
                CompositionLocalProvider(LocalLoadingMeter provides meter) {
                    when {
                        state.phase == Phase.Loading -> BookLoading(theme.colors())
                        state.phase is Phase.Failed -> Text((state.phase as Phase.Failed).message)
                        doc == null -> Unit
                        doc is EpubBook ->
                            if (paged) EpubBookPages(doc, state, controller, colors, book, showControls, toggleControls)
                            else EpubPages(doc, state, controller, colors, toggleControls)
                        doc !is PagedDocument -> Unit
                        textMode && text != null ->
                            if (paged) PdfBookPages(text!!, doc, state, controller, colors, book, showControls, toggleControls)
                            else TextPages(text!!, doc, state, controller, colors, toggleControls)
                        else -> PdfPages(doc, state, controller, toggleControls)
                    }
                }
            }
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topBarPx = it.height },
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = chrome,
                        titleContentColor = colors.text,
                        navigationIconContentColor = colors.text,
                        actionIconContentColor = colors.text,
                    ),
                    title = { Text(state.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state.document == null) return@TopAppBar
                        if (textMode) {
                            TextSettingsMenu(
                                scale = state.textScale,
                                theme = theme,
                                color = colors.text,
                                onScale = vm::setTextScale,
                                onTheme = vm::setTheme,
                            )
                        } else {
                            TextButton(
                                onClick = { controller.setZoom(ZOOM_STEPS.lastOrNull { it < state.zoom } ?: 1f) },
                                enabled = state.zoom > 1f,
                            ) { Text("−") }
                            TextButton(
                                onClick = { controller.setZoom(ZOOM_STEPS.firstOrNull { it > state.zoom } ?: state.zoom) },
                                enabled = state.zoom < ZOOM_STEPS.last(),
                            ) { Text("+") }
                        }
                        OverflowMenu(
                            view = if (textMode) ReadingView.Text(flow) else ReadingView.Pages,
                            canShowPages = state.document is PagedDocument,
                            onView = { view ->
                                when (view) {
                                    is ReadingView.Text -> {
                                        vm.setFlow(view.flow)
                                        if (!textMode) vm.setMode(ReadingMode.TEXT)
                                    }
                                    ReadingView.Pages -> vm.setMode(ReadingMode.PAGES)
                                }
                            },
                            onRestart = controller::restartFromBeginning,
                        )
                    },
                )
            }
            state.document?.let { doc ->
                AnimatedVisibility(
                    visible = showControls,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomBarPx = it.height },
                ) {
                    // In book pages the scrubber moves through those pages; otherwise (or while a
                    // PDF's pages are still being prepared) through the PDF's pages or the EPUB's chapters.
                    val bookPages = paged && textMode && book.count > 0 && !book.partial
                    ProgressBar(
                        pageIndex = if (bookPages) book.page else state.pageIndex,
                        pageCount = if (bookPages) book.count else doc.pageCount,
                        unit = if (bookPages) "Page " else if (doc is EpubBook) "Chapter " else "",
                        color = colors.text,
                        background = chrome,
                        onSeek = if (bookPages) { page -> book.seek(page) } else controller::goToPage,
                    )
                }
            }
        }
    }
}

/**
 * Hides the navigation bar while reading (a swipe from the edge brings it back briefly) and keeps
 * the status bar's icons legible on the page colour: dark on light and sepia, light on dark.
 */
@Composable
private fun ImmersiveEffect(immersive: Boolean, darkPage: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, immersive, darkPage) {
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.isAppearanceLightStatusBars = !darkPage
        bars?.isAppearanceLightNavigationBars = !darkPage
        if (immersive) bars?.hide(WindowInsetsCompat.Type.navigationBars())
        else bars?.show(WindowInsetsCompat.Type.navigationBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.navigationBars())
            // Back to the library, which is always light.
            bars?.isAppearanceLightStatusBars = true
            bars?.isAppearanceLightNavigationBars = true
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** What the book is shown as: flowing text (a screen at a time, or one long scroll) or the PDF's own pages. */
private sealed interface ReadingView {
    data class Text(val flow: TextFlow) : ReadingView
    data object Pages : ReadingView
}

@Composable
private fun TextSettingsMenu(
    scale: Float,
    theme: TextTheme,
    color: Color,
    onScale: (Float) -> Unit,
    onTheme: (TextTheme) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Aa", color = color) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onScale(TEXT_STEPS.lastOrNull { it < scale - 0.001f } ?: scale) },
                enabled = scale > TEXT_STEPS.first(),
            ) { Text("A−") }
            Text("${(scale * 100).roundToInt()}%")
            TextButton(
                onClick = { onScale(TEXT_STEPS.firstOrNull { it > scale + 0.001f } ?: scale) },
                enabled = scale < TEXT_STEPS.last(),
            ) { Text("A+") }
        }
        HorizontalDivider()
        for (t in TextTheme.entries) {
            DropdownMenuItem(
                text = { Text(t.label) },
                leadingIcon = { if (t == theme) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { onTheme(t) },
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    view: ReadingView,
    canShowPages: Boolean,
    onView: (ReadingView) -> Unit,
    onRestart: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val views = TextFlow.entries.map { ReadingView.Text(it) } + listOfNotNull(ReadingView.Pages.takeIf { canShowPages })
        for (v in views) {
            DropdownMenuItem(
                text = { Text(v.label) },
                leadingIcon = { if (v == view) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onView(v)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Start from the beginning") },
            onClick = {
                open = false
                onRestart()
            },
        )
    }
}

private val ReadingView.label: String
    get() = when (this) {
        is ReadingView.Text -> "Flowing text: ${flow.label.replaceFirstChar { it.lowercase() }}"
        ReadingView.Pages -> "Original pages"
    }

/** Play Books-style scrubber: drag to preview a page number, release to jump there. */
@Composable
private fun ProgressBar(
    pageIndex: Int,
    pageCount: Int,
    unit: String,
    color: Color,
    background: Color,
    onSeek: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.roundToInt() ?: pageIndex
    Surface(color = background) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = dragging ?: pageIndex.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.roundToInt()) }
                    dragging = null
                },
                valueRange = 0f..maxOf(1, pageCount - 1).toFloat(),
                enabled = pageCount > 1,
                colors = SliderDefaults.colors(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "$unit${shown + 1} / $pageCount",
                color = color,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun NoticeEffect(notice: Notice?, snackbar: SnackbarHostState, controller: ReaderController) {
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        val (message, action) = when (n) {
            is Notice.Resumed -> "Resumed on page ${n.pageIndex + 1}" to "Start over"
            is Notice.SyncedFromOtherDevice ->
                "Moved to page ${n.pageIndex + 1}, where you left off on another device" to null
            is Notice.OtherDeviceAvailable ->
                "You reached page ${n.position.pageIndex + 1} on another device" to "Go there"
        }
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = action,
            withDismissAction = action != null,
            duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (n) {
                is Notice.Resumed -> controller.restartFromBeginning()
                is Notice.OtherDeviceAvailable -> controller.jumpTo(n.position)
                is Notice.SyncedFromOtherDevice -> Unit
            }
        }
        controller.dismissNotice()
    }
}

@Composable
private fun PdfPages(doc: PagedDocument, state: ReaderUiState, controller: ReaderController, onTap: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFE0E0E0)).readerTaps(null, onTap)) {
        val viewportWidthPx = constraints.maxWidth
        val viewportHeightPx = constraints.maxHeight
        val layoutKey = state.layoutKey(viewportWidthPx)
        val gapPx = with(density) { 8.dp.roundToPx() }
        val contentWidthPx = PageLayout.contentWidthPx(viewportWidthPx, state.zoom)
        val pages = remember(doc, contentWidthPx, gapPx) { PageLayout(doc.pageSizes, contentWidthPx, gapPx) }
        val listState = rememberLazyListState()
        val hScroll = rememberScrollState()

        LaunchedEffect(layoutKey) { controller.onLayout(layoutKey) }

        // Apply a restore target. Keyed on the layout too, so a rotation mid-restore recomputes
        // pixel offsets for the new page sizes.
        val restoring = state.phase as? Phase.Restoring
        LaunchedEffect(restoring?.token, layoutKey) {
            val r = restoring ?: return@LaunchedEffect
            val target = r.target
            val scroll = if (target == null) ScrollTarget(0, 0)
            else pages.scrollTargetFor(target.pageIndex, target.pageOffset)
            listState.scrollToItem(scroll.index, scroll.offsetPx)
            if (state.zoom > 1f) {
                withTimeoutOrNull(500) { snapshotFlow { hScroll.maxValue }.first { it > 0 } }
                hScroll.scrollTo(((target?.horizontalOffset ?: 0f) * hScroll.maxValue).roundToInt())
            } else {
                hScroll.scrollTo(0)
            }
            withFrameNanos { } // let the list measure, so the reported position is where it landed
            val index = listState.firstVisibleItemIndex
            controller.onRestoreApplied(
                token = r.token,
                layout = layoutKey,
                pageIndex = index,
                pageOffset = pages.fractionAt(index, listState.firstVisibleItemScrollOffset),
                horizontalOffset = fraction(hScroll.value, hScroll.maxValue),
                atEnd = !listState.canScrollForward,
            )
        }

        LaunchedEffect(layoutKey, pages) {
            snapshotFlow {
                Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, hScroll.value)
            }.collect { (index, offset, h) ->
                controller.onScrolled(
                    layout = layoutKey,
                    pageIndex = index,
                    pageOffset = pages.fractionAt(index, offset),
                    horizontalOffset = fraction(h, hScroll.maxValue),
                )
            }
        }

        Box(Modifier.horizontalScroll(hScroll)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.exactSizePx(contentWidthPx, viewportHeightPx),
            ) {
                items(count = doc.pageCount, key = { it }) { index ->
                    Page(doc, index, contentWidthPx, pages.pageHeightsPx[index], gapPx)
                }
            }
        }
    }
}

@Composable
internal fun Page(doc: PagedDocument, index: Int, widthPx: Int, heightPx: Int, gapPx: Int) {
    val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
        value = doc.render(index, widthPx)
    }
    Box(Modifier.exactSizePx(widthPx, heightPx + gapPx)) {
        val page = Modifier.exactSizePx(widthPx, heightPx).background(Color.White)
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = page,
            )
        } ?: Box(page)
    }
}

/** Exact pixel size: page offsets must match [PageLayout] to the pixel. */
internal fun Modifier.exactSizePx(width: Int, height: Int) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

private fun fraction(value: Int, max: Int): Float = if (max > 0) value.toFloat() / max else 0f
