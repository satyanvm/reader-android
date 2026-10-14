package io.github.satyanvm.reader.reader

import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import io.github.satyanvm.reader.sync.SyncEngine
import io.github.satyanvm.reader.telemetry.RestoreTelemetry
import io.github.satyanvm.reader.telemetry.RestoreTelemetry.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ReadingMode {
    /** The PDF's own page images. */
    PAGES,
    /** Extracted text reflowed to the screen, like an ebook. */
    TEXT,
}

/** Layout inputs that change pixel offsets. A change means the position must be re-applied. */
data class LayoutKey(
    val viewportWidthPx: Int,
    val zoom: Float,
    val mode: ReadingMode = ReadingMode.PAGES,
    val textScale: Float = 1f,
    /** Flowing text read a screen at a time rather than scrolled. */
    val paged: Boolean = false,
)

/** What opening a document produces. */
data class LoadedDocument(
    val title: String,
    val document: ReaderDocument,
    val mode: ReadingMode = ReadingMode.PAGES,
    val textScale: Float = 1f,
)

sealed interface Phase {
    data object Loading : Phase
    data class Failed(val message: String) : Phase

    /**
     * The UI must scroll to [target] (null = the beginning) and then call
     * [ReaderController.onRestoreApplied] with the same [token].
     */
    data class Restoring(val token: Int, val target: ReadingPosition?, val reason: Reason) : Phase

    /** Scroll reports are user navigation and get saved. */
    data object Reading : Phase

    enum class Reason { INITIAL, REMOTE, RELAYOUT, USER }
}

sealed interface Notice {
    /** Opened where the user left off. */
    data class Resumed(val pageIndex: Int) : Notice
    /** A newer position from another device arrived and was applied before the user moved. */
    data class SyncedFromOtherDevice(val pageIndex: Int) : Notice
    /** A newer position from another device arrived after the user started reading here. */
    data class OtherDeviceAvailable(val position: ReadingPosition) : Notice
}

data class ReaderUiState(
    val title: String = "",
    val document: ReaderDocument? = null,
    val phase: Phase = Phase.Loading,
    val zoom: Float = 1f,
    val mode: ReadingMode = ReadingMode.PAGES,
    /** Text size multiplier in flowing-text mode. */
    val textScale: Float = 1f,
    val pageIndex: Int = 0,
    val notice: Notice? = null,
) {
    fun layoutKey(viewportWidthPx: Int) = LayoutKey(viewportWidthPx, zoom, mode, textScale)
}

/**
 * Restore-and-track state machine for one open document.
 *
 * Rules that keep saved progress safe:
 *  - Nothing is saved until the initial restore has been applied, so loading and layout (which
 *    start at page 0) can never overwrite a saved position.
 *  - Only scroll reports produced under the currently applied layout count. Reports made while
 *    a rotation or zoom change is re-laying out pages are ignored until the position has been
 *    re-applied to the new layout.
 *  - A position arriving from the server is applied only if it's newer than what was restored
 *    and the user hasn't navigated since. Otherwise it's offered, never forced.
 */
