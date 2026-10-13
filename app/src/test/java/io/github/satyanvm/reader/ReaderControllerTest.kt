package io.github.satyanvm.reader

import io.github.satyanvm.reader.reader.LayoutKey
import io.github.satyanvm.reader.reader.LoadedDocument
import io.github.satyanvm.reader.reader.ReadingMode
import io.github.satyanvm.reader.reader.Notice
import io.github.satyanvm.reader.reader.Phase
import io.github.satyanvm.reader.reader.ReaderController
import io.github.satyanvm.reader.telemetry.RestoreTelemetry.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderControllerTest {
    private val phoneLayout = LayoutKey(viewportWidthPx = 1080, zoom = 1f)
    private val telemetry = RecordingTelemetry()

    private fun TestScope.open(device: Device, waitMs: Long = 1_500) = ReaderController(
        documentId = DOC,
        loadDocument = { LoadedDocument("Book", FakeDocument()) },
        positions = device.repo,
        sync = device.sync,
        telemetry = telemetry,
        scope = this,
        saveScope = this,
        now = { testScheduler.currentTime },
        firstOpenRemoteWaitMs = waitMs,
    ).also { it.start() }

    /** What the UI does for a restore: land exactly on the target and report it. */
    private fun ReaderController.applyRestore(layout: LayoutKey = phoneLayout) {
        val r = state.value.phase as Phase.Restoring
        val t = r.target
        onRestoreApplied(r.token, layout, t?.pageIndex ?: 0, t?.pageOffset ?: 0f, 0f, atEnd = false)
    }

    private fun ReaderController.restoringTarget() = (state.value.phase as Phase.Restoring).target

    @Test
    fun `first open with no history starts at the beginning`() = runTest {
        val c = open(Device("phone", null))
        advanceUntilIdle()
        assertNull(c.restoringTarget())
        c.applyRestore()
        assertEquals(Phase.Reading, c.state.value.phase)
        assertEquals(listOf(Event.FIRST_OPEN), telemetry.events)
    }

    @Test
    fun `reopen resumes at the saved page and offset`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(186, 0.5f))

        val c = open(device.restarted())
        advanceUntilIdle()
        assertEquals(186, c.restoringTarget()!!.pageIndex)
        assertEquals(0.5f, c.restoringTarget()!!.pageOffset)
        c.applyRestore()
        assertEquals(Notice.Resumed(186), c.state.value.notice)
        assertTrue(Event.RESTORED_LOCAL in telemetry.events)
    }

    @Test
    fun `scroll reports during loading and layout never overwrite saved progress`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(186, 0.5f))
        val c = open(device)
        advanceUntilIdle()

        // The list lays out at page 0 before the restore is applied.
        c.onLayout(phoneLayout)
        c.onScrolled(phoneLayout, 0, 0f, 0f)
        c.flush()
        advanceUntilIdle()
        assertEquals(186, device.repo.load(DOC)!!.pageIndex)

        c.applyRestore()
        c.onScrolled(phoneLayout, 186, 0.5f, 0f) // the post-restore report: not a move
        c.flush()
        advanceUntilIdle()
        assertEquals(186, device.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `reading is saved after the debounce without any lifecycle event`() = runTest {
        val device = Device("laptop", null)
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        c.onScrolled(phoneLayout, 186, 0.4f, 0f)
        advanceUntilIdle() // no flush(): simulates the process being killed without onPause
        assertEquals(186, device.repo.load(DOC)!!.pageIndex)
        assertEquals(0.4f, device.repo.load(DOC)!!.pageOffset)
    }

    @Test
    fun `continuous scrolling is saved at least every few seconds`() = runTest {
        val device = Device("laptop", null)
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        // A scroll report every 100ms for 3.5s never lets the debounce settle.
        for (i in 1..35) {
            c.onScrolled(phoneLayout, i, 0f, 0f)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
        }
        val saved = device.repo.load(DOC)
        assertTrue("max-wait write happened mid-scroll", saved != null && saved.pageIndex >= 20)
    }

    @Test
    fun `rotation re-applies the position instead of saving a shifted one`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(186, 0.5f))
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        val landscape = LayoutKey(2400, 1f)
        // The list re-measures under the new width before the controller hears about it.
        c.onScrolled(landscape, 186, 0.22f, 0f)
        c.onLayout(landscape)
        assertEquals(0.5f, c.restoringTarget()!!.pageOffset)
        c.applyRestore(landscape)
        c.flush()
        advanceUntilIdle()
        assertEquals(0.5f, device.repo.load(DOC)!!.pageOffset)
    }

    @Test
    fun `zoom change keeps the reading position and is saved`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(40, 0.3f))
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        c.setZoom(2f)
        val zoomed = LayoutKey(1080, 2f)
        c.onLayout(zoomed)
        assertEquals(40, c.restoringTarget()!!.pageIndex)
        assertEquals(0.3f, c.restoringTarget()!!.pageOffset)
        c.applyRestore(zoomed)
        advanceUntilIdle()
        assertEquals(2f, device.repo.load(DOC)!!.zoom)
        assertEquals(40, device.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `start over saves page one immediately`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(186))
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        c.restartFromBeginning()
        runCurrent()
        assertEquals(0, device.repo.load(DOC)!!.pageIndex)
        assertNull(c.restoringTarget()?.takeIf { !it.isAtStart })
    }

    @Test
    fun `new device waits briefly for the server instead of flashing page one`() = runTest {
        val server = FakeServer()
        Device("laptop", server).apply {
            repo.saveLocal(pos(186, 0.5f))
            sync.pushPending()
        }
        val c = open(Device("phone", server))
        advanceUntilIdle()
        assertEquals(186, c.restoringTarget()!!.pageIndex)
        c.applyRestore()
        assertTrue(Event.RESTORED_REMOTE in telemetry.events)
    }

    @Test
    fun `slow sync applies a newer remote position if the user hasn't moved`() = runTest {
        val server = FakeServer()
        val phone = Device("phone", server, wallClock = 1_000_000)
        phone.repo.saveLocal(pos(20))
        Device("laptop", server, wallClock = 2_000_000).apply {
            repo.saveLocal(pos(186))
            sync.pushPending()
        }
        val gate = CompletableDeferred<Unit>()
        server.getGate = gate

        val c = open(phone)
        advanceUntilIdle()
        assertEquals("opens at local position, not page one", 20, c.restoringTarget()!!.pageIndex)
        c.applyRestore()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(186, c.restoringTarget()!!.pageIndex)
        c.applyRestore()
        assertEquals(Notice.SyncedFromOtherDevice(186), c.state.value.notice)
        assertEquals(186, phone.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `slow sync never yanks a user who has started reading, it offers instead`() = runTest {
        val server = FakeServer()
        val phone = Device("phone", server, wallClock = 1_000_000)
        phone.repo.saveLocal(pos(20))
        Device("laptop", server, wallClock = 9_000_000).apply {
            repo.saveLocal(pos(186))
            sync.pushPending()
        }
        val gate = CompletableDeferred<Unit>()
        server.getGate = gate

        val c = open(phone)
        advanceUntilIdle()
        c.applyRestore()
        c.onScrolled(phoneLayout, 21, 0.1f, 0f)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Phase.Reading, c.state.value.phase)
        val notice = c.state.value.notice as Notice.OtherDeviceAvailable
        assertEquals(186, notice.position.pageIndex)
        // This device's reading stays the latest.
        assertEquals(21, phone.repo.load(DOC)!!.pageIndex)

        c.jumpTo(notice.position)
        runCurrent()
        assertEquals(186, c.restoringTarget()!!.pageIndex)
        advanceUntilIdle()
        assertEquals(186, phone.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `an older server position arriving late is ignored`() = runTest {
        val server = FakeServer()
        Device("laptop", server, wallClock = 1_000_000).apply {
            repo.saveLocal(pos(5))
            sync.pushPending()
        }
        val phone = Device("phone", server, wallClock = 5_000_000)
        phone.repo.saveLocal(pos(300))
        val gate = CompletableDeferred<Unit>()
        server.getGate = gate

        val c = open(phone)
        advanceUntilIdle()
        c.applyRestore()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(Phase.Reading, c.state.value.phase)
        assertFalse(c.state.value.notice is Notice.OtherDeviceAvailable)
        assertEquals(300, phone.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `landing on page one when progress existed is reported as a defect`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(186))
        val c = open(device)
        advanceUntilIdle()
        val r = c.state.value.phase as Phase.Restoring
        c.onRestoreApplied(r.token, phoneLayout, 0, 0f, 0f, atEnd = false)
        assertTrue(Event.UNEXPECTED_RESET in telemetry.events)
    }

    @Test
    fun `end-of-document clamp is not a defect and doesn't drift the saved position`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(499, 0.6f))
        val c = open(device)
        advanceUntilIdle()
        val r = c.state.value.phase as Phase.Restoring
        // The list can't put page 499 at the top; it stops on page 498.
        c.onRestoreApplied(r.token, phoneLayout, 498, 0.7f, 0f, atEnd = true)
        c.onScrolled(phoneLayout, 498, 0.7f, 0f)
        c.flush()
        advanceUntilIdle()
        assertFalse(Event.RESTORE_MISMATCH in telemetry.events)
        assertEquals(499, device.repo.load(DOC)!!.pageIndex)
    }

    @Test
    fun `switching to flowing text keeps the place and saves nothing new`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(66, 0.4f))
        val before = device.repo.load(DOC)!!
        val c = open(device)
        advanceUntilIdle()
        c.applyRestore()

        c.setMode(ReadingMode.TEXT)
        val text = LayoutKey(1080, 1f, ReadingMode.TEXT)
        // The text list starts at the top before the position is re-applied to it.
        c.onScrolled(text, 0, 0f, 0f)
        c.onLayout(text)
        assertEquals(66, c.restoringTarget()!!.pageIndex)
        assertEquals(0.4f, c.restoringTarget()!!.pageOffset)
        c.applyRestore(text)
        c.flush()
        advanceUntilIdle()
        assertEquals(before, device.repo.load(DOC))

        // Reading in text mode is saved as usual, in the same page terms.
        c.onScrolled(text, 67, 0.25f, 0f)
        advanceUntilIdle()
        assertEquals(67, device.repo.load(DOC)!!.pageIndex)
        assertEquals(0.25f, device.repo.load(DOC)!!.pageOffset)
    }

    @Test
    fun `changing text size re-applies the position`() = runTest {
        val device = Device("laptop", null)
        device.repo.saveLocal(pos(10, 0.5f))
        val c = ReaderController(
            documentId = DOC,
            loadDocument = { LoadedDocument("Book", FakeDocument(), ReadingMode.TEXT) },
            positions = device.repo,
            sync = device.sync,
            telemetry = telemetry,
            scope = this,
            saveScope = this,
            now = { testScheduler.currentTime },
        ).also { it.start() }
        advanceUntilIdle()
        val small = LayoutKey(1080, 1f, ReadingMode.TEXT, 1f)
        c.applyRestore(small)

        c.setTextScale(1.5f)
        val large = LayoutKey(1080, 1f, ReadingMode.TEXT, 1.5f)
        c.onScrolled(large, 10, 0.2f, 0f) // shifted by the bigger text: must be ignored
        c.onLayout(large)
        assertEquals(0.5f, c.restoringTarget()!!.pageOffset)
        c.applyRestore(large)
        c.flush()
        advanceUntilIdle()
        assertEquals(0.5f, device.repo.load(DOC)!!.pageOffset)
    }

    @Test
    fun `the progress slider jumps to a page and saves it`() = runTest {
        val device = Device("laptop", null)
        val c = open(device)
        advanceUntilIdle()
        c.goToPage(150) // ignored before the initial restore
        assertNull(c.restoringTarget())
        c.applyRestore()

        c.goToPage(150)
        assertEquals(150, c.restoringTarget()!!.pageIndex)
        c.applyRestore()
        advanceUntilIdle()
        assertEquals(150, device.repo.load(DOC)!!.pageIndex)
        assertEquals(150, c.state.value.pageIndex)
    }
}
