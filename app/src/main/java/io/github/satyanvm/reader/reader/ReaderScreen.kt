package io.github.satyanvm.reader.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.TextTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val ZOOM_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f)
private val TEXT_STEPS = listOf(0.7f, 0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val textMode = state.mode == ReadingMode.TEXT
    val colors = if (textMode) theme.colors() else TextTheme.LIGHT.colors()

    // Periodic debounced saves are the primary mechanism; these are just prompt extras.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.flush() }
    DisposableEffect(controller) { onDispose { controller.flush() } }

    NoticeEffect(state.notice, snackbar, controller)

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
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
                        mode = state.mode.takeIf { state.document is PagedDocument },
                        onMode = vm::setMode,
                        onRestart = controller::restartFromBeginning,
                    )
                },
            )
        },
        bottomBar = {
            state.document?.let { doc ->
                ProgressBar(
                    pageIndex = state.pageIndex,
                    pageCount = doc.pageCount,
                    unit = if (doc is EpubBook) "Chapter " else "",
                    color = colors.text,
                    background = colors.background,
                    onSeek = controller::goToPage,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            val doc = state.document
            when {
                state.phase == Phase.Loading -> CircularProgressIndicator()
                state.phase is Phase.Failed -> Text((state.phase as Phase.Failed).message)
                doc == null -> Unit
                doc is EpubBook -> EpubPages(doc, state, controller, colors)
                doc !is PagedDocument -> Unit
                textMode && text != null -> TextPages(text!!, doc, state, controller, colors)
                else -> PdfPages(doc, state, controller)
            }
        }
    }
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
private fun OverflowMenu(mode: ReadingMode?, onMode: (ReadingMode) -> Unit, onRestart: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (mode != null) {
            DropdownMenuItem(
                text = { Text("Flowing text") },
                leadingIcon = { if (mode == ReadingMode.TEXT) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onMode(ReadingMode.TEXT)
                },
            )
            DropdownMenuItem(
                text = { Text("Original pages") },
                leadingIcon = { if (mode == ReadingMode.PAGES) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onMode(ReadingMode.PAGES)
                },
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("Start from the beginning") },
            onClick = {
                open = false
                onRestart()
            },
        )
    }
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
private fun PdfPages(doc: PagedDocument, state: ReaderUiState, controller: ReaderController) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFE0E0E0))) {
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
