package io.github.satyanvm.reader.data

import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that decides which reading position wins.
 *
 * Policy: last writer wins, ordered by [Stamp] (hybrid-logical-clock time, then device id).
 * Local saves and server responses both go through the same compare-and-write, so a stale write
 * from anywhere (a late network response, an old offline save, a retried push) can never replace
 * a newer position. "Restart from beginning" is an ordinary save and therefore also propagates.
 */
class ReadingPositionRepository(
    private val store: PositionStore,
    private val clock: HybridClock,
    val deviceId: String,
    /** Called after a local change that the server hasn't seen yet. */
    private val onPendingChange: () -> Unit = {},
) {
    private val clockInit = Mutex()
    private var clockReady = false

    /** The clock must never go backwards across restarts, so seed it from what's on disk. */
    private suspend fun readyClock(): HybridClock {
        if (!clockReady) clockInit.withLock {
            if (!clockReady) {
                clock.observe(store.maxHlc())
                clockReady = true
            }
        }
        return clock
    }

    suspend fun load(documentId: String): ReadingPosition? =
        store.transaction { get(documentId) }?.position

    fun observe(documentId: String): Flow<ReadingPosition?> =
        store.observe(documentId).map { it?.position }

    /**
     * Records that the user is now at [position] on this device. The stamp on [position] is
     * ignored and replaced with a fresh one. Returns the stored position.
     */
    suspend fun saveLocal(position: ReadingPosition): ReadingPosition {
        val stamped = position.copy(stamp = Stamp(readyClock().now(), deviceId))
        val written = store.transaction {
            val existing = get(stamped.documentId)?.position
            // A fresh local stamp beats anything already observed; the check only matters if
            // the store was written by someone who didn't go through this repository.
            if (stamped.isNewerThan(existing)) {
                put(StoredPosition(stamped, pendingSync = true))
                true
            } else {
                false
            }
        }
        if (written) onPendingChange()
        return stamped
    }

    /**
     * Merges a position that came from the server. Returns whichever position is now stored.
     */
    suspend fun mergeRemote(remote: ReadingPosition): ReadingPosition {
        readyClock().observe(remote.stamp.hlc)
        return store.transaction {
            val existing = get(remote.documentId)
            when {
                existing == null || remote.isNewerThan(existing.position) -> {
                    put(StoredPosition(remote, pendingSync = false))
                    remote
                }
                // The server already has exactly what we have: nothing left to push.
                existing.position.stamp == remote.stamp && existing.pendingSync -> {
                    put(existing.copy(pendingSync = false))
                    existing.position
                }
                else -> existing.position
            }
        }
    }

    /** Positions saved on this device that the server hasn't acknowledged. */
    suspend fun pending(): List<ReadingPosition> = store.pending().map { it.position }
}
