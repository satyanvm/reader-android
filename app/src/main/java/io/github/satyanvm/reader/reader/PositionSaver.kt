package io.github.satyanvm.reader.reader

import io.github.satyanvm.reader.model.ReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a stream of scroll positions into a small number of durable writes.
 *
 * A write happens [debounceMs] after scrolling settles, and at least every [maxWaitMs] during
 * continuous scrolling, so a long reading session is never more than a few seconds from disk.
 * [flush] writes immediately and is called on pause/stop. Nothing relies on it, because the OS
 * can kill the process without warning.
 *
 * Writes run in [scope], which should outlive the screen (the application scope), so a pending
 * write still completes after the reader closes.
 */
class PositionSaver(
    private val scope: CoroutineScope,
    private val persist: suspend (ReadingPosition) -> Unit,
    private val debounceMs: Long = 500,
    private val maxWaitMs: Long = 3_000,
    private val now: () -> Long,
) {
    private val writeLock = Mutex()
    private var pending: ReadingPosition? = null
    private var pendingSince: Long = 0
    private var lastWritten: ReadingPosition? = null
    private var timer: Job? = null

    @Synchronized
    fun submit(position: ReadingPosition) {
        if (pending == null) pendingSince = now()
        pending = position
        val waited = now() - pendingSince
        val wait = minOf(debounceMs, maxOf(0, maxWaitMs - waited))
        timer?.cancel()
        timer = scope.launch {
            delay(wait)
            write()
        }
    }

    /**
     * Tells the saver what's already stored (after a restore), so a later write is skipped only
     * if it matches what's on disk now, not something written before a remote position replaced it.
     */
    fun markStored(position: ReadingPosition?) {
        scope.launch { writeLock.withLock { lastWritten = position } }
    }

    /** Writes any pending position now. Returns the job so callers can wait on it if they want. */
    fun flush(): Job {
        synchronized(this) { timer?.cancel() }
        return scope.launch { write() }
    }

    private suspend fun write() {
        writeLock.withLock {
            val next = synchronized(this) {
                val p = pending
                pending = null
                p
            } ?: return
            if (next.samePlaceAs(lastWritten)) return
            persist(next)
            lastWritten = next
        }
    }
}
