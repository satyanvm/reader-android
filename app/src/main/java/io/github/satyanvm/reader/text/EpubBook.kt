package io.github.satyanvm.reader.text

import io.github.satyanvm.reader.reader.ReaderDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/**
 * An EPUB, read into flowing blocks. EPUBs already carry structure (paragraphs, headings,
 * italics, images), so unlike a PDF nothing has to be inferred.
 *
 * Positions use chapter = page: `pageIndex` is the spine item and `pageOffset` is how far
 * through that chapter's blocks the reader is (see [EpubPositions]).
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val title: String?,
    val chapters: List<List<Block>>,
) : ReaderDocument {
    override val pageCount: Int get() = chapters.size

    val positions = EpubPositions(chapters.map { it.size })

    /** Raw bytes of an image referenced by a [Block.Kind.IMAGE] block, or null if missing. */
    fun imageBytes(path: String): ByteArray? = synchronized(zip) {
        zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    }

    override fun close() = synchronized(zip) { zip.close() }

    companion object {
        fun isEpub(file: File): Boolean = runCatching {
            ZipFile(file).use { z ->
                val m = z.getEntry("mimetype") ?: return@use z.getEntry("META-INF/container.xml") != null
                z.getInputStream(m).use { it.readBytes().decodeToString().trim() } == "application/epub+zip"
            }
        }.getOrDefault(false)

        fun open(file: File): EpubBook {
            val zip = ZipFile(file)
            try {
                val container = zip.text("META-INF/container.xml") ?: error("Not an EPUB: no container.xml")
                val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
                    .selectFirst("rootfile")?.attr("full-path") ?: error("Not an EPUB: no rootfile")
                val opf = Jsoup.parse(zip.text(opfPath) ?: error("Missing $opfPath"), "", Parser.xmlParser())

                val manifest = opf.select("manifest > item").associate { it.attr("id") to it }
                val title = opf.selectFirst("metadata > dc|title, metadata > title")?.text()?.takeIf { it.isNotBlank() }
                val chapters = opf.select("spine > itemref")
                    .filter { it.attr("linear") != "no" }
                    .mapNotNull { manifest[it.attr("idref")] }
                    .filter { it.attr("media-type").contains("html") }
                    .map { resolve(opfPath, it.attr("href")) }
                    .mapNotNull { path -> zip.text(path)?.let { html -> XhtmlBlocks.parse(html, path) } }
                    .filter { it.isNotEmpty() }
                if (chapters.isEmpty()) error("This EPUB has no readable text")
                return EpubBook(zip, title, chapters)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private fun ZipFile.text(path: String): String? =
            getEntry(path)?.let { e -> getInputStream(e).use { it.readBytes().decodeToString() } }

        /** Resolves [href] relative to the file at [base], both paths inside the zip. */
        fun resolve(base: String, href: String): String {
            val raw = href.substringBefore('#')
            val clean = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
            if (clean.isEmpty()) return base
            val parts = if (clean.startsWith("/")) mutableListOf()
            else base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            for (segment in clean.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += segment
                }
            }
            return parts.joinToString("/")
        }
    }
}

/** Converts an XHTML chapter into blocks. */
object XhtmlBlocks {
    private val blockTags = setOf(
        "p", "div", "section", "article", "blockquote", "li", "ul", "ol", "dd", "dt", "dl",
        "pre", "figure", "figcaption", "table", "tr", "td", "th", "header", "footer", "aside",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "body", "nav",
    )
    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val italicTags = setOf("em", "i", "cite", "var", "dfn")
    private val boldTags = setOf("strong", "b")
    private val skipTags = setOf("script", "style", "head", "title")

    fun parse(html: String, path: String): List<Block> {
        val body = Jsoup.parse(html).body()
        val out = mutableListOf<Block>()
        val walker = Walker(path, out)
        walker.walk(body, bold = false, italic = false, pre = false)
        walker.flush()
        return out
    }

