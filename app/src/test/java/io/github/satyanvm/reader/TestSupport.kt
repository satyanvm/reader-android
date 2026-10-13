package io.github.satyanvm.reader

import android.graphics.Bitmap
import io.github.satyanvm.reader.account.AccountSession
import io.github.satyanvm.reader.data.InMemoryPositionStore
import io.github.satyanvm.reader.data.PositionStore
import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import io.github.satyanvm.reader.reader.PageSize
import io.github.satyanvm.reader.reader.PagedDocument
import io.github.satyanvm.reader.sync.SyncApi
import io.github.satyanvm.reader.sync.SyncEngine
import io.github.satyanvm.reader.sync.SyncUnavailableException
import io.github.satyanvm.reader.telemetry.RestoreTelemetry
import kotlinx.coroutines.CompletableDeferred

const val DOC = "doc-sha256"
const val PAGES = 500

fun pos(page: Int, offset: Float = 0f, zoom: Float = 1f) = ReadingPosition(
    documentId = DOC,
    pageIndex = page,
    pageOffset = offset,
    zoom = zoom,
    pageCount = PAGES,
    stamp = Stamp(0, ""),
)

/** Server with the contract's compare-and-store semantics, plus knobs for failure tests. */
class FakeServer {
    val rows = mutableMapOf<String, ReadingPosition>()
    var online = true

    /** When set, `get` waits on it, simulating a slow response. */
    var getGate: CompletableDeferred<Unit>? = null

    fun put(p: ReadingPosition): ReadingPosition {
        val existing = rows[p.documentId]
        if (existing == null || p.stamp > existing.stamp) rows[p.documentId] = p
        return rows.getValue(p.documentId)
    }

    fun api() = object : SyncApi {
        override suspend fun get(documentId: String): ReadingPosition? {
            getGate?.await()
            if (!online) throw SyncUnavailableException("offline")
            return rows[documentId]
        }

        override suspend fun put(position: ReadingPosition): ReadingPosition {
            if (!online) throw SyncUnavailableException("offline")
            return this@FakeServer.put(position)
        }
    }
}

object SignedIn : AccountSession {
    override val isSignedIn = true
    override fun token() = "t"
}

/** One device: its own durable store, clock and id, sharing [server] with other devices. */
class Device(
    val id: String,
    private val server: FakeServer?,
    var wallClock: Long = 1_000_000L,
    val store: PositionStore = InMemoryPositionStore(),
) {
    val clock = HybridClock({ wallClock })
    val repo = ReadingPositionRepository(store, clock, id)
    val sync = SyncEngine(repo, server?.api(), SignedIn)

    /** Same device after an app restart: same disk, fresh process state. */
    fun restarted() = Device(id, server, wallClock, store)
}

class FakeDocument(override val pageSizes: List<PageSize> = List(PAGES) { PageSize(612, 792) }) :
    PagedDocument {
    override suspend fun render(index: Int, widthPx: Int): Bitmap = error("not rendered in tests")
    override fun close() = Unit
}

class RecordingTelemetry : RestoreTelemetry {
    val events = mutableListOf<RestoreTelemetry.Event>()
    override fun record(event: RestoreTelemetry.Event, detail: String) {
        events += event
    }
}
