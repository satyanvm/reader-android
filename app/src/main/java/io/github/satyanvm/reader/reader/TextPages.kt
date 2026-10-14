package io.github.satyanvm.reader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.satyanvm.reader.text.Block
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.PageText
import io.github.satyanvm.reader.text.TextDocument
import io.github.satyanvm.reader.text.TextFlow
import io.github.satyanvm.reader.text.TextTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class ReaderColors(val background: Color, val text: Color, val muted: Color)

fun TextTheme.colors() = when (this) {
    TextTheme.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF8A8A8A))
    TextTheme.SEPIA -> ReaderColors(Color(0xFFF5ECD9), Color(0xFF4A3A2A), Color(0xFF9C8B75))
    TextTheme.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFDADADA), Color(0xFF7A7A7A))
}

val TextTheme.label: String
    get() = when (this) {
        TextTheme.LIGHT -> "Light"
        TextTheme.SEPIA -> "Sepia"
        TextTheme.DARK -> "Dark"
    }

val TextFlow.label: String
    get() = when (this) {
        TextFlow.PAGED -> "Page by page"
        TextFlow.CONTINUOUS -> "Continuous scroll"
    }

/** Comfortable line length on tablets; phones use the full width minus margins. */
private val MAX_TEXT_WIDTH = 680.dp
private val MIN_MARGIN = 24.dp

private fun margin(maxWidth: Dp) = maxOf(MIN_MARGIN, (maxWidth - MAX_TEXT_WIDTH) / 2)

private fun bodyStyle(scale: Float, colors: ReaderColors) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = (19 * scale).sp,
    lineHeight = 1.55.em,
    color = colors.text,
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Paragraph,
)

/**
 * Flowing text for a PDF. One list item per source page, so a position ("page 186, 40% down")
 * is the same place here as in the original-pages view. A page with no text even after OCR
 * (a figure, a photo) is shown as its page image.
 */
@Composable
internal fun TextPages(
    text: TextDocument,
    pdf: PagedDocument,
    state: ReaderUiState,
    controller: ReaderController,
    colors: ReaderColors,
    paged: Boolean,
    onMenuTap: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val layoutKey = state.layoutKey(constraints.maxWidth).copy(paged = paged)
        val margin = margin(maxWidth)
        val columnWidthPx = with(density) { (maxWidth - margin * 2).roundToPx() }
        val listState = rememberLazyListState()
        val pager = remember(listState) { Pager(listState) }.takeIf { paged }
        val style = remember(state.textScale, colors) { bodyStyle(state.textScale, colors) }

        TrackPosition(
            pager = pager,
            listState = listState,
            state = state,
            controller = controller,
            layoutKey = layoutKey,
            prepare = { pageIndex, _ -> text.page(pageIndex.coerceIn(0, maxOf(0, text.pageCount - 1))) },
            toItem = { page, fraction -> page to fraction },
            fromItem = { index, fraction -> if (index in text.loaded) index to fraction else index to 0f },
        )

        TextList(listState, pager, margin, maxHeight, colors, onMenuTap) {
            items(count = text.pageCount, key = { it }) { index ->
                TextPage(text, pdf, index, columnWidthPx, style, colors, pager)
            }
        }

        val recognized by remember {
            derivedStateOf { text.loaded[listState.firstVisibleItemIndex]?.recognized == true }
        }
        if (recognized) {
            Text(
                text = "Text recognized from a scanned page, so it may contain mistakes.",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.background.copy(alpha = 0.95f))
                    .padding(horizontal = margin, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TextPage(
    text: TextDocument,
    pdf: PagedDocument,
    index: Int,
    widthPx: Int,
    style: TextStyle,
    colors: ReaderColors,
    pager: Pager?,
) {
    val page: PageText? = text.loaded[index]
    LaunchedEffect(index, page == null) {
        if (page == null) runCatching { text.page(index) }
    }
    when {
        page == null -> Box(
            Modifier.fillMaxWidth().height(320.dp).pagerEntry(rememberPagerEntry(pager, index)),
            contentAlignment = Alignment.Center,
        ) {
            Text("…", color = colors.muted)
        }
        page.isEmpty -> {
            val size = pdf.pageSizes[index]
            val heightPx = maxOf(1, (widthPx.toLong() * size.height / maxOf(1, size.width)).toInt())
            Box(Modifier.padding(vertical = 12.dp).pagerEntry(rememberPagerEntry(pager, index))) {
                Page(pdf, index, widthPx, heightPx, gapPx = 0)
            }
        }
        else -> Column {
            for (block in page.blocks) BlockView(block, style, colors, image = null, rememberPagerEntry(pager, index))
        }
    }
}

/** An EPUB in flowing text. One list item per block; positions are counted per chapter. */
@Composable
internal fun EpubPages(
    book: EpubBook,
    state: ReaderUiState,
    controller: ReaderController,
    colors: ReaderColors,
    paged: Boolean,
    onMenuTap: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val layoutKey = state.layoutKey(constraints.maxWidth).copy(paged = paged)
        val margin = margin(maxWidth)
        val columnWidthPx = with(density) { (maxWidth - margin * 2).roundToPx() }
        val listState = rememberLazyListState()
        val pager = remember(listState) { Pager(listState) }.takeIf { paged }
        val style = remember(state.textScale, colors) { bodyStyle(state.textScale, colors) }
        val blocks = remember(book) { book.chapters.flatten() }
        val chapterStarts = remember(book) { book.chapters.indices.map { book.positions.chapterStart(it) }.toSet() }

        TrackPosition(
            pager = pager,
            listState = listState,
            state = state,
            controller = controller,
            layoutKey = layoutKey,
            prepare = { _, _ -> },
            toItem = { chapter, fraction -> book.positions.toFlat(chapter, fraction) },
            fromItem = { index, fraction -> book.positions.fromFlat(index, fraction) },
        )

        TextList(listState, pager, margin, maxHeight, colors, onMenuTap) {
            items(count = blocks.size, key = { it }) { index ->
                Column {
                    if (index > 0 && index in chapterStarts) Box(Modifier.height(48.dp))
                    BlockView(blocks[index], style, colors, image = { path ->
                        EpubImage(book, path, columnWidthPx)
                    }, rememberPagerEntry(pager, index))
                }
            }
        }
    }
}

/**
 * The flowing-text list: scrolls freely, or with a [pager] moves a screen at a time. Paged, the
 * vertical margin sits outside the list so each screen's first line starts below it, and there's
 * a screen of space after the end so the last screen can start on a whole line too. A tap (in
 * the middle of the screen, when paged) shows or hides the reading controls via [onMenuTap].
 */
@Composable
private fun TextList(
    listState: LazyListState,
    pager: Pager?,
    margin: Dp,
    maxHeight: Dp,
    colors: ReaderColors,
    onMenuTap: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val toolbar = LocalTextToolbar.current
    if (pager == null) {
        SelectionContainer(Modifier.readerTaps(toolbar, onMenuTap)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = margin, vertical = 16.dp),
                content = content,
            )
        }
        return
    }
    val scope = rememberCoroutineScope()
    PagerScreen(pager) {
        SelectionContainer(Modifier.padding(vertical = 16.dp).pageTurns(pager, scope, toolbar, onMenuTap)) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(start = margin, end = margin, bottom = maxHeight),
                modifier = Modifier.pagerViewport(pager, colors.background),
                content = content,
            )
        }
    }
}

