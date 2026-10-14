package io.github.satyanvm.reader.sync

import io.github.satyanvm.reader.account.AccountSession
import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.model.ReadingPosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves positions between the local store and the server. Every server answer is merged through
 * [ReadingPositionRepository.mergeRemote], so ordering of responses never matters.
 */
class SyncEngine(
    private val repository: ReadingPositionRepository,
    private val api: SyncApi?,
    private val session: AccountSession,
    /** The server rejected the token; the account should sign out and ask to sign in again. */
    private val onUnauthorized: () -> Unit = {},
) {
    private val pushLock = Mutex()

    val isEnabled: Boolean get() = api != null && session.isSignedIn

    /**
     * Pushes every unacknowledged local position. Returns false if anything failed and should be
     * retried later.
     */
    suspend fun pushPending(): Boolean {
        if (!isEnabled) return true
        return pushLock.withLock {
            var ok = true
            for (local in repository.pending()) {
                try {
                    repository.mergeRemote(api!!.put(local))
                } catch (e: UnauthorizedException) {
                    onUnauthorized()
                    // Still pending: it goes up after the next sign-in.
                    return@withLock true
                } catch (e: SyncUnavailableException) {
                    ok = false
                }
            }
            ok
        }
    }

    /**
     * Fetches the server's position for [documentId] and merges it. Returns the server's position
     * (which may have lost the merge), or null if there's none or the server is unreachable.
     */
    suspend fun pull(documentId: String): ReadingPosition? {
        if (!isEnabled) return null
        val remote = try {
            api!!.get(documentId)
        } catch (e: UnauthorizedException) {
            onUnauthorized()
            return null
        } catch (e: SyncUnavailableException) {
            return null
        } ?: return null
        repository.mergeRemote(remote)
        return remote
    }
}
