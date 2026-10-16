package io.github.satyanvm.reader.sync

import io.github.satyanvm.reader.model.ReadingPosition

/**
 * Server contract for reading positions (see docs/position-sync.md).
 *
 * The server must apply the same ordering as the client: store an incoming position only if its
 * stamp is greater than the stored one, and always answer with whatever is stored afterwards.
 * That makes `put` idempotent and safe to retry or deliver out of order.
 */
interface SyncApi {
    /** The server's position for [documentId], or null if it has none. */
    suspend fun get(documentId: String): ReadingPosition?

    /** Offers [position] to the server; returns the server's winner after the compare. */
    suspend fun put(position: ReadingPosition): ReadingPosition
}

/** Network or server failure that's worth retrying later. */
open class SyncUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server rejected the session token (401): signing in again is the only fix. */
class UnauthorizedException : SyncUnavailableException("HTTP 401: session is no longer valid")

/** A book in the account's library on the server. */
data class RemoteDocument(
    val documentId: String,
    val title: String,
    /** "PDF" or "EPUB". */
    val format: String,
    val sizeBytes: Long,
    val pageCount: Int,
    /** The file has been uploaded, so it can be downloaded. */
    val hasFile: Boolean,
    val addedAt: Long,
    /** Set when the book was removed from the library (on any device). */
    val removedAt: Long? = null,
)

/**
 * Server contract for the library (server/src/worker.js; the Mac app speaks the same one).
 * A book's id is the SHA-256 of its file, which the server checks on upload. A book is added
 * first ([add]), then its file goes up ([upload]).
 */
interface LibraryApi {
    /** Every book in the account, removed ones included (with [RemoteDocument.removedAt] set). */
    suspend fun library(): List<RemoteDocument>

    /** Adds the book to the account's library (or brings it back if it was removed). */
    suspend fun add(documentId: String, title: String, format: String, sizeBytes: Long, pageCount: Int)

    /** Uploads the file of a book already [add]ed. */
    suspend fun upload(documentId: String, file: java.io.File)

    /** Writes the book's file to [dest]. */
    suspend fun download(documentId: String, dest: java.io.File)
}

/** The server can't take this book (too large, or rejected): retrying won't help. */
class BookRejectedException(message: String) : Exception(message)

/** The server has no book storage set up; only positions sync. */
class BookStorageUnavailableException : SyncUnavailableException("the server has no book storage")
