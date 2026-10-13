package io.github.satyanvm.reader

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.github.satyanvm.reader.data.db.AppDatabase
import io.github.satyanvm.reader.text.Line
import io.github.satyanvm.reader.text.Ocr
import io.github.satyanvm.reader.text.Span
import io.github.satyanvm.reader.text.TextDocument
import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TextExtractionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** One line of text at a position, in a font. */
    private data class Text(val text: String, val x: Float, val y: Float, val italic: Boolean = false)

    /** Builds a PDF; each page is a list of positioned lines (an empty page has no text). */
    private fun pdf(vararg pages: List<Text>): File {
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "sample.pdf")
        PDDocument().use { doc ->
            for (lines in pages) {
                val page = PDPage()
                doc.addPage(page)
                if (lines.isEmpty()) continue
                PDPageContentStream(doc, page).use { cs ->
                    for (l in lines) {
                        cs.beginText()
                        cs.setFont(if (l.italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN, 12f)
                        cs.newLineAtOffset(l.x, 792f - l.y)
                        cs.showText(l.text)
                        cs.endText()
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    /** A typeset page: running header, indented paragraphs, page number at the foot. */
    private fun bookPage(n: Int, vararg paragraphs: List<String>): List<Text> {
        val out = mutableListOf(Text("$n THE MEDITATIONS", 200f, 60f))
        var y = 90f
        for (p in paragraphs) {
            p.forEachIndexed { i, line ->
                out += Text(line, if (i == 0) 90f else 72f, y)
                y += 15f
            }
        }
        out += Text("$n", 300f, 740f)
        return out
    }

    private fun textDocument(file: File, pages: Int, ocr: Ocr? = null) = TextDocument(
        pdf = file,
        cacheDir = File(context.filesDir, "text-test"),
        pageCount = pages,
        renderPage = { _, w -> Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888) },
        ocr = ocr,
    )

    @Test
    fun `builds reflowed pages without running headers and caches them`() = runBlocking {
        File(context.filesDir, "text-test").deleteRecursively()
        val para1 = listOf(
            "Be not uneasy, discouraged, or out of humour, because practice falls short",
            "of precept in some particulars. If you happen to be beaten, come on again,",
            "and be glad.",
        )
        val para2 = listOf(
            "Things are so much perplexed and in the dark that several great philosophers",
            "have thought it impossible to arrive at any certainty, and that which you",
        )
        val para2End = listOf(
            "call your circumstances is no more than what was appointed you from the",
            "beginning by the highest and most ancient causes.",
        )
        val file = pdf(
            bookPage(70, para1, para2),
            bookPage(71).toMutableList().apply {
                // Continues para2 unindented, then a new paragraph.
                addAll(para2End.mapIndexed { i, l -> Text(l, 72f, 90f + 15f * i) })
                add(Text("Remember this.", 90f, 135f))
            },
            bookPage(72, listOf("Short final page.")),
        )
        val text = textDocument(file, pages = 3)

        val first = text.page(0)
        assertEquals(2, first.blocks.size)
        assertTrue(first.blocks[0].text.startsWith("Be not uneasy"))
        assertTrue(first.blocks[0].text.endsWith("and be glad."))
        assertFalse("running header removed", first.blocks.any { "MEDITATIONS" in it.text })
        assertTrue(
            "paragraph split by the page break is joined",
            first.blocks[1].text.endsWith("that which you call your circumstances is no more than what was " +
                "appointed you from the beginning by the highest and most ancient causes."),
        )
        val second = text.page(1)
        assertEquals(listOf("Remember this."), second.blocks.map { it.text })
        text.close()

        // A fresh instance reads the cache instead of the PDF.
        file.delete()
        assertEquals(first, textDocument(file, pages = 3).page(0))
    }

    @Test
    fun `italic fonts become italic spans`() = runBlocking {
        File(context.filesDir, "text-test").deleteRecursively()
        val file = pdf(
            listOf(
                Text("You have two reasons to be content.", 90f, 90f),
                Text("First", 72f, 105f, italic = true),
                Text("Secondly, the lot.", 72f, 120f),
            )
        )
        val spans = textDocument(file, pages = 1).page(0).blocks.flatMap { it.spans }
        assertTrue(spans.any { it.italic && it.text.trim() == "First" })
        assertTrue(spans.any { !it.italic && "Secondly" in it.text })
    }

    @Test
    fun `pages with no text fall back to OCR`() = runBlocking {
        File(context.filesDir, "text-test").deleteRecursively()
        val file = pdf(emptyList(), emptyList())
        val words = ArrayDeque(listOf("alpha", "beta", "gamma", "delta"))
        val ocr = Ocr { _ ->
            val w = words.removeFirst() // different text on every page, as in a real book
            listOf(
                Line(listOf(Span("Recognized $w text from a scanned")), 100f, 1500f, 200f, 30f),
                Line(listOf(Span("page about $w.")), 100f, 300f, 240f, 30f),
            )
        }
        val page = textDocument(file, pages = 2, ocr).page(0)
        assertTrue(page.recognized)
        val block = page.blocks.single().text
        assertTrue(block, block.startsWith("Recognized ") && block.contains(" text from a scanned page about "))

        File(context.filesDir, "text-test").deleteRecursively()
        val noOcr = textDocument(File(file.path), pages = 2, ocr = Ocr { emptyList() })
        assertTrue("a figure page stays an image", noOcr.page(1).isEmpty)
    }

    @Test
    fun `upgrading from version 1 keeps documents and positions`() {
        migrations.createDatabase("migrate.db", 1).apply {
            execSQL(
                "INSERT INTO documents (id, title, localPath, sizeBytes, pageCount, lastOpenedAt) " +
                    "VALUES ('abc', 'Meditations', '/x.pdf', 10, 224, 1)"
            )
            execSQL(
                "INSERT INTO reading_positions (documentId, pageIndex, pageOffset, horizontalOffset, " +
                    "zoom, pageCount, hlc, deviceId, schemaVersion, pendingSync) " +
                    "VALUES ('abc', 66, 0.4, 0, 1, 224, 5, 'phone', 1, 0)"
            )
            close()
        }
        val db = migrations.runMigrationsAndValidate("migrate.db", 3, true)
        db.query("SELECT readingMode, format FROM documents WHERE id = 'abc'").use {
            it.moveToFirst()
            assertEquals("PAGES", it.getString(0))
            assertEquals("PDF", it.getString(1))
        }
        db.query("SELECT pageIndex FROM reading_positions WHERE documentId = 'abc'").use {
            it.moveToFirst()
            assertEquals(66, it.getInt(0))
        }
    }
}
