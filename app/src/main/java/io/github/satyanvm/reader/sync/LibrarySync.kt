package io.github.satyanvm.reader.sync

import io.github.satyanvm.reader.account.AccountSession
import io.github.satyanvm.reader.data.DocumentRepository
import io.github.satyanvm.reader.data.ReadingPositionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the whole library in step with the account, with no button to press:
 *
 *  - every book on this device that the account doesn't have is added and its file uploaded;
 *  - every book in the account that this device doesn't have is downloaded;
 *  - every book's reading position is pulled from the server and merged (the usual
 *    last-writer-wins compare, so newer reading here is kept and goes up with the next push).
 *
 * A book removed from the account on another device isn't added back from here, and isn't
 * deleted here either. Runs from the background sync job (SyncScheduler), so it waits for a
 * network and retries.
 */
class LibrarySync(
    private val api: LibraryApi?,
    private val positionsApi: SyncApi?,
    private val session: AccountSession,
    private val documents: DocumentRepository,
    private val positions: ReadingPositionRepository,
    /** The server rejected the token; the account should sign out and ask to sign in again. */
    private val onUnauthorized: () -> Unit = {},
) {
    private val lock = Mutex()

    val isEnabled: Boolean get() = api != null && session.isSignedIn

    /** Returns false if anything failed and should be retried later. */
    suspend fun sync(): Boolean {
        if (!isEnabled) return true
        val api = api!!
        return lock.withLock {
            try {
                val remote = api.library()
                val onServer = remote.associateBy { it.documentId }
                var ok = true

                var canStoreFiles = true
                for (doc in documents.all()) {
                    val there = onServer[doc.id]
                    if (there?.removedAt != null || there?.hasFile == true || !canStoreFiles) continue
                    val file = documents.fileOf(doc) ?: continue
                    try {
                        if (there == null) api.add(doc.id, doc.title, doc.format, file.length(), doc.pageCount)
                        api.upload(doc.id, file)
                    } catch (e: BookStorageUnavailableException) {
                        // Positions still sync; files wait until the server can hold them.
                        canStoreFiles = false
                    } catch (e: BookRejectedException) {
                        // Too large for the server, most likely. Nothing to retry.
                    } catch (e: UnauthorizedException) {
                        throw e
                    } catch (e: SyncUnavailableException) {
                        ok = false
                    }
                }

                for (book in remote) {
                    if (book.removedAt != null || !book.hasFile || documents.get(book.documentId) != null) continue
                    val tmp = documents.tempFile()
                    try {
                        api.download(book.documentId, tmp)
                        documents.adoptDownload(book.documentId, book.title, book.addedAt, tmp)
                    } catch (e: UnauthorizedException) {
                        throw e
                    } catch (e: BookStorageUnavailableException) {
                        break
                    } catch (e: SyncUnavailableException) {
                        ok = false
                    } catch (e: java.io.IOException) {
                        // Out of space, most likely; try again later.
                        ok = false
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                }

                // Every book's place, so each opens where it was left on another device.
                positionsApi?.let { p ->
                    for (doc in documents.all()) {
                        try {
                            p.get(doc.id)?.let { positions.mergeRemote(it) }
                        } catch (e: UnauthorizedException) {
                            throw e
                        } catch (e: SyncUnavailableException) {
                            ok = false
                        }
                    }
                }
                ok
            } catch (e: UnauthorizedException) {
                onUnauthorized()
                true
            } catch (e: SyncUnavailableException) {
                false
            }
        }
    }
}
