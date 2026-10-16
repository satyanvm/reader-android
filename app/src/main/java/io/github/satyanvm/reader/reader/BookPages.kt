package io.github.satyanvm.reader.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.satyanvm.reader.text.Block
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.TextDocument
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

/** What a book is made of before it's measured. [item] is the unit positions are counted in. */
internal sealed interface BookContent {
    val item: Int
    /** Starts a chapter, so it starts a new page. */
    val chapterStart: Boolean

    class Text(override val item: Int, val block: Block, override val chapterStart: Boolean) : BookContent

    /** An EPUB image ([path]) or a PDF page with no text, shown as its image ([pdfPage]). */
    class Picture(
        override val item: Int,
        val path: String?,
        val pdfPage: Int?,
        override val chapterStart: Boolean,
    ) : BookContent
}

/** A picture's pixel size (to lay it out) and its image at a given width (to draw it). */
internal interface PictureLoader {
    suspend fun size(picture: BookContent.Picture): IntSize?
    suspend fun image(picture: BookContent.Picture, widthPx: Int): ImageBitmap?
}

/** The book's pages, for the scrubber: which one is showing, how many, and a way to jump. */
internal class BookProgress {
    var page by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)
    /** Only part of the book is paginated so far (a PDF still being prepared). */
    var partial by mutableStateOf(false)
    var seek: (Int) -> Unit = {}
}

/** What to paginate: [contents] cover items [items] (all of them, or the part ready so far). */
internal class BookSource(val contents: List<BookContent>, val items: IntRange, val preparing: String? = null)

/** A measured, paginated book. [contents] and [pieces] line up index for index. */
internal class MeasuredBook(
    val source: BookSource,
    val contents: List<BookContent>,
    val pieces: List<Piece>,
    /** A picture's width on the page at its full height; 0 for text. */
    val pictureWidths: FloatArray,
    val layout: BookLayout,
)

/** Where the text column sits on a page, in px. */
private class PageFrame(val left: Float, val top: Float, val width: Float, val height: Float, val footer: Float)

private val PAGE_TOP = 36.dp
private val PAGE_BOTTOM = 52.dp

/**
 * Flowing text as a book of fixed pages, like Play Books: the text is laid out for this screen
 * and text size into pages numbered 1 to N, each starting and ending on whole lines (chapters
 * start on a new page). Pages turn like paper ([drawTurn]); the page number is at the bottom.
 */
