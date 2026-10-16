package io.github.satyanvm.reader.text

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Flowing-text view of a PDF, built page by page and cached on disk, so each page is processed
 * once per device:
 *
 *   PDF text lines (or OCR of the page image, if the page has no text)
 *     → running headers/footers removed (compared with nearby pages)
 *     → reflowed into paragraphs and headings with italics/bold
 *     → paragraphs split by a page break rejoined
 *
 * Text is kept per source page, so a position ("page 186, 40% down") means the same place here
 * as in the original-pages view.
 */
class TextDocument(
    private val pdf: File,
    private val cacheDir: File,
    val pageCount: Int,
    /** Renders a page image for OCR. */
    private val renderPage: suspend (index: Int, widthPx: Int) -> Bitmap,
    private val ocr: Ocr?,
) : Closeable {
    /** Pages finished so far, observable from Compose. Only written on the caller's (main) thread. */
    val loaded = mutableStateMapOf<Int, PageText>()

    private class RawPage(val lines: List<Line>, val recognized: Boolean)

    private val work = Mutex()
    private val raw = object : LinkedHashMap<Int, RawPage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RawPage>?) = size > 24
    }
    private val reflowed = object : LinkedHashMap<Int, List<Block>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Block>>?) = size > 24
    }
    private val extractor = PdfLineExtractor()
    private var document: PDDocument? = null
    private var closed = false
    private var prefetch: Job? = null

    init {
        cacheDir.mkdirs()
    }

    /** Flowing text for page [index], building it if needed. Call from the main thread. */
    suspend fun page(index: Int): PageText {
        loaded[index]?.let { return it }
        val text = work.withLock {
            withContext(Dispatchers.IO) { cached(index) } ?: build(index)
        }
        loaded[index] = text
        return text
    }

    /**
     * Builds pages in the background, starting at [from] and working forward then backward, so
     * the pages near the reader are ready first.
     */
    fun prefetch(scope: CoroutineScope, from: Int) {
        prefetch?.cancel()
        val start = from.coerceIn(0, maxOf(0, pageCount - 1))
        val order = (start until pageCount) + (start - 1 downTo 0)
        prefetch = scope.launch {
            for (i in order) {
                if (!isActive) break
                if (i in loaded) continue
                try {
                    page(i)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Couldn't get text: show the page as its image rather than leave a hole.
                    loaded[i] = PageText(emptyList())
                }
            }
        }
    }

    private suspend fun build(index: Int): PageText {
        val prev = if (index > 0) reflow(index - 1) else null
        val current = reflow(index)
        val next = if (index < pageCount - 1) reflow(index + 1) else null
        val text = PageText(PageAssembly.assemble(prev, current, next), recognized = rawPage(index).recognized)
        // Best effort: if the cache can't be written the page is just rebuilt next time.
        withContext(Dispatchers.IO) {
            runCatching {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$index.tmp")
                tmp.writeText(text.toJson())
                tmp.renameTo(cacheFile(index))
            }
        }
        return text
    }

    private suspend fun reflow(index: Int): List<Block> {
        reflowed[index]?.let { return it }
        val neighbours = ((index - PageAssembly.WINDOW)..(index + PageAssembly.WINDOW))
            .filter { it != index && it in 0 until pageCount }
            .map { rawPage(it).lines }
        val blocks = Reflow.blocks(PageAssembly.stripRunningLines(rawPage(index).lines, neighbours))
        reflowed[index] = blocks
        return blocks
    }

    private suspend fun rawPage(index: Int): RawPage {
        raw[index]?.let { return it }
        val lines = withContext(extractDispatcher) {
            check(!closed) { "closed" }
            val doc = document ?: PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).also { document = it }
            extractor.lines(doc, index)
        }
        val reader = ocr
        val page = if (lines.isNotEmpty() || reader == null) {
            RawPage(lines, recognized = false)
        } else {
            val recognized = runCatching { reader.lines(renderPage(index, OCR_WIDTH_PX)) }.getOrDefault(emptyList())
            RawPage(recognized, recognized = recognized.isNotEmpty())
        }
        raw[index] = page
        return page
    }

    private fun cacheFile(index: Int) = File(cacheDir, "$index.json")

    private fun cached(index: Int): PageText? {
        val f = cacheFile(index)
        if (!f.exists()) return null
        return runCatching { PageText.fromJson(f.readText()) }.getOrNull()
    }

    override fun close() {
        prefetch?.cancel()
        CoroutineScope(extractDispatcher).launch {
            closed = true
            document?.close()
            document = null
        }
    }

    companion object {
        /** Wide enough that body text is ~30px tall, which OCR reads reliably. */
        private const val OCR_WIDTH_PX = 1600

        /** PdfBox isn't thread safe; one extraction at a time. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val extractDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Bump when the pipeline's output changes, so old cached text is rebuilt. */
        const val CACHE_VERSION = 2

        fun cacheDirFor(filesDir: File, documentId: String) =
            File(filesDir, "text-v$CACHE_VERSION/$documentId")
    }
}
