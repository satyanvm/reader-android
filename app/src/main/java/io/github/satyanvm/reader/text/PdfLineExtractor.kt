package io.github.satyanvm.reader.text

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

/**
 * Pulls positioned, styled lines out of a PDF page. PDFTextStripper already does the hard part
 * (ordering glyphs into words and lines); this captures the geometry and fonts it normally
 * throws away.
 */
class PdfLineExtractor {
    fun lines(doc: PDDocument, pageIndex: Int): List<Line> {
        val stripper = Collector()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.writeText(doc, StringWriter())
        stripper.endLine()
        return stripper.lines
    }

    private class Collector : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        private val spans = mutableListOf<Span>()
        private var x = Float.NaN
        private var endX = 0f
        private var y = 0f
        private var sizeSum = 0f
        private var sizeCount = 0

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (textPositions.isEmpty()) return
            // Split the run wherever the font changes, so mixed italics within a word survive.
            var start = 0
            for (i in 1..textPositions.size) {
                if (i == textPositions.size || styleOf(textPositions[i].font) != styleOf(textPositions[start].font)) {
                    val run = textPositions.subList(start, i)
                    val (bold, italic) = styleOf(run.first().font)
                    spans += Span(run.joinToString("") { it.unicode ?: "" }, bold, italic)
                    start = i
                }
            }
            val first = textPositions.first()
            val last = textPositions.last()
            if (x.isNaN()) {
                x = first.xDirAdj
                y = first.yDirAdj
            }
            endX = maxOf(endX, last.xDirAdj + last.widthDirAdj)
            for (p in textPositions) {
                sizeSum += p.fontSizeInPt.takeIf { it > 0 } ?: p.heightDir
                sizeCount++
            }
        }

        override fun writeWordSeparator() {
            if (spans.isNotEmpty()) spans[spans.size - 1] = spans.last().let { it.copy(text = it.text + " ") }
        }

        override fun writeLineSeparator() = endLine()
        override fun writeParagraphStart() = endLine()
        override fun writeParagraphEnd() = endLine()
        override fun writePageStart() = Unit
        override fun writePageEnd() = endLine()

        fun endLine() {
            if (spans.isNotEmpty() && !x.isNaN()) {
                lines += Line(
                    spans = spans.toList().compact(),
                    x = x,
                    endX = endX,
                    y = y,
                    fontSize = if (sizeCount > 0) sizeSum / sizeCount else 10f,
                )
            }
            spans.clear()
            x = Float.NaN
            endX = 0f
            sizeSum = 0f
            sizeCount = 0
        }
    }

    companion object {
        /** (bold, italic) from the font's descriptor and name ("ABCDEF+Garamond-BoldItalic"). */
        fun styleOf(font: PDFont?): Pair<Boolean, Boolean> {
            if (font == null) return false to false
            val name = font.name.orEmpty().substringAfter('+').lowercase()
            val d = font.fontDescriptor
            val bold = (d != null && (d.isForceBold || d.fontWeight >= 600f)) ||
                "bold" in name || "black" in name || "heavy" in name || "semibold" in name
            val italic = (d != null && (d.isItalic || d.italicAngle != 0f)) ||
                "italic" in name || "oblique" in name
            return bold to italic
        }
    }
}