@Composable
private fun BookPages(
    source: BookSource,
    itemCount: Int,
    pictures: PictureLoader,
    state: ReaderUiState,
    controller: ReaderController,
    colors: ReaderColors,
    progress: BookProgress,
    controlsShown: Boolean,
    onMenuTap: () -> Unit,
    /** Saved position (page/chapter, fraction) → (item, fraction within it), and back. */
    toItem: (Int, Float) -> Pair<Int, Float>,
    fromItem: (Int, Float) -> Pair<Int, Float>,
    scanned: (item: Int) -> Boolean = { false },
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val heightPx = constraints.maxHeight
        val layoutKey = state.layoutKey(constraints.maxWidth).copy(paged = true, viewportHeightPx = heightPx)
        val margin = margin(maxWidth)
        val columnWidthPx = with(density) { (maxWidth - margin * 2).roundToPx() }
        val frame = with(density) {
            PageFrame(
                left = margin.toPx(),
                top = PAGE_TOP.toPx(),
                width = columnWidthPx.toFloat(),
                height = heightPx - PAGE_TOP.toPx() - PAGE_BOTTOM.toPx(),
                footer = heightPx - PAGE_BOTTOM.toPx() / 2,
            )
        }
        // Measured without a colour, so a theme change only repaints.
        val body = remember(state.textScale) { bodyStyle(state.textScale, Color.Unspecified) }
        val offMain = remember(context, density, direction) {
            TextMeasurer(createFontFamilyResolver(context), density, direction, cacheSize = 0)
        }
        val turns = remember(columnWidthPx, frame.height, body) { PageTurns() }
        // Where the reader is, as (item, fraction), so re-paginating keeps the place.
        val anchor = remember { floatArrayOf(state.pageIndex.toFloat(), 0f) }
        val currentSource by rememberUpdatedState(source)
        val book by produceState<MeasuredBook?>(null, columnWidthPx, frame.height, body, offMain, turns) {
            value = null
            val measured = ConcurrentHashMap<BookContent, Measured>()
            // As more of the book is ready (a PDF being prepared), it's paginated again without a
            // spinner; the swap waits for any page turn in progress to finish.
            snapshotFlow { currentSource }.collectLatest { src ->
                val next = measureBook(src, itemCount, offMain, body, columnWidthPx, frame.height, density, pictures, measured)
                snapshotFlow { turns.turn }.first { it == null }
                turns.count = next.layout.pageCount
                if (next.layout.pageCount > 0) {
                    val (item, fraction) = toItem(anchor[0].toInt(), anchor[1])
                    turns.show(next.layout.pageFor(item, fraction))
                }
                value = next
            }
        }

        LaunchedEffect(layoutKey) { controller.onLayout(layoutKey) }

        val b = book
        if (b == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val scope = rememberCoroutineScope()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val renderer = remember(b, measurer, body, columnWidthPx) { PageRenderer(b, measurer, body, columnWidthPx) }
        val images = remember(b) { mutableStateMapOf<Int, ImageBitmap>() }
        val currentKey by rememberUpdatedState(layoutKey)
        val shown by rememberUpdatedState(controlsShown)
        val menuTap by rememberUpdatedState(onMenuTap)

        val currentPage = turns.current
        SideEffect {
            turns.onTurned = { page ->
                val (item, fraction) = b.layout.anchor(page)
                val (pageIndex, offset) = fromItem(item, fraction)
                anchor[0] = pageIndex.toFloat()
                anchor[1] = offset
                controller.onScrolled(currentKey, pageIndex, offset, horizontalOffset = 0f)
            }
            progress.page = currentPage
        }
        DisposableEffect(b) {
            progress.count = b.layout.pageCount
            progress.partial = b.source.preparing != null
            progress.seek = { turns.jump(it) }
            onDispose { progress.count = 0 }
        }

        val restoring = state.phase as? Phase.Restoring
        LaunchedEffect(restoring?.token, layoutKey, b) {
            val r = restoring ?: return@LaunchedEffect
            val (item, fraction) = toItem(r.target?.pageIndex ?: 0, r.target?.pageOffset ?: 0f)
            // Not paginated yet (a PDF page still being prepared): wait for the book that has it.
            if (item !in b.source.items) return@LaunchedEffect
            anchor[0] = (r.target?.pageIndex ?: 0).toFloat()
            anchor[1] = r.target?.pageOffset ?: 0f
            val page = if (b.layout.pageCount == 0) 0 else b.layout.pageFor(item, fraction)
            turns.show(page)
            val (pageIndex, offset) = if (b.layout.pageCount == 0) 0 to 0f
            else b.layout.anchor(page).let { (i, f) -> fromItem(i, f) }
            controller.onRestoreApplied(
                token = r.token,
                layout = layoutKey,
                pageIndex = pageIndex,
                pageOffset = offset,
                horizontalOffset = 0f,
                atEnd = page >= b.layout.pageCount - 1,
            )
        }

        // Pictures on this page and the ones either side, ready before they're turned to.
        LaunchedEffect(b, currentPage) {
            val pages = b.layout.pages
            val wanted = (currentPage - 1..currentPage + 2)
                .filter { it in pages.indices }
                .flatMap { pages[it].slices }
                .map { it.piece }
                .filter { b.contents[it] is BookContent.Picture }
                .toSet()
            (images.keys - wanted).forEach { images.remove(it) }
            for (piece in wanted) {
                if (piece in images) continue
                val picture = b.contents[piece] as BookContent.Picture
                pictures.image(picture, b.pictureWidths[piece].roundToInt().coerceAtLeast(1))?.let { images[piece] = it }
            }
        }

        val footerStyle = remember(colors) { TextStyle(color = colors.muted, fontSize = 12.sp) }
        Canvas(Modifier.fillMaxSize().pageGestures(turns, scope, { shown }, { menuTap() })) {
            drawTurn(turns, colors.background) { page ->
                drawRect(colors.background)
                if (page !in b.layout.pages.indices) return@drawTurn
                drawPageText(b, page, renderer, frame, colors, images)
                val item = b.layout.pages[page].item
                val count = b.source.preparing?.let { "${page + 1} · $it" } ?: "${page + 1} of ${b.layout.pageCount}"
                val label = (if (scanned(item)) "Scanned text · " else "") + count
                val footer = measurer.measure(AnnotatedString(label), footerStyle)
                drawText(
                    footer,
                    topLeft = Offset((size.width - footer.size.width) / 2f, frame.footer - footer.size.height / 2f),
                )
            }
        }
    }
}

/** Lays text out again for drawing (keeping the last several), exactly as it was measured. */
private class PageRenderer(
    private val book: MeasuredBook,
    private val measurer: TextMeasurer,
    private val body: TextStyle,
    private val columnWidthPx: Int,
) {
    private val cache = object : LinkedHashMap<Int, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextLayoutResult>?) = size > 96
    }

    fun layout(piece: Int): TextLayoutResult = cache.getOrPut(piece) {
        val block = (book.contents[piece] as BookContent.Text).block
        measurer.measure(block.annotated(), block.styled(body), constraints = Constraints(maxWidth = columnWidthPx))
    }
}