/**
 * Restore-and-report wiring shared by the flowing-text views. A list item is the unit the view
 * scrolls by; [toItem]/[fromItem] convert between a saved (page, fraction) and (item, fraction
 * within the item).
 */
@Composable
private fun TrackPosition(
    pager: Pager?,
    listState: LazyListState,
    state: ReaderUiState,
    controller: ReaderController,
    layoutKey: LayoutKey,
    /** Make sure the target item has its real content (and so its real height) before scrolling. */
    prepare: suspend (pageIndex: Int, fraction: Float) -> Unit,
    toItem: (pageIndex: Int, fraction: Float) -> Pair<Int, Float>,
    fromItem: (itemIndex: Int, fractionInItem: Float) -> Pair<Int, Float>,
) {
    LaunchedEffect(layoutKey) { controller.onLayout(layoutKey) }

    val restoring = state.phase as? Phase.Restoring
    LaunchedEffect(restoring?.token, layoutKey) {
        val r = restoring ?: return@LaunchedEffect
        val page = r.target?.pageIndex ?: 0
        val fraction = r.target?.pageOffset ?: 0f
        prepare(page, fraction)
        val (item, within) = toItem(page, fraction)
        listState.scrollToItem(item)
        withFrameNanos { }
        listState.scrollToItem(item, (within * listState.itemHeight(item)).roundToInt())
        withFrameNanos { }
        pager?.alignTop()
        val first = listState.firstVisibleItemIndex
        val (actualPage, actualFraction) = fromItem(first, listState.fractionIn(first))
        controller.onRestoreApplied(
            token = r.token,
            layout = layoutKey,
            pageIndex = actualPage,
            pageOffset = actualFraction,
            horizontalOffset = 0f,
            atEnd = !listState.canScrollForward,
        )
    }

    LaunchedEffect(layoutKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, _) ->
                val (page, fraction) = fromItem(index, listState.fractionIn(index))
                controller.onScrolled(layoutKey, page, fraction, horizontalOffset = 0f)
            }
    }
}

@Composable
private fun BlockView(
    block: Block,
    style: TextStyle,
    colors: ReaderColors,
    image: (@Composable (String) -> Unit)?,
    /** Set when reading a screen at a time, to keep lines and images whole on one screen. */
    entry: Pager.Entry?,
) {
    when (block.kind) {
        Block.Kind.HEADING -> Text(
            text = block.annotated(),
            style = style.copy(
                fontSize = style.fontSize * 1.25f,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 1.35.em,
            ),
            onTextLayout = { entry?.text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 14.dp).pagerEntry(entry),
        )
        Block.Kind.PARAGRAPH -> Text(
            text = block.annotated(),
            style = style.copy(
                textIndent = if (block.continuation) null else TextIndent(firstLine = 1.6.em),
            ),
            onTextLayout = { entry?.text = it },
            modifier = Modifier.pagerEntry(entry),
        )
        Block.Kind.IMAGE -> block.image?.let { path ->
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.pagerEntry(entry)) { image?.invoke(path) }
            }
        }
    }
}

@Composable
private fun EpubImage(book: EpubBook, path: String, maxWidthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, book, path, maxWidthPx) {
        value = withContext(Dispatchers.IO) { decodeScaled(book.imageBytes(path), maxWidthPx) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun decodeScaled(bytes: ByteArray?, maxWidthPx: Int): Bitmap? {
    bytes ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun Block.annotated(): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
        ) { append(span.text) }
    }
}

private fun LazyListState.itemHeight(index: Int): Int =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0

/** How far down item [index] the viewport top is, if it's the first visible item. */
private fun LazyListState.fractionIn(index: Int): Float {
    val height = itemHeight(index)
    if (height <= 0 || index != firstVisibleItemIndex) return 0f
    return (firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f)
}
