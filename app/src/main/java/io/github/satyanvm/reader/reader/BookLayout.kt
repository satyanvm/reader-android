package io.github.satyanvm.reader.reader

import kotlin.math.min

/**
 * One measured piece of a book: a text block (as its line positions) or a picture. Heights are
 * in px at the page width; [item] is the unit positions are counted in (an EPUB block, a PDF
 * source page).
 */
internal class Piece(
    val item: Int,
    /** Line tops and bottoms from the text layout; null for a picture. */
    val lineTops: FloatArray? = null,
    val lineBottoms: FloatArray? = null,
    /** A picture's height at its full size on the page. */
    val pictureHeight: Float = 0f,
    val gapBefore: Float = 0f,
    val gapAfter: Float = 0f,
    /** Starts a new page (a chapter). Its [gapBefore] is kept even at the top of the page. */
    val breakBefore: Boolean = false,
    /** Don't leave this alone at the bottom of a page (a heading). */
    val keepWithNext: Boolean = false,
) {
    val isText: Boolean get() = lineTops != null
    val lineCount: Int get() = lineTops?.size ?: 0
    val height: Float get() = lineBottoms?.lastOrNull() ?: if (lineTops != null) 0f else pictureHeight
    fun top(line: Int): Float = lineTops!![line]
    fun bottom(line: Int): Float = lineBottoms!![line]
}

/** Part of a piece on a page: lines [from, to) of text, or the whole picture. [y] is from the page's top. */
internal data class Slice(val piece: Int, val from: Int, val to: Int, val y: Float, val height: Float)

/** A page, and where it starts: [yInItem] px into item [item]. */
internal class BookPage(val slices: List<Slice>, val item: Int, val yInItem: Float)

/**
 * A book laid out into fixed pages, like Play Books: each page is a screenful, numbered from 1,
 * that starts and ends on whole lines. Plain arithmetic over measured [Piece]s, so it's testable
 * without a device.
 */
internal class BookLayout(val pages: List<BookPage>, private val itemHeights: FloatArray) {
    val pageCount: Int get() = pages.size

    /** Where page [page] starts, as (item, fraction down the item). */
    fun anchor(page: Int): Pair<Int, Float> {
        val p = pages[page.coerceIn(0, pages.size - 1)]
        val h = itemHeights.getOrElse(p.item) { 0f }
        return p.item to if (h > 0f) (p.yInItem / h).coerceIn(0f, 1f) else 0f
    }

    /** The page showing (item, fraction): the last one starting at or before it. */
    fun pageFor(item: Int, fraction: Float): Int {
        val y = fraction.coerceIn(0f, 1f) * itemHeights.getOrElse(item) { 0f }
        var lo = 0
        var hi = pages.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = pages[mid]
            if (p.item < item || (p.item == item && p.yInItem <= y + EPS)) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        private const val EPS = 0.5f

        fun paginate(pieces: List<Piece>, itemCount: Int, pageHeight: Float): BookLayout {
            // Where each piece sits within its item, for positions.
            val itemHeights = FloatArray(itemCount)
            val pieceY = FloatArray(pieces.size)
            for ((i, pc) in pieces.withIndex()) {
                if (pc.item !in 0 until itemCount) continue
                pieceY[i] = itemHeights[pc.item] + pc.gapBefore
                itemHeights[pc.item] += pc.gapBefore + pc.height + pc.gapAfter
            }

            val h = pageHeight
            val pages = ArrayList<BookPage>()
            var slices = ArrayList<Slice>()
            var y = 0f
            fun newPage() {
                if (slices.isEmpty()) return
                val first = slices[0]
                val pc = pieces[first.piece]
                pages += BookPage(slices, pc.item, pieceY[first.piece] + if (pc.isText) pc.top(first.from) else 0f)
                slices = ArrayList()
                y = 0f
            }

            for ((i, pc) in pieces.withIndex()) {
                if (pc.isText && pc.lineCount == 0) continue
                if (pc.breakBefore) newPage()
                var gap = if (slices.isEmpty() && !pc.breakBefore) 0f else pc.gapBefore
                if (pc.keepWithNext && slices.isNotEmpty()) {
                    val need = gap + min(pc.height, h) + firstLinesHeight(pieces.getOrNull(i + 1), h)
                    if (y + need > h + EPS) {
                        newPage()
                        gap = 0f
                    }
                }
                if (pc.isText) {
                    y += gap
                    var line = 0
                    while (line < pc.lineCount) {
                        val top = pc.top(line)
                        var end = line
                        while (end < pc.lineCount && pc.bottom(end) - top <= h - y + EPS) end++
                        if (end == line) {
                            if (slices.isNotEmpty()) {
                                newPage()
                                continue
                            }
                            end = line + 1 // a line taller than the page gets a page of its own
                        }
                        val sliceHeight = pc.bottom(end - 1) - top
                        slices += Slice(i, line, end, y, sliceHeight)
                        y += sliceHeight
                        line = end
                        if (line < pc.lineCount) newPage()
                    }
                    y += pc.gapAfter
                } else {
                    val pictureHeight = min(pc.height, h)
                    if (slices.isNotEmpty() && y + gap + pictureHeight > h + EPS) {
                        newPage()
                        gap = 0f
                    }
                    y += gap
                    slices += Slice(i, 0, 0, y, pictureHeight)
                    y += pictureHeight + pc.gapAfter
                }
            }
            newPage()
            return BookLayout(pages, itemHeights)
        }

        /** Room the start of [next] needs below a heading: its first two lines, or the picture. */
        private fun firstLinesHeight(next: Piece?, pageHeight: Float): Float = when {
            next == null || next.breakBefore -> 0f
            next.isText && next.lineCount > 0 -> next.gapBefore + next.bottom(min(1, next.lineCount - 1)) - next.top(0)
            next.isText -> 0f
            else -> next.gapBefore + min(next.height, pageHeight)
        }
    }
}