private fun DrawScope.drawPageText(
    b: MeasuredBook,
    page: Int,
    renderer: PageRenderer,
    frame: PageFrame,
    colors: ReaderColors,
    images: Map<Int, ImageBitmap>,
) {
    for (s in b.layout.pages[page].slices) {
        val y = frame.top + s.y
        when (b.contents[s.piece]) {
            is BookContent.Text -> {
                val r = renderer.layout(s.piece)
                if (s.from >= r.lineCount) continue
                val top = r.getLineTop(s.from)
                val bottom = r.getLineBottom(minOf(s.to, r.lineCount) - 1)
                clipRect(top = y, bottom = y + (bottom - top)) {
                    drawText(r, color = colors.text, topLeft = Offset(frame.left, y - top))
                }
            }
            is BookContent.Picture -> {
                val full = b.pieces[s.piece].height
                val w = b.pictureWidths[s.piece] * if (full > 0f) s.height / full else 1f
                val x = frame.left + (frame.width - w) / 2f
                val image = images[s.piece]
                if (image != null) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), s.height.roundToInt()),
                    )
                } else {
                    drawRect(colors.muted.copy(alpha = 0.08f), topLeft = Offset(x, y), size = Size(w, s.height))
                }
            }
        }
    }
}

/** A block measured at the column width: its piece and, for a picture, its width on the page. */
private class Measured(val piece: Piece, val width: Float)

/**
 * Measures every block at the column width (off the main thread, reusing [cache] for blocks
 * measured before) and paginates the result.
 */
private suspend fun measureBook(
    source: BookSource,
    itemCount: Int,
    measurer: TextMeasurer,
    body: TextStyle,
    columnWidthPx: Int,
    pageHeight: Float,
    density: Density,
    pictures: PictureLoader,
    cache: MutableMap<BookContent, Measured>,
): MeasuredBook = withContext(Dispatchers.Default) {
    val contents = source.contents
    val kept = ArrayList<BookContent>(contents.size)
    val pieces = ArrayList<Piece>(contents.size)
    val widths = ArrayList<Float>(contents.size)
    val constraints = Constraints(maxWidth = columnWidthPx)
    with(density) {
        for (c in contents) {
            coroutineContext.ensureActive()
            val known = cache[c]
            if (known != null) {
                pieces += known.piece
                widths += known.width
                kept += c
                continue
            }
            val chapterGap = if (c.chapterStart) CHAPTER_GAP.toPx() else 0f
            when (c) {
                is BookContent.Text -> {
                    val r = measurer.measure(c.block.annotated(), c.block.styled(body), constraints = constraints)
                    val heading = c.block.kind == Block.Kind.HEADING
                    pieces += Piece(
                        item = c.item,
                        lineTops = FloatArray(r.lineCount) { r.getLineTop(it) },
                        lineBottoms = FloatArray(r.lineCount) { r.getLineBottom(it) },
                        gapBefore = chapterGap + if (heading) HEADING_GAP_BEFORE.toPx() else 0f,
                        gapAfter = if (heading) HEADING_GAP_AFTER.toPx() else 0f,
                        breakBefore = c.chapterStart,
                        keepWithNext = heading,
                    )
                    widths += 0f
                    cache[c] = Measured(pieces.last(), 0f)
                }
                is BookContent.Picture -> {
                    val size = pictures.size(c)?.takeIf { it.width > 0 && it.height > 0 } ?: continue
                    // A PDF page fills the column; an EPUB image is shown at its own size, at most the column.
                    val w = if (c.pdfPage != null) columnWidthPx.toFloat()
                    else min(columnWidthPx.toFloat(), size.width * density.density)
                    pieces += Piece(
                        item = c.item,
                        pictureHeight = w * size.height / size.width,
                        gapBefore = chapterGap + PICTURE_GAP.toPx(),
                        gapAfter = PICTURE_GAP.toPx(),
                        breakBefore = c.chapterStart,
                    )
                    widths += w
                    cache[c] = Measured(pieces.last(), w)
                }
            }
            kept += c
        }
    }
    MeasuredBook(source, kept, pieces, widths.toFloatArray(), BookLayout.paginate(pieces, itemCount, pageHeight))
}

