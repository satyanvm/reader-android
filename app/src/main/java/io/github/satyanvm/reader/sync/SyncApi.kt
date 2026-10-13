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
class SyncUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