class ReaderController(
    private val documentId: String,
    private val loadDocument: suspend () -> LoadedDocument,
    private val positions: ReadingPositionRepository,
    private val sync: SyncEngine,
    private val telemetry: RestoreTelemetry,
    /** Scope of the screen; cancelled when it goes away. */
    private val scope: CoroutineScope,
    /** Scope that outlives the screen, for writes that must finish. */
    private val saveScope: CoroutineScope,
    private val now: () -> Long,
    private val firstOpenRemoteWaitMs: Long = 1_500,
) {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val saver = PositionSaver(saveScope, { positions.saveLocal(it) }, now = now)

    private var pageCount = 0
    private var token = 0
    private var appliedLayout: LayoutKey? = null
    /** Where the viewport is now, as last applied or reported. */
    private var current: ReadingPosition? = null
    /** The stored position the reader was restored from (null if it started fresh). */
    private var restoredFrom: ReadingPosition? = null
    private var userMoved = false
    private var initialRestoreDone = false

    fun start() {
        scope.launch {
            val loaded = try {
                loadDocument()
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Failed(e.message ?: "Couldn't open document")) }
                return@launch
            }
            val doc = loaded.document
            pageCount = doc.pageCount
            _state.update {
                it.copy(
                    title = loaded.title,
                    document = doc,
                    mode = loaded.mode,
                    textScale = loaded.textScale,
                )
            }

            val local = positions.load(documentId)?.validFor(pageCount)
            val pull = scope.async { sync.pull(documentId)?.validFor(pageCount) }

            var initial = local
            // New on this device: briefly wait for the server rather than flash page 1 and jump.
            if (local == null && sync.isEnabled) {
                initial = withTimeoutOrNull(firstOpenRemoteWaitMs) { pull.await() }
            }
            restoredFrom = initial
            beginRestore(initial, Phase.Reason.INITIAL)

            pull.await()?.let(::onRemote)
        }
    }

    /** The UI's layout inputs changed (first layout, rotation, resize, zoom). */
    fun onLayout(key: LayoutKey) {
        if (_state.value.phase == Phase.Reading && key != appliedLayout) {
            beginRestore(current, Phase.Reason.RELAYOUT)
        }
    }

    /**
     * The UI finished scrolling for restore [token] under [layout]. [pageIndex]/[pageOffset] is
     * where it actually landed, which can differ from the target near the end of the document.
     */
    fun onRestoreApplied(
        token: Int,
        layout: LayoutKey,
        pageIndex: Int,
        pageOffset: Float,
        horizontalOffset: Float,
        atEnd: Boolean,
    ) {
        val restoring = _state.value.phase as? Phase.Restoring ?: return
        if (restoring.token != token) return
        appliedLayout = layout
        val target = restoring.target
        current = position(pageIndex, pageOffset, horizontalOffset)

        if (target != null && pageIndex != target.pageIndex && !atEnd) {
            telemetry.record(Event.RESTORE_MISMATCH, "target=${target.pageIndex} actual=$pageIndex")
            if (pageIndex == 0) telemetry.record(Event.UNEXPECTED_RESET, "target=${target.pageIndex}")
        }

        var notice = _state.value.notice
        when (restoring.reason) {
            Phase.Reason.INITIAL -> {
                initialRestoreDone = true
                saver.markStored(target)
                when {
                    target == null -> telemetry.record(Event.FIRST_OPEN)
                    target.stamp.deviceId == positions.deviceId -> telemetry.record(Event.RESTORED_LOCAL)
                    else -> telemetry.record(Event.RESTORED_REMOTE)
                }
                if (target != null && !target.isAtStart) notice = Notice.Resumed(target.pageIndex)
            }
            Phase.Reason.REMOTE -> saver.markStored(target)
            Phase.Reason.RELAYOUT, Phase.Reason.USER -> Unit
        }
        _state.update { it.copy(phase = Phase.Reading, pageIndex = pageIndex, notice = notice) }
    }

    /** A scroll report from the UI. Saved only if it's real navigation under the applied layout. */
    fun onScrolled(layout: LayoutKey, pageIndex: Int, pageOffset: Float, horizontalOffset: Float) {
        if (_state.value.phase != Phase.Reading || layout != appliedLayout) return
        val pos = position(pageIndex, pageOffset, horizontalOffset)
        if (pos.samePlaceAs(current)) return
        current = pos
        userMoved = true
        if (_state.value.pageIndex != pageIndex) _state.update { it.copy(pageIndex = pageIndex) }
        saver.submit(pos)
    }

    fun setZoom(zoom: Float) {
        val z = zoom.coerceIn(ReadingPosition.MIN_ZOOM, ReadingPosition.MAX_ZOOM)
        if (z == _state.value.zoom) return
        _state.update { it.copy(zoom = z) }
        current = current?.copy(zoom = z)
        if (initialRestoreDone) {
            userMoved = true
            current?.let(saver::submit)
        }
        // The UI reports the new LayoutKey via onLayout, which re-applies the position.
    }

    /**
     * Switches between original pages and flowing text. The position is kept as-is (it's
     * expressed per source page, so it means the same place in both) and re-applied to the new
     * layout via [onLayout].
     */
    fun setMode(mode: ReadingMode) {
        if (mode != _state.value.mode) _state.update { it.copy(mode = mode) }
    }

    /** Flowing-text size. Like a mode switch, it keeps the position and re-applies it. */
    fun setTextScale(scale: Float) {
        val s = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        if (s != _state.value.textScale) _state.update { it.copy(textScale = s) }
    }

    /** Jump to the top of page [index] (the progress slider). Saved like any navigation. */
    fun goToPage(index: Int) {
        if (!initialRestoreDone || pageCount == 0) return
        val target = position(index.coerceIn(0, pageCount - 1), 0f, 0f)
        userMoved = true
        current = target
        saver.submit(target)
        beginRestore(target, Phase.Reason.USER)
        _state.update { it.copy(pageIndex = target.pageIndex) }
    }

    /** Explicit "start from the beginning". Saved immediately so it wins everywhere. */
    fun restartFromBeginning() {
        val start = position(0, 0f, 0f)
        userMoved = true
        current = start
        telemetry.record(Event.USER_RESTART)
        saver.submit(start)
        saver.flush()
        beginRestore(start, Phase.Reason.USER)
        _state.update { it.copy(notice = null) }
    }

    /** Accepts an [Notice.OtherDeviceAvailable] offer. */
    fun jumpTo(target: ReadingPosition) {
        userMoved = true
        current = target
        saver.submit(target)
        saver.flush()
        beginRestore(target, Phase.Reason.USER)
        _state.update { it.copy(notice = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Lifecycle pause/stop: write what we have now. */
    fun flush() {
        saver.flush()
    }

    private fun onRemote(remote: ReadingPosition) {
        val base = restoredFrom
        if (base != null && !remote.isNewerThan(base)) return // stale or already applied
        if (remote.samePlaceAs(current)) return
        if (!userMoved) {
            restoredFrom = remote
            if (initialRestoreDone || _state.value.phase !is Phase.Restoring) {
                telemetry.record(Event.REMOTE_APPLIED_LATE, "page=${remote.pageIndex}")
                _state.update { it.copy(notice = Notice.SyncedFromOtherDevice(remote.pageIndex)) }
                beginRestore(remote, Phase.Reason.REMOTE)
            } else {
                // Initial restore hasn't been applied yet: just retarget it.
                beginRestore(remote, Phase.Reason.INITIAL)
            }
        } else {
            // The user is reading here; keep this device's position as the latest, and offer
            // the other one.
            current?.let { here -> saveScope.launch { positions.saveLocal(here) } }
            _state.update { it.copy(notice = Notice.OtherDeviceAvailable(remote)) }
        }
    }

    private fun beginRestore(target: ReadingPosition?, reason: Phase.Reason) {
        token += 1
        _state.update {
            it.copy(
                phase = Phase.Restoring(token, target, reason),
                zoom = target?.zoom ?: it.zoom,
            )
        }
    }

    private fun position(pageIndex: Int, pageOffset: Float, horizontalOffset: Float) = ReadingPosition(
        documentId = documentId,
        pageIndex = pageIndex,
        pageOffset = pageOffset,
        horizontalOffset = horizontalOffset,
        zoom = _state.value.zoom,
        pageCount = pageCount,
        stamp = UNSTAMPED,
    )

    companion object {
        const val MIN_TEXT_SCALE = 0.7f
        const val MAX_TEXT_SCALE = 2f
        private val UNSTAMPED = Stamp(0, "")
    }
}
