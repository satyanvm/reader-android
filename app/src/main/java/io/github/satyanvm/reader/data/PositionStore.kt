package io.github.satyanvm.reader.data

import androidx.room.withTransaction
import io.github.satyanvm.reader.data.db.AppDatabase
import io.github.satyanvm.reader.data.db.PositionEntity
import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredPosition(val position: ReadingPosition, val pendingSync: Boolean)

/**
 * Durable per-document position storage. All read-compare-write sequences go through
 * [transaction] so a merge decision is never made on stale data.
 */
interface PositionStore {
    suspend fun <T> transaction(block: suspend PositionTx.() -> T): T
    fun observe(documentId: String): Flow<StoredPosition?>
    suspend fun pending(): List<StoredPosition>
    suspend fun maxHlc(): Long
}

interface PositionTx {
    suspend fun get(documentId: String): StoredPosition?
    suspend fun put(stored: StoredPosition)
}

class RoomPositionStore(private val db: AppDatabase) : PositionStore {
    private val dao = db.positions()

    private val tx = object : PositionTx {
        override suspend fun get(documentId: String) = dao.get(documentId)?.toStored()
        override suspend fun put(stored: StoredPosition) = dao.put(stored.toEntity())
    }

    override suspend fun <T> transaction(block: suspend PositionTx.() -> T): T =
        db.withTransaction { tx.block() }

    override fun observe(documentId: String): Flow<StoredPosition?> =
        dao.observe(documentId).map { it?.toStored() }.distinctUntilChanged()

    override suspend fun pending(): List<StoredPosition> = dao.pending().map { it.toStored() }

    override suspend fun maxHlc(): Long = dao.maxHlc()
}

/** Non-durable store used by tests and previews; same semantics as [RoomPositionStore]. */
class InMemoryPositionStore : PositionStore {
    private val mutex = Mutex()
    private val rows = MutableStateFlow<Map<String, StoredPosition>>(emptyMap())

    private val tx = object : PositionTx {
        override suspend fun get(documentId: String) = rows.value[documentId]
        override suspend fun put(stored: StoredPosition) {
            rows.value = rows.value + (stored.position.documentId to stored)
        }
    }

    override suspend fun <T> transaction(block: suspend PositionTx.() -> T): T =
        mutex.withLock { tx.block() }

    override fun observe(documentId: String): Flow<StoredPosition?> =
        rows.map { it[documentId] }.distinctUntilChanged()

    override suspend fun pending(): List<StoredPosition> =
        rows.value.values.filter { it.pendingSync }

    override suspend fun maxHlc(): Long = rows.value.values.maxOfOrNull { it.position.stamp.hlc } ?: 0L
}

private fun PositionEntity.toStored() = StoredPosition(
    position = ReadingPosition(
        documentId = documentId,
        pageIndex = pageIndex,
        pageOffset = pageOffset,
        horizontalOffset = horizontalOffset,
        zoom = zoom,
        pageCount = pageCount,
        stamp = Stamp(hlc, deviceId),
        schemaVersion = schemaVersion,
    ),
    pendingSync = pendingSync,
)

private fun StoredPosition.toEntity() = PositionEntity(
    documentId = position.documentId,
    pageIndex = position.pageIndex,
    pageOffset = position.pageOffset,
    horizontalOffset = position.horizontalOffset,
    zoom = position.zoom,
    pageCount = position.pageCount,
    hlc = position.stamp.hlc,
    deviceId = position.stamp.deviceId,
    schemaVersion = position.schemaVersion,
    pendingSync = pendingSync,
)
