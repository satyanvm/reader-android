package io.github.satyanvm.reader

import io.github.satyanvm.reader.text.Block
import io.github.satyanvm.reader.text.Line
import io.github.satyanvm.reader.text.PageAssembly
import io.github.satyanvm.reader.text.Reflow
import io.github.satyanvm.reader.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds a page the way a typeset book looks: 12pt body, left margin 72, right margin 540. */
class PageBuilder {
    val lines = mutableListOf<Line>()
    private var y = 72f

    fun line(text: String, indent: Boolean = false, full: Boolean = true, size: Float = 12f, gap: Float = 0f,
             italic: Boolean = false, bold: Boolean = false, x: Float? = null) = apply {
        y += 15f * (size / 12f) + gap
        val startX = x ?: if (indent) 90f else 72f
        lines += Line(listOf(Span(text, bold, italic)), startX, if (full) 540f else startX + text.length * 5.5f, y, size)
    }

    fun spans(vararg spans: Span) = apply {
        y += 15f
        lines += Line(spans.toList(), 72f, 540f, y, 12f)
    }
}

fun page(build: PageBuilder.() -> Unit) = PageBuilder().apply(build).lines

class ReflowTest {
    private fun texts(blocks: List<Block>) = blocks.map { it.text }

    @Test
    fun `indented first lines start paragraphs, wrapped lines join`() {
        val blocks = Reflow.blocks(page {
            line("Be not uneasy, discouraged, or out of humour, because practice falls", indent = true)
            line("short of precept in some particulars. If you happen to be beaten, come")
            line("on again.", full = false)
            line("Things are so much perplexed and in the dark that several great", indent = true)
            line("philosophers have thought it impossible to arrive at certainty.", full = false)
        })
        assertEquals(
            listOf(
                "Be not uneasy, discouraged, or out of humour, because practice falls short of precept " +
                    "in some particulars. If you happen to be beaten, come on again.",
                "Things are so much perplexed and in the dark that several great philosophers have " +
                    "thought it impossible to arrive at certainty.",
            ),
            texts(blocks),
        )
        assertTrue(blocks.all { it.indented })
    }

    @Test
    fun `a full-width line ending in a full stop does not end the paragraph`() {
        val blocks = Reflow.blocks(page {
            line("Depend upon it, this had never been sent you, if the universe had not.", indent = true)
            line("Neither does nature act at random, or order anything which is not")
            line("suitable.", full = false)
        })
        assertEquals(1, blocks.size)
    }

    @Test
    fun `extra vertical space separates unindented paragraphs`() {
        val blocks = Reflow.blocks(page {
            line("First block paragraph that wraps onto")
            line("a second line.", full = false)
            line("Second block paragraph.", gap = 12f, full = false)
        })
        assertEquals(listOf("First block paragraph that wraps onto a second line.", "Second block paragraph."), texts(blocks))
    }

    @Test
    fun `short line ending a sentence ends the paragraph even without an indent`() {
        val blocks = Reflow.blocks(page {
            line("A paragraph whose last line is short and ends a sentence, as a ragged")
            line("last line does.", full = false)
            line("The next paragraph starts here without indentation or extra spacing")
            line("and goes on.", full = false)
        })
        assertEquals(2, blocks.size)
    }

    @Test
    fun `larger type is a heading`() {
        val blocks = Reflow.blocks(page {
            line("BOOK THE SECOND", size = 18f, full = false, x = 200f)
            line("Begin the morning by saying to thyself, I shall meet with the busy-", indent = true)
            line("body, the ungrateful, arrogant, deceitful, envious, unsocial.", full = false)
        })
        assertEquals(Block.Kind.HEADING, blocks[0].kind)
        assertEquals("BOOK THE SECOND", blocks[0].text)
        assertEquals(Block.Kind.PARAGRAPH, blocks[1].kind)
        // "busy-" + "body": the next line starts lowercase, so the hyphen is a line-break hyphen.
        assertTrue(blocks[1].text.contains("busybody"))
    }

    @Test
    fun `hyphens are kept before a capital or inside a line`() {
        val blocks = Reflow.blocks(page {
            line("a well-known, long-")
            line("Term plan", full = false)
        })
        assertEquals("a well-known, long- Term plan", blocks.single().text)
    }