/** An EPUB as a book of pages. Positions are per chapter, as in the scrolling view. */
@Composable
internal fun EpubBookPages(
    book: EpubBook,
    state: ReaderUiState,
    controller: ReaderController,
    colors: ReaderColors,
    progress: BookProgress,
    controlsShown: Boolean,
    onMenuTap: () -> Unit,
) {
    val contents = remember(book) {
        val out = ArrayList<BookContent>()
        var item = 0
        book.chapters.forEachIndexed { chapter, blocks ->
            blocks.forEachIndexed { i, block ->
                val start = chapter > 0 && i == 0
                if (block.kind == Block.Kind.IMAGE) {
                    block.image?.let { out += BookContent.Picture(item, it, null, start) }
                } else {
                    out += BookContent.Text(item, block, start)
                }
                item++
            }
        }
        out
    }
    val pictures = remember(book) {
        object : PictureLoader {
            override suspend fun size(picture: BookContent.Picture): IntSize? = withContext(Dispatchers.IO) {
                val bytes = picture.path?.let(book::imageBytes) ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                IntSize(bounds.outWidth, bounds.outHeight)
            }

            override suspend fun image(picture: BookContent.Picture, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
                decodeScaled(picture.path?.let(book::imageBytes), widthPx)?.asImageBitmap()
            }
        }
    }
    val source = remember(contents) { BookSource(contents, 0 until book.positions.totalBlocks) }
    BookPages(
        source = source,
        itemCount = book.positions.totalBlocks,
        pictures = pictures,
        state = state,
        controller = controller,
        colors = colors,
        progress = progress,
        controlsShown = controlsShown,
        onMenuTap = onMenuTap,
        toItem = { chapter, fraction -> book.positions.toFlat(chapter, fraction) },
        fromItem = { index, fraction -> book.positions.fromFlat(index, fraction) },
    )
}

/**
 * A PDF's flowing text as a book of pages. Text is prepared page by page (once per device; it's
 * cached), starting where the reader is. Pages turn as soon as the reader's page is ready: the
 * book is paginated from the run of prepared pages around it, and again as that run grows.
 */
@Composable
internal fun PdfBookPages(
    text: TextDocument,
    pdf: PagedDocument,
    state: ReaderUiState,
    controller: ReaderController,
    colors: ReaderColors,
    progress: BookProgress,
    controlsShown: Boolean,
    onMenuTap: () -> Unit,
) {
    // The PDF page the reader is on, or is being taken to.
    val restoring = state.phase as? Phase.Restoring
    val pivot = (restoring?.target?.pageIndex ?: state.pageIndex).coerceIn(0, maxOf(0, text.pageCount - 1))
    val currentPivot by rememberUpdatedState(pivot)
    val window by remember(text) { derivedStateOf { readyRun(text, currentPivot) } }
    LaunchedEffect(text, pivot) {
        // Usually already under way (the background preparation starts where the reader is).
        for (i in pivot until minOf(text.pageCount, pivot + 2)) runCatching { text.page(i) }
    }

    // Each page's content is made once, so its measurement can be reused as the book grows.
    val perPage = remember(text) { HashMap<Int, List<BookContent>>() }
    val source = remember(text, window) {
        val run = window ?: return@remember null
        val out = ArrayList<BookContent>()
        for (i in run) {
            out += perPage.getOrPut(i) {
                val page = text.loaded[i]
                if (page == null || page.isEmpty) listOf(BookContent.Picture(i, null, i, chapterStart = false))
                else page.blocks.filter { it.kind != Block.Kind.IMAGE }.map { BookContent.Text(i, it, chapterStart = false) }
            }
        }
        val all = run.first == 0 && run.last == text.pageCount - 1
        BookSource(out, run, preparing = if (all) null else "Preparing pages: ${text.loaded.size} of ${text.pageCount}")
    }
    if (source == null) {
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val pictures = remember(pdf) {
        object : PictureLoader {
            override suspend fun size(picture: BookContent.Picture): IntSize? =
                picture.pdfPage?.let { pdf.pageSizes.getOrNull(it) }?.let { IntSize(it.width, it.height) }

            override suspend fun image(picture: BookContent.Picture, widthPx: Int): ImageBitmap? =
                picture.pdfPage?.let { runCatching { pdf.render(it, widthPx).asImageBitmap() }.getOrNull() }
        }
    }
    BookPages(
        source = source,
        itemCount = text.pageCount,
        pictures = pictures,
        state = state,
        controller = controller,
        colors = colors,
        progress = progress,
        controlsShown = controlsShown,
        onMenuTap = onMenuTap,
        toItem = { page, fraction -> page to fraction },
        fromItem = { page, fraction -> page to fraction },
        scanned = { text.loaded[it]?.recognized == true },
    )
}

/**
 * The run of prepared pages around [pivot], or null if [pivot] isn't ready. It only grows a few
 * pages at a time (or when it reaches either end of the book), so the book isn't re-paginated
 * for every page.
 */
private fun readyRun(text: TextDocument, pivot: Int): IntRange? {
    if (pivot !in text.loaded) return null
    var lo = pivot
    var hi = pivot
    while (lo - 1 in text.loaded) lo--
    while (hi + 1 in text.loaded) hi++
    val last = text.pageCount - 1
    if (hi != last) hi = maxOf(pivot, hi - hi % READY_STEP)
    if (lo != 0) lo = minOf(pivot, lo + (READY_STEP - lo % READY_STEP) % READY_STEP)
    return lo..hi
}

private const val READY_STEP = 4
