package io.github.satyanvm.reader.reader

import android.graphics.Bitmap
import java.io.Closeable

/** What the reader needs from any document; lets the restore logic be tested without a file. */
interface ReaderDocument : Closeable {
    /** PDF pages, or EPUB chapters: the unit a [io.github.satyanvm.reader.model.ReadingPosition] counts in. */
    val pageCount: Int
}

/** A document with fixed page images (PDF). */
interface PagedDocument : ReaderDocument {
    val pageSizes: List<PageSize>
    override val pageCount: Int get() = pageSizes.size
    suspend fun render(index: Int, widthPx: Int): Bitmap
}