    private class Walker(private val path: String, private val out: MutableList<Block>) {
        private val spans = mutableListOf<Span>()
        private var heading = false

        fun walk(node: Node, bold: Boolean, italic: Boolean, pre: Boolean) {
            when (node) {
                is TextNode -> {
                    val text = if (pre) node.wholeText else node.wholeText.replace(Regex("""\s+"""), " ")
                    if (text.isNotEmpty()) spans += Span(text, bold, italic)
                }
                is Element -> element(node, bold, italic, pre)
            }
        }

        private fun element(el: Element, bold: Boolean, italic: Boolean, pre: Boolean) {
            val tag = el.normalName()
            if (tag in skipTags) return
            when (tag) {
                "br" -> {
                    spans += Span("\n", bold, italic)
                    return
                }
                "img", "image" -> {
                    val src = el.attr("src").ifEmpty { el.attr("xlink:href").ifEmpty { el.attr("href") } }
                    if (src.isNotEmpty() && !src.startsWith("data:")) {
                        flush()
                        out += Block(Block.Kind.IMAGE, image = EpubBook.resolve(path, src))
                    }
                    return
                }
            }
            val isBlock = tag in blockTags
            if (isBlock) flush()
            if (tag in headingTags) heading = true
            val b = bold || tag in boldTags
            val i = italic || tag in italicTags
            for (child in el.childNodes()) walk(child, b, i, pre || tag == "pre")
            if (isBlock) flush()
        }

        fun flush() {
            val compact = spans.compact()
            spans.clear()
            val wasHeading = heading
            heading = false
            if (compact.isEmpty()) return
            // Trim the block's outer whitespace and the space around line breaks.
            val trimmed = compact.mapIndexed { i, s ->
                var t = s.text.replace(Regex(""" ?\n ?"""), "\n")
                if (i == 0) t = t.trimStart()
                if (i == compact.lastIndex) t = t.trimEnd()
                s.copy(text = t)
            }.compact()
            if (trimmed.isEmpty() || trimmed.all { it.text.isBlank() }) return
            out += Block(if (wasHeading) Block.Kind.HEADING else Block.Kind.PARAGRAPH, trimmed)
        }
    }
}

/**
 * Maps an EPUB position (chapter, fraction through the chapter) to a block in the flat list
 * the reader shows, and back. Fractions are counted in blocks, so they don't depend on screen
 * size, text size or theme.
 */
class EpubPositions(private val blockCounts: List<Int>) {
    private val starts = IntArray(blockCounts.size).also {
        var acc = 0
        for (i in blockCounts.indices) {
            it[i] = acc
            acc += blockCounts[i]
        }
    }
    val totalBlocks: Int = blockCounts.sum()

    fun chapterStart(chapter: Int): Int = starts[chapter.coerceIn(0, starts.size - 1)]

    /** (flat block index, fraction within that block) for a saved position. */
    fun toFlat(chapter: Int, fraction: Float): Pair<Int, Float> {
        val c = chapter.coerceIn(0, blockCounts.size - 1)
        val n = blockCounts[c]
        val x = fraction.coerceIn(0f, 1f) * n
        val block = x.toInt().coerceAtMost(n - 1)
        return (starts[c] + block) to (x - block).coerceIn(0f, 1f)
    }

    /** (chapter, fraction through it) for the block at [flatIndex], [withinBlock] of the way down. */
    fun fromFlat(flatIndex: Int, withinBlock: Float): Pair<Int, Float> {
        val i = flatIndex.coerceIn(0, maxOf(0, totalBlocks - 1))
        var c = starts.indexOfLast { it <= i }.coerceAtLeast(0)
        while (c > 0 && blockCounts[c] == 0) c--
        val n = blockCounts[c].coerceAtLeast(1)
        return c to ((i - starts[c] + withinBlock.coerceIn(0f, 1f)) / n).coerceIn(0f, 1f)
    }
}
