package io.github.satyanvm.reader.text

/**
 * Cross-page cleanup, which needs to see neighbouring pages:
 *  - running headers and footers (book title, chapter name, "Page 72") repeat on nearby pages
 *    and are dropped;
 *  - a paragraph split by a page break is rejoined onto the earlier page.
 */
object PageAssembly {
    /** How many pages either side are compared when looking for running headers/footers. */
    const val WINDOW = 2

    /** Removes a first/last line that repeats (ignoring digits) at the same edge of a nearby page. */
    fun stripRunningLines(page: List<Line>, neighbours: List<List<Line>>): List<Line> {
        val lines = page.filter { it.text.isNotBlank() }
        if (lines.size < 2) return lines
        val tops = neighbours.mapNotNull { n -> n.firstOrNull { it.text.isNotBlank() }?.let(::key) }.toSet()
        val bottoms = neighbours.mapNotNull { n -> n.lastOrNull { it.text.isNotBlank() }?.let(::key) }.toSet()
        var out = lines
        if (isRunning(out.first(), tops)) out = out.drop(1)
        if (out.size > 1 && isRunning(out.last(), bottoms)) out = out.dropLast(1)
        return out
    }

    /**
     * Final blocks for a page given the reflowed blocks of the page before, this one and the one
     * after. A paragraph that continues across a break is moved whole onto the earlier page,
     * so both pages agree on who owns it no matter which is assembled first. Pages that would be
     * left empty (a page that is one long paragraph) keep their text and are marked as
     * continuations instead.
     */
    fun assemble(prev: List<Block>?, page: List<Block>, next: List<Block>?): List<Block> {
        if (page.isEmpty()) return page
        val out = page.toMutableList()
        val fromPrev = prev != null && page.size >= 2 &&
            Reflow.continues(prev.lastOrNull(), page.first(), usesIndents(page))
        if (fromPrev) {
            out.removeAt(0)
        } else if (prev != null && Reflow.continues(prev.lastOrNull(), page.first(), usesIndents(page))) {
            out[0] = out[0].copy(continuation = true)
        }
        if (next != null && next.size >= 2 && Reflow.continues(page.last(), next.first(), usesIndents(next))) {
            out[out.size - 1] = Reflow.merge(out.last(), next.first())
        }
        return out
    }

    private fun usesIndents(blocks: List<Block>) = blocks.any { it.indented }

    private fun key(line: Line) = line.text.lowercase().replace(Regex("""\d+"""), "#").replace(Regex("""\s+"""), " ").trim()

    private fun isRunning(line: Line, edges: Set<String>) =
        line.text.length <= 100 && key(line).let { it.isNotEmpty() && it in edges }
}
