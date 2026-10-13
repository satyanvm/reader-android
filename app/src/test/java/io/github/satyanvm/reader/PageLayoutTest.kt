package io.github.satyanvm.reader

import io.github.satyanvm.reader.reader.PageLayout
import io.github.satyanvm.reader.reader.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    private val letter = List(PAGES) { PageSize(612, 792) }

    @Test
    fun `same fraction maps to proportional pixels on any screen and zoom`() {
        for (viewport in listOf(720, 1080, 1440, 2560)) {
            for (zoom in listOf(1f, 1.5f, 2f, 4f)) {
                val layout = PageLayout(letter, PageLayout.contentWidthPx(viewport, zoom), gapPx = 16)
                val target = layout.scrollTargetFor(186, 0.5f)
                assertEquals(186, target.index)
                assertEquals(0.5f, layout.fractionAt(target.index, target.offsetPx), 0.002f)
            }
        }
    }

    @Test
    fun `phone position restores to the same spot on a tablet`() {
        val phone = PageLayout(letter, 1080, gapPx = 16)
        val tablet = PageLayout(letter, 2560, gapPx = 16)
        val fraction = phone.fractionAt(186, 700)
        val onTablet = tablet.scrollTargetFor(186, fraction)
        assertEquals(700.0 / phone.pageHeightsPx[186], onTablet.offsetPx.toDouble() / tablet.pageHeightsPx[186], 0.002)
    }

    @Test
    fun `out of range targets are clamped`() {
        val layout = PageLayout(letter, 1080, gapPx = 16)
        assertEquals(PAGES - 1, layout.scrollTargetFor(10_000, 0.5f).index)
        assertEquals(0, layout.scrollTargetFor(-3, 2f).index)
        assertEquals(1f, layout.fractionAt(5, 99_999), 0f) // inside the gap below the page
    }

    @Test
    fun `mixed page sizes use each page's own height`() {
        val pages = listOf(PageSize(612, 792), PageSize(792, 612), PageSize(612, 1584))
        val layout = PageLayout(pages, 1000, gapPx = 0)
        assertEquals(1294, layout.pageHeightsPx[0])
        assertEquals(772, layout.pageHeightsPx[1])
        assertEquals(1294, layout.scrollTargetFor(2, 0.5f).offsetPx)
    }
}