    @Test
    fun `italics survive and same-style runs merge`() {
        val blocks = Reflow.blocks(page {
            spans(Span("contented with your condition. "), Span("First", italic = true), Span(", because it has"))
            spans(Span("befallen you. "), Span("Secondly", italic = true), Span(", the lot."))
        })
        val spans = blocks.single().spans
        assertEquals(listOf(false, true, false, true, false), spans.map { it.italic })
        assertEquals("First", spans[1].text)
        assertEquals(", because it has befallen you. ", spans[2].text)
    }

    @Test
    fun `page numbers at the top or bottom are dropped`() {
        val blocks = Reflow.blocks(page {
            line("72", full = false, x = 300f)
            line("The text of the page.", full = false)
            line("xiv", full = false, x = 300f)
        })
        assertEquals(listOf("The text of the page."), texts(blocks))
        assertEquals(emptyList<Block>(), Reflow.blocks(page { line("12", full = false) }))
    }
}

class PageAssemblyTest {
    private fun runningPage(n: Int, body: String) = page {
        line(if (n % 2 == 0) "$n THE MEDITATIONS" else "BOOK II $n", full = false)
        line(body, indent = true, full = false)
        line("$n", full = false, x = 300f)
    }

    @Test
    fun `running headers repeated on nearby pages are removed`() {
        val pages = (70..74).map { runningPage(it, "Body text of page $it.") }
        val stripped = PageAssembly.stripRunningLines(pages[2], pages.filterIndexed { i, _ -> i != 2 })
        val blocks = Reflow.blocks(stripped)
        assertEquals(listOf("Body text of page 72."), blocks.map { it.text })
    }

    @Test
    fun `a unique first line is kept`() {
        val a = page { line("Chapter One", full = false); line("Text.", full = false) }
        val b = page { line("Something else entirely", full = false); line("More.", full = false) }
        assertEquals(2, PageAssembly.stripRunningLines(a, listOf(b)).size)
    }

    private fun para(text: String, indented: Boolean = true) =
        Block(Block.Kind.PARAGRAPH, listOf(Span(text)), indented = indented)

    @Test
    fun `a paragraph split by a page break is moved whole onto the earlier page`() {
        val p1 = listOf(para("Start."), para("Now, if you are displeased with your circumstances, you"))
        val p2 = listOf(para("injure the whole of which you are a part.", indented = false), para("Next one."))

        val first = PageAssembly.assemble(null, p1, p2)
        assertEquals(
            "Now, if you are displeased with your circumstances, you injure the whole of which you are a part.",
            first.last().text,
        )
        val second = PageAssembly.assemble(p1, p2, null)
        assertEquals(listOf("Next one."), second.map { it.text })
    }

    @Test
    fun `hyphenated words split across pages are rejoined`() {
        val p1 = listOf(para("A"), para("the universe had not found its advan-"))
        val p2 = listOf(para("tage in it.", indented = false), para("B"))
        assertEquals("the universe had not found its advantage in it.", PageAssembly.assemble(null, p1, p2).last().text)
    }

    @Test
    fun `a page that is all one paragraph keeps its text and is marked as a continuation`() {
        val p1 = listOf(para("A"), para("it goes on and"))
        val p2 = listOf(para("on for the whole page without stopping", indented = false))
        assertEquals("it goes on and", PageAssembly.assemble(null, p1, p2).last().text)
        val second = PageAssembly.assemble(p1, p2, null)
        assertTrue(second.single().continuation)
    }

    @Test
    fun `an indented paragraph at the top of a page is a new paragraph`() {
        val p1 = listOf(para("A"), para("ends a sentence."))
        val p2 = listOf(para("New paragraph."), para("B"))
        assertEquals("ends a sentence.", PageAssembly.assemble(null, p1, p2).last().text)
        assertEquals(2, PageAssembly.assemble(p1, p2, null).size)
    }

    @Test
    fun `in books without indents, a new sentence after a full stop is a new paragraph`() {
        val p1 = listOf(para("A", false), para("ends a sentence.", false))
        val p2 = listOf(para("Starts fresh.", false), para("B", false))
        assertFalse(Reflow.continues(p1.last(), p2.first(), nextPageUsesIndents = false))
        val p3 = listOf(para("but this continues.", false), para("C", false))
        assertTrue(Reflow.continues(p1.last(), p3.first(), nextPageUsesIndents = false))
    }
}
