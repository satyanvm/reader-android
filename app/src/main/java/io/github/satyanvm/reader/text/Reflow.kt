package io.github.satyanvm.reader.text

import kotlin.math.abs

/**
 * Turns a page's visual lines into flowing blocks (paragraphs and headings).
 *
 * PDFs only record where glyphs sit, so structure is inferred from geometry, the way a typesetter
 * would read it:
 *  - a new paragraph starts at an indented line, after extra vertical space, or after a short
 *    line that ends a sentence (the ragged last line of a justified paragraph);
 *  - noticeably larger type is a heading;
 *  - a word hyphenated at a line end is rejoined.
 */
object Reflow {
    private val sentenceEnd = Regex("""[.!?:;'"”’)\]]$""")
    private val pageNumber = Regex("""^(\d{1,4}|[ivxlcdm]{1,7}|page \d+( of \d+)?|- ?\d{1,4} ?-)$""", RegexOption.IGNORE_CASE)

    fun blocks(input: List<Line>): List<Block> {
        val lines = input
            .map { it.copy(spans = it.spans.normalizeSpaces()) }
            .filter { it.text.isNotBlank() }
            .let(::dropPageNumbers)
        if (lines.isEmpty()) return emptyList()

        val body = median(lines.map { it.fontSize })
        val bodyLines = lines.filter { !isLarger(it.fontSize, body) }.ifEmpty { lines }
        val left = percentile(bodyLines.map { it.x }, 0.1)
        val right = percentile(bodyLines.map { it.endX }, 0.75)
        val gaps = bodyLines.zipWithNext { a, b -> b.y - a.y }.filter { it > 0 }
        val lineGap = if (gaps.isEmpty()) body * 1.2f else median(gaps)
        val indentMin = body * 0.8f

        val blocks = mutableListOf<Block>()
        var current = mutableListOf<Line>()
        var currentIndented = false

        fun flush() {
            if (current.isEmpty()) return
            val heading = current.all { isLarger(it.fontSize, body) } ||
                (current.size == 1 && current[0].spans.all { it.bold } && current[0].text.length < 80 &&
                    current[0].x - left > indentMin) // short, bold and centred
            blocks += Block(
                kind = if (heading) Block.Kind.HEADING else Block.Kind.PARAGRAPH,
                spans = join(current),
                indented = currentIndented && !heading,
            )
            current = mutableListOf()
        }

        for ((i, line) in lines.withIndex()) {
            val prev = lines.getOrNull(i - 1)
            val indented = line.x - left > indentMin && line.x - left < (right - left) * 0.5f
            val startsNew = prev == null ||
                isLarger(line.fontSize, body) != isLarger(prev.fontSize, body) ||
                line.y - prev.y > lineGap * 1.6f ||
                (indented && abs(line.x - prev.x) > indentMin / 2) ||
                (prev.endX < right - body * 3 && sentenceEnd.containsMatchIn(prev.text.trimEnd()))
            if (startsNew) {
                flush()
                currentIndented = indented
            }
            current += line
        }
        flush()
        return blocks
    }

    /** Joins lines into one run of spans, undoing end-of-line hyphenation. */
    fun join(lines: List<Line>): List<Span> {
        val out = mutableListOf<Span>()
        for (line in lines) {
            val spans = line.spans
            if (out.isNotEmpty()) {
                val last = out.last()
                val lastText = last.text.trimEnd()
                val next = spans.firstOrNull()?.text?.trimStart().orEmpty()
                if (lastText.length >= 2 && lastText.last() == '-' && lastText[lastText.length - 2].isLetter() &&
                    next.firstOrNull()?.isLowerCase() == true
                ) {
                    out[out.size - 1] = last.copy(text = lastText.dropLast(1))
                } else {
                    out[out.size - 1] = last.copy(text = lastText + " ")
                }
            }
            out += spans.mapIndexed { i, s -> if (i == 0) s.copy(text = s.text.trimStart()) else s }
        }
        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(text = out.last().text.trimEnd())
        return out.compact()
    }

    /** Can [next] (first block of a page) continue [prev] (last block of the page before)? */
    fun continues(prev: Block?, next: Block?, nextPageUsesIndents: Boolean): Boolean {
        if (prev == null || next == null) return false
        if (prev.kind != Block.Kind.PARAGRAPH || next.kind != Block.Kind.PARAGRAPH) return false
        if (nextPageUsesIndents) return !next.indented
        val endsSentence = sentenceEnd.containsMatchIn(prev.text.trimEnd())
        val startsUpper = next.text.trimStart().firstOrNull()?.isUpperCase() == true
        return !(endsSentence && startsUpper)
    }

    /** [prev] with [next]'s text appended, as one paragraph. */
    fun merge(prev: Block, next: Block): Block {
        val a = Line(prev.spans, 0f, 0f, 0f, 1f)
        val b = Line(next.spans, 0f, 0f, 0f, 1f)
        return prev.copy(spans = join(listOf(a, b)))
    }

    private fun dropPageNumbers(lines: List<Line>): List<Line> =
        lines.dropWhile { pageNumber.matches(it.text.trim()) }
            .dropLastWhile { pageNumber.matches(it.text.trim()) }

    private fun isLarger(size: Float, body: Float) = size > body * 1.15f

    private fun List<Span>.normalizeSpaces() =
        map { it.copy(text = it.text.replace(Regex("""[ \t ]+"""), " ")) }

    private fun median(values: List<Float>): Float = percentile(values, 0.5)

    private fun percentile(values: List<Float>, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
