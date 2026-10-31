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
 * Imports PDFs and EPUBs into app storage and keeps the library list.
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

    suspend fun all(): List<DocumentEntity> = dao.all()

    /** The stored file for [doc], if it's still there. */
    fun fileOf(doc: DocumentEntity): File? = File(doc.localPath).takeIf { it.isFile }

    suspend fun touch(id: String) = dao.touch(id, now())

    suspend fun setReadingMode(id: String, mode: String) = dao.setReadingMode(id, mode)

    suspend fun setCurrentlyReading(id: String, reading: Boolean) = dao.setCurrentlyReading(id, reading)

    /** The file's name without its extension, offered as the book's title before importing. */
    suspend fun suggestedTitle(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { displayName(uri) }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    }

    /** Whether [uri] looks like a PDF by its type or name; PDFs are named by the reader on import. */
    fun looksLikePdf(uri: Uri): Boolean =
        context.contentResolver.getType(uri) == "application/pdf" ||
            runCatching { displayName(uri) }.getOrNull()?.endsWith(".pdf", ignoreCase = true) == true

    /**
     * Copies [uri] into the library. A [title] the reader typed names the book, here and on the
     * account, even if the book was already in the library under another name.
     */
    suspend fun import(uri: Uri, title: String? = null): DocumentEntity = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("import", ".part", dir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Can't open $uri")
            input.use { src ->
                tmp.outputStream().use { dst ->
                    src.copyTo(dst, DEFAULT_BUFFER_SIZE * 8)
                    dst.fd.sync()
                }
            }
            val id = sha256(tmp)
            val chosen = title?.trim()?.takeIf { it.isNotEmpty() }
            val doc = register(
                id, tmp,
                titleFallback = chosen ?: displayName(uri)?.substringBeforeLast('.'),
                openedAt = now(),
                preferFallbackTitle = chosen != null,
            )
            if (chosen != null && doc.title != chosen) {
                dao.rename(id, chosen)
                doc.copy(title = chosen)
            } else {
                doc
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Adds a book downloaded from the sync server. [tmp] is the downloaded file; it must hash to
     * [id], or it's discarded. The server's title is used unless the book names itself.
     * Returns null if the file is corrupt or not a book.
     */
    suspend fun adoptDownload(id: String, title: String, addedAt: Long, tmp: File): DocumentEntity? =
        withContext(Dispatchers.IO) {
            try {
                if (sha256(tmp) != id) null
                else register(id, tmp, titleFallback = title, openedAt = addedAt, preferFallbackTitle = true)
            } catch (e: UnsupportedFormatException) {
                null
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

    /** A scratch file in the documents folder, so finishing a download is a rename. */
    fun tempFile(): File = File.createTempFile("download", ".part", dir)

    /** Moves [tmp] (already hashed to [id]) into place and records it, unless it's known. */
    private suspend fun register(
        id: String,
        tmp: File,
        titleFallback: String?,
        openedAt: Long,
        preferFallbackTitle: Boolean = false,
    ): DocumentEntity {
        val format = when {
            isPdf(tmp) -> "PDF"
            EpubBook.isEpub(tmp) -> "EPUB"
            else -> throw UnsupportedFormatException()
        }
        val target = File(dir, "$id.${format.lowercase()}")
        if (target.exists()) tmp.delete() else check(tmp.renameTo(target)) { "rename failed" }

        val existing = dao.get(id)
        val entity = existing?.copy(lastOpenedAt = maxOf(existing.lastOpenedAt, openedAt)) ?: run {
            val (pages, bookTitle) = if (format == "PDF") pageCount(target) to null
            else EpubBook.open(target).use { it.pageCount to it.title }
            DocumentEntity(
                id = id,
                title = (if (preferFallbackTitle) titleFallback ?: bookTitle else bookTitle ?: titleFallback) ?: "Untitled",
                localPath = target.absolutePath,
                sizeBytes = target.length(),
                pageCount = pages,
                lastOpenedAt = openedAt,
                format = format,
            )
        }
        dao.put(entity)
        return entity
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
