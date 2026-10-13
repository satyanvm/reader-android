package io.github.satyanvm.reader.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.satyanvm.reader.data.db.DocumentDao
import io.github.satyanvm.reader.data.db.DocumentEntity
import io.github.satyanvm.reader.data.db.LibraryRow
import io.github.satyanvm.reader.text.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Imports PDFs into app storage and keeps the library list.
 *
 * Each PDF is copied into app-private storage and named by the SHA-256 of its bytes. The hash is
 * the document id everywhere, including sync: the same file opened on another device gets the
 * same id. The copy keeps the document readable offline, after reboots, and after the source
 * app revokes its URI permission.
 */
class UnsupportedFormatException : Exception("Only PDF and EPUB files are supported")

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(context.filesDir, "documents").apply { mkdirs() }

    fun library(): Flow<List<LibraryRow>> = dao.library()

    suspend fun get(id: String): DocumentEntity? = dao.get(id)

    suspend fun touch(id: String) = dao.touch(id, now())

    suspend fun setReadingMode(id: String, mode: String) = dao.setReadingMode(id, mode)

    suspend fun import(uri: Uri): DocumentEntity = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("import", ".pdf", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Can't open $uri")
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        dst.write(buf, 0, n)
                    }
                    dst.fd.sync()
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val format = when {
                isPdf(tmp) -> "PDF"
                EpubBook.isEpub(tmp) -> "EPUB"
                else -> throw UnsupportedFormatException()
            }
            val target = File(dir, "$id.${format.lowercase()}")
            if (target.exists()) tmp.delete() else check(tmp.renameTo(target)) { "rename failed" }

            val existing = dao.get(id)
            val entity = existing?.copy(lastOpenedAt = now()) ?: run {
                val (pages, bookTitle) = if (format == "PDF") pageCount(target) to null
                else EpubBook.open(target).use { it.pageCount to it.title }
                DocumentEntity(
                    id = id,
                    title = bookTitle ?: displayName(uri)?.substringBeforeLast('.') ?: "Untitled",
                    localPath = target.absolutePath,
                    sizeBytes = target.length(),
                    pageCount = pages,
                    lastOpenedAt = now(),
                    format = format,
                )
            }
            dao.put(entity)
            entity
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun isPdf(file: File): Boolean =
        file.inputStream().use { input -> ByteArray(1024).let { b -> String(b, 0, maxOf(0, input.read(b)), Charsets.ISO_8859_1) } }
            .contains("%PDF-")

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
}
