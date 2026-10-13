package io.github.satyanvm.reader

import io.github.satyanvm.reader.text.Block
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.EpubPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun epub(files: Map<String, String>): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            for ((path, body) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
            }
        }
        return f
    }

    private val book = mapOf(
        "META-INF/container.xml" to """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent(),
        "OEBPS/content.opf" to """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Meditations</dc:title></metadata>
              <manifest>
                <item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/two%20b.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
                <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="cover" linear="no"/>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent(),
        "OEBPS/text/cover.xhtml" to "<html><body><p>Cover</p></body></html>",
        "OEBPS/text/one.xhtml" to """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head>
            <body>
              <h1>Book   One</h1>
              <p>You have two reasons, therefore, to be
                 contented with your condition. <em>First</em>, because it has befallen you.</p>
              <p><i>Secondly</i>, the lot even of <b>individuals</b> is destined&#160;for the whole.</p>
              <div><img src="../images/fig%201.png" alt=""/></div>
            </body></html>
        """.trimIndent(),
        "OEBPS/text/two b.xhtml" to "<html><body><p>Line one<br/>Line two</p><p>  </p></body></html>",
        "OEBPS/images/fig 1.png" to "PNG",
    )

    @Test
    fun `reads chapters in spine order with headings, italics and images`() {
        EpubBook.open(epub(book)).use { b ->
            assertEquals("The Meditations", b.title)
            assertEquals("non-linear cover is skipped", 2, b.pageCount)

            val one = b.chapters[0]
            assertEquals(Block.Kind.HEADING, one[0].kind)
            assertEquals("Book One", one[0].text)
            assertEquals(
                "You have two reasons, therefore, to be contented with your condition. First, because it has befallen you.",
                one[1].text,
            )
            assertEquals(listOf(false, true, false), one[1].spans.map { it.italic })
            assertTrue(one[2].spans.first().italic)
            assertTrue(one[2].spans.single { it.text == "individuals" }.bold)
            assertEquals(Block.Kind.IMAGE, one[3].kind)
            assertEquals("OEBPS/images/fig 1.png", one[3].image)
            assertEquals("PNG", b.imageBytes(one[3].image!!)!!.decodeToString())

            assertEquals(listOf("Line one\nLine two"), b.chapters[1].map { it.text })
        }
    }

    @Test
    fun `detects EPUBs`() {
        assertTrue(EpubBook.isEpub(epub(book)))
        assertFalse(EpubBook.isEpub(tmp.newFile("x.pdf").apply { writeText("%PDF-1.7") }))
    }

    @Test
    fun `relative paths resolve inside the zip`() {
        assertEquals("OEBPS/images/a.png", EpubBook.resolve("OEBPS/text/one.xhtml", "../images/a.png"))
        assertEquals("OEBPS/text/two.xhtml", EpubBook.resolve("OEBPS/content.opf", "text/two.xhtml#c2"))
        assertEquals("a b.png", EpubBook.resolve("x.xhtml", "a%20b.png"))
        assertEquals("root.png", EpubBook.resolve("OEBPS/x.xhtml", "/root.png"))
    }

    @Test
    fun `positions round-trip through the flat block list`() {
        val p = EpubPositions(listOf(4, 10, 2))
        assertEquals(16, p.totalBlocks)
        assertEquals(4 + 5 to 0f, p.toFlat(1, 0.5f))
        val (flat, within) = p.toFlat(1, 0.53f)
        val (chapter, fraction) = p.fromFlat(flat, within)
        assertEquals(1, chapter)
        assertEquals(0.53f, fraction, 0.0001f)
        assertEquals(2 to 1f, p.fromFlat(15, 1f))
        assertEquals(0 to 0f, p.fromFlat(0, 0f))
        assertEquals("fraction 1 lands in the last block", 3, p.toFlat(0, 1f).first)
    }
}
