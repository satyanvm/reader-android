package io.github.satyanvm.reader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders pages of a local PDF. [PdfRenderer] allows one open page at a time and isn't thread
 * safe, so all access goes through a single-threaded dispatcher.
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val pageSizes: List<PageSize>,
) : PagedDocument {

    private var closed = false // only touched on pdfDispatcher

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Renders [index] at [widthPx] wide (capped to keep bitmaps a sane size at high zoom). */
    override suspend fun render(index: Int, widthPx: Int): Bitmap {
        val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH)
        val key = "$index@$width"
        cache.get(key)?.let { return it }
        return withContext(pdfDispatcher) {
            if (closed) throw CancellationException("document closed")
            cache.get(key) ?: renderer.openPage(index).use { page ->
                val height = maxOf(1, (width.toLong() * page.height / page.width).toInt())
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            }
        }
    }

    /** Runs on the render thread so it can't close the renderer under an in-flight render. */
    override fun close() {
        CoroutineScope(pdfDispatcher).launch {
            closed = true
            cache.evictAll()
            renderer.close()
            fd.close()
        }
    }

    companion object {
        private const val MAX_RENDER_WIDTH = 2400

        @OptIn(ExperimentalCoroutinesApi::class)
        private val pdfDispatcher = Dispatchers.IO.limitedParallelism(1)

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        suspend fun open(file: File): PdfDocument = withContext(pdfDispatcher) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            // Page sizes up front, so every page's height is known before layout and a restore
            // can compute the exact pixel offset without rendering anything.
            val sizes = List(renderer.pageCount) { i ->
                renderer.openPage(i).use { PageSize(it.width, it.height) }
            }
            PdfDocument(fd, renderer, sizes)
        }
    }
}
