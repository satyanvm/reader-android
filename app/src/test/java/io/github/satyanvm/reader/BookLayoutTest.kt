package io.github.satyanvm.reader

import io.github.satyanvm.reader.reader.BookLayout
import io.github.satyanvm.reader.reader.Piece
import io.github.satyanvm.reader.reader.Slice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLayoutTest {
    /** A paragraph of [lines] lines, 10px each. */
    private fun text(item: Int, lines: Int, gapBefore: Float = 0f, gapAfter: Float = 0f, breakBefore: Boolean = false, keep: Boolean = false) =
        Piece(
            item = item,
            lineTops = FloatArray(lines) { it * 10f },
            lineBottoms = FloatArray(lines) { it * 10f + 10f },
            gapBefore = gapBefore,
            gapAfter = gapAfter,
            breakBefore = breakBefore,
            keepWithNext = keep,
        )

    @Test
    fun `pages hold whole lines and every line appears exactly once`() {
        val pieces = listOf(text(0, 7), text(1, 5), text(2, 9))
        val book = BookLayout.paginate(pieces, itemCount = 3, pageHeight = 35f)
        for (page in book.pages) {
            val used = page.slices.sumOf { it.height.toDouble() }
            assertTrue("page overflows: $used", used <= 35.0)
        }
        val lines = book.pages.flatMap { it.slices }.groupBy { it.piece }.mapValues { (_, s) -> s.sumOf { it.to - it.from } }
        assertEquals(mapOf(0 to 7, 1 to 5, 2 to 9), lines)
        assertEquals(7, book.pageCount) // 21 lines, 3 per page
    }

    @Test
    fun `a paragraph split across pages continues on the next line`() {
        val book = BookLayout.paginate(listOf(text(0, 5)), itemCount = 1, pageHeight = 30f)
        assertEquals(listOf(Slice(0, 0, 3, 0f, 30f)), book.pages[0].slices)
        assertEquals(listOf(Slice(0, 3, 5, 0f, 20f)), book.pages[1].slices)
        assertEquals(0 to 0.6f, book.anchor(1))
    }

    @Test
    fun `positions map to the page showing them and back`() {
        val book = BookLayout.paginate(listOf(text(0, 10), text(1, 10)), itemCount = 2, pageHeight = 40f)
        assertEquals(0, book.pageFor(0, 0f))
        assertEquals(0, book.pageFor(0, 0.35f)) // line 3 is on the first page
        assertEquals(1, book.pageFor(0, 0.45f))
        assertEquals(2, book.pageFor(1, 0f)) // item 1 starts mid-page 2 (index 2)
        for (p in 0 until book.pageCount) {
            val (item, fraction) = book.anchor(p)
            assertEquals(p, book.pageFor(item, fraction))
        }
    }

    @Test
    fun `chapters start on a new page and headings aren't left at the bottom`() {
        val pieces = listOf(
            text(0, 2),
            text(1, 1, gapBefore = 5f, keep = true), // heading: needs itself + 2 lines of what follows
            text(2, 3),
            text(3, 1, breakBefore = true),
        )
        val book = BookLayout.paginate(pieces, itemCount = 4, pageHeight = 40f)
        assertEquals(listOf(0), book.pages[0].slices.map { it.piece })
        assertEquals(listOf(1, 2), book.pages[1].slices.map { it.piece })
        assertEquals(0f, book.pages[1].slices[0].y) // no gap at the top of a page
        assertEquals(listOf(3), book.pages.last().slices.map { it.piece })
    }

    @Test
    fun `tall pictures are fitted to one page`() {
        val pieces = listOf(text(0, 2), Piece(item = 1, pictureHeight = 500f), text(2, 1))
        val book = BookLayout.paginate(pieces, itemCount = 3, pageHeight = 100f)
        assertEquals(3, book.pageCount)
        assertEquals(100f, book.pages[1].slices.single().height)
    }

    @Test
    fun `an empty book has no pages but positions still resolve`() {
        val book = BookLayout.paginate(emptyList(), itemCount = 0, pageHeight = 100f)
        assertEquals(0, book.pageCount)
        assertEquals(0, book.pageFor(5, 0.5f))
    }
}
