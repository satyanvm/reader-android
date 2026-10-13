package io.github.satyanvm.reader

import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScenariosTest {

    @Test
    fun `clock is monotonic and orders after anything observed`() {
        var wall = 100L
        val clock = HybridClock({ wall })
        val a = clock.now()
        val b = clock.now() // wall clock didn't move
        assertTrue(b > a)
        clock.observe(10_000)
        assertTrue(clock.now() > 10_000)
        wall = 5 // wall clock jumps backwards
        assertTrue(clock.now() > 10_000)
    }

    @Test
    fun `saved position survives an app restart`() = runTest {
        val laptop = Device("laptop", null)
        laptop.repo.saveLocal(pos(186, 0.5f))
        val reopened = laptop.restarted()
        assertEquals(186, reopened.repo.load(DOC)!!.pageIndex)
        assertEquals(0.5f, reopened.repo.load(DOC)!!.pageOffset)
    }

    @Test
    fun `clock is reseeded from disk after restart so new saves still win`() = runTest {
        val phone = Device("phone", null, wallClock = 5_000)
        val first = phone.repo.saveLocal(pos(10))
        val restarted = phone.restarted().apply { wallClock = 1_000 } // clock went backwards
        val second = restarted.repo.saveLocal(pos(11))
        assertTrue(second.stamp > first.stamp)
        assertEquals(11, restarted.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `position read on one device opens on another`() = runTest {
        val server = FakeServer()
        val laptop = Device("laptop", server)
        val phone = Device("phone", server)

        laptop.repo.saveLocal(pos(186, 0.5f))
        assertTrue(laptop.sync.pushPending())
        assertTrue(laptop.repo.pending().isEmpty())

        val remote = phone.sync.pull(DOC)
        assertEquals(186, remote!!.pageIndex)
        assertEquals(186, phone.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `offline reading syncs when connectivity returns`() = runTest {
        val server = FakeServer()
        val laptop = Device("laptop", server)
        laptop.repo.saveLocal(pos(50))
        laptop.sync.pushPending()

        server.online = false
        laptop.wallClock += 60_000
        laptop.repo.saveLocal(pos(120))
        assertFalse("push fails while offline", laptop.sync.pushPending())
        assertEquals(1, laptop.repo.pending().size)
        assertEquals(50, server.rows[DOC]!!.pageIndex)

        server.online = true
        assertTrue(laptop.sync.pushPending())
        assertEquals(120, server.rows[DOC]!!.pageIndex)
        assertTrue(laptop.repo.pending().isEmpty())
    }

    @Test
    fun `newer reading on another device wins over older offline progress`() = runTest {
        val server = FakeServer()
        val laptop = Device("laptop", server, wallClock = 1_000_000)
        val phone = Device("phone", server, wallClock = 1_000_000)

        server.online = false
        laptop.repo.saveLocal(pos(100)) // read offline on the laptop...
        server.online = true
        phone.wallClock = 2_000_000
        phone.repo.saveLocal(pos(250)) // ...then later on the phone, online
        phone.sync.pushPending()

        laptop.sync.pushPending() // laptop reconnects; its older position loses
        assertEquals(250, server.rows[DOC]!!.pageIndex)
        assertEquals(250, laptop.repo.load(DOC)!!.pageIndex)
        assertTrue(laptop.repo.pending().isEmpty())
    }

    @Test
    fun `restart from the beginning propagates even though it's a lower page`() = runTest {
        val server = FakeServer()
        val laptop = Device("laptop", server)
        val phone = Device("phone", server)
        laptop.repo.saveLocal(pos(300))
        laptop.sync.pushPending()
        phone.sync.pull(DOC)

        phone.wallClock += 1_000
        phone.repo.saveLocal(pos(0))
        phone.sync.pushPending()
        laptop.sync.pull(DOC)
        assertEquals(0, laptop.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `late or out-of-order server responses never overwrite newer local progress`() = runTest {
        val server = FakeServer()
        val phone = Device("phone", server)
        val old = Device("tablet", server).repo.saveLocal(pos(40))
        server.put(old)

        phone.wallClock += 10_000
        phone.repo.saveLocal(pos(90))

        // A response for an old request arrives after the newer local save.
        val winner = phone.repo.mergeRemote(old)
        assertEquals(90, winner.pageIndex)
        assertEquals(90, phone.repo.load(DOC)!!.pageIndex)
        assertEquals("still needs pushing", 1, phone.repo.pending().size)

        // Responses delivered in reverse order converge to the newest.
        val a = Stamp(5_000_000, "x")
        val b = Stamp(6_000_000, "x")
        phone.repo.mergeRemote(pos(200).copy(stamp = b))
        phone.repo.mergeRemote(pos(150).copy(stamp = a))
        assertEquals(200, phone.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `device with a slow clock still orders after what it has seen`() = runTest {
        val server = FakeServer()
        val fastClock = Device("fast", server, wallClock = 9_000_000)
        val slowClock = Device("slow", server, wallClock = 1_000_000)

        fastClock.repo.saveLocal(pos(100))
        fastClock.sync.pushPending()
        slowClock.sync.pull(DOC)

        // Later reading on the slow-clock device must win despite its smaller wall time.
        slowClock.repo.saveLocal(pos(101))
        slowClock.sync.pushPending()
        assertEquals(101, server.rows[DOC]!!.pageIndex)
    }

    @Test
    fun `equal stamps from a retried push clear pending without rewriting`() = runTest {
        val server = FakeServer()
        val phone = Device("phone", server)
        phone.repo.saveLocal(pos(7))
        phone.sync.pushPending()
        phone.sync.pushPending() // nothing pending, no-op
        assertTrue(phone.repo.pending().isEmpty())
        assertEquals(7, server.rows[DOC]!!.pageIndex)
    }
}
