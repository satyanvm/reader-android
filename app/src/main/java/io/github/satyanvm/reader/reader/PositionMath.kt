package io.github.satyanvm.reader.reader

import kotlin.math.roundToInt

/** Page dimensions in PDF points. */
data class PageSize(val width: Int, val height: Int)

/** Where a vertical scroll lands: list item and pixel offset into it. */
data class ScrollTarget(val index: Int, val offsetPx: Int)

/**
 * Converts between device-independent page fractions and pixel scroll offsets for a given
 * layout. Pages are laid out at [contentWidthPx] wide, so their heights (and therefore every
 * pixel offset) depend on the screen and zoom while the fractions do not.
 */
class PageLayout(
    pages: List<PageSize>,
    val contentWidthPx: Int,
    /** Space below each page, part of that page's list item. */
    val gapPx: Int,
) {
    val pageHeightsPx: IntArray = IntArray(pages.size) { i ->
        val p = pages[i]
        maxOf(1, (contentWidthPx.toLong() * p.height / maxOf(1, p.width)).toInt())
    }

    val pageCount: Int get() = pageHeightsPx.size

    /** Fraction of page [index] above the viewport top, given LazyColumn's first-visible state. */
    fun fractionAt(index: Int, offsetPx: Int): Float {
        if (index !in pageHeightsPx.indices) return 0f
        return (offsetPx.toFloat() / pageHeightsPx[index]).coerceIn(0f, 1f)
    }

    fun scrollTargetFor(pageIndex: Int, fraction: Float): ScrollTarget {
        if (pageCount == 0) return ScrollTarget(0, 0)
        val index = pageIndex.coerceIn(0, pageCount - 1)
        val offset = (fraction.coerceIn(0f, 1f) * pageHeightsPx[index]).roundToInt()
        return ScrollTarget(index, offset)
    }

    companion object {
        fun contentWidthPx(viewportWidthPx: Int, zoom: Float): Int =
            maxOf(1, (viewportWidthPx * zoom).roundToInt())
    }
}
