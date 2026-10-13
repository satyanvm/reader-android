package io.github.satyanvm.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.data.RoomPositionStore
import io.github.satyanvm.reader.data.db.AppDatabase
import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SQLite file: closing and reopening it stands in for an app or device restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "persistence-test.db"

    private fun repo(db: AppDatabase) =
        ReadingPositionRepository(RoomPositionStore(db), HybridClock(), "laptop")

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `position survives closing and reopening the database`() = runBlocking {
        val first = AppDatabase.open(context, dbName)
        repo(first).saveLocal(pos(186, 0.5f, zoom = 1.5f))
        first.close()

        val second = AppDatabase.open(context, dbName)
        val restored = repo(second).load(DOC)!!
        assertEquals(186, restored.pageIndex)
        assertEquals(0.5f, restored.pageOffset)
        assertEquals(1.5f, restored.zoom)
        assertEquals("unsynced flag survives too", 1, repo(second).pending().size)
        second.close()
    }

    @Test
    fun `stale remote write is rejected by the stored compare`() = runBlocking {
        val db = AppDatabase.open(context, dbName)
        val r = repo(db)
        val saved = r.saveLocal(pos(90))
        r.mergeRemote(pos(10).copy(stamp = Stamp(saved.stamp.hlc - 1, "tablet")))
        assertEquals(90, r.load(DOC)!!.pageIndex)

        r.mergeRemote(saved) // server acknowledges exactly what we sent
        assertTrue(r.pending().isEmpty())
        db.close()
    }
}
