package io.github.satyanvm.reader.reader

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Which page is showing, and the page turn in progress. A turn is driven by a finger (it follows
 * the drag, then finishes or falls back on release) or plays by itself after a tap.
 */
internal class PageTurns {
    var current by mutableIntStateOf(0)
        private set
    var count = 0
    var turn by mutableStateOf<Turn?>(null)
        private set
    /** A turn finished on a new page (not called for [show]). */
    var onTurned: (Int) -> Unit = {}
    private var settling: Job? = null

    class Turn(val target: Int, val forward: Boolean, val horizontal: Boolean) {
        /** 0 = still on the old page, 1 = turned to [target]. */
        var amount by mutableFloatStateOf(0f)
        internal var committing = false
    }

    /** Starts a turn, or returns null if there's no page that way. */
    fun begin(forward: Boolean, horizontal: Boolean): Turn? {
        finishNow()
        val target = current + if (forward) 1 else -1
        if (target !in 0 until count) return null
        return Turn(target, forward, horizontal).also { turn = it }
    }

    fun drag(t: Turn, amount: Float) {
        if (turn === t) t.amount = amount.coerceIn(0f, 1f)
    }

    fun release(scope: CoroutineScope, t: Turn, commit: Boolean) = settle(scope, t, commit)

    fun tap(scope: CoroutineScope, forward: Boolean) {
        begin(forward, horizontal = true)?.let { settle(scope, it, commit = true) }
    }

    /** Shows [page] straight away (a restore). */
    fun show(page: Int) {
        finishNow()
        current = page.coerceIn(0, maxOf(0, count - 1))
    }

    /** Jumps to [page] as the reader's own move (the slider). */
    fun jump(page: Int) {
        show(page)
        onTurned(current)
    }

    private fun settle(scope: CoroutineScope, t: Turn, commit: Boolean) {
        if (turn !== t) return
        t.committing = commit
        val target = if (commit) 1f else 0f
        val ms = (TURN_MS * abs(target - t.amount)).toInt().coerceAtLeast(MIN_SETTLE_MS)
        settling = scope.launch {
            try {
                animate(t.amount, target, animationSpec = tween(ms, easing = TurnEasing)) { v, _ -> t.amount = v }
            } finally {
                end(t)
            }
        }
    }

    /** Completes (or drops) the turn in progress at once, e.g. when another one starts. */
    private fun finishNow() {
        val t = turn ?: return
        end(t)
        settling?.cancel()
        settling = null
    }

    private fun end(t: Turn) {
        if (turn !== t) return
        turn = null
        if (t.committing) {
            current = t.target
            onTurned(current)
        }
    }

    private companion object {
        /** A whole turn after a tap. Unhurried, like turning a real page. */
        const val TURN_MS = 620
        const val MIN_SETTLE_MS = 200
        /** Lifts off gently, sweeps across, settles softly. */
        val TurnEasing = CubicBezierEasing(0.35f, 0f, 0.25f, 1f)
    }
}

/**
 * Draws the page being turned as a sheet folding over: the part of the page still lying flat,
 * the folded-over flap showing the back of the page (the print faintly through the paper), and
 * the next page uncovered beyond the fold, with shadows along the fold and under the flap.
 *
 * Turning back plays the same fold in reverse, with the previous page settling down over this one.
 */
internal fun DrawScope.drawTurn(turns: PageTurns, paper: Color, drawPage: DrawScope.(Int) -> Unit) {
    val t = turns.turn
    if (t == null) {
        drawPage(turns.current)
        return
    }
    // The sheet that moves is the earlier page; it lies over the later one.
    val sheet = if (t.forward) turns.current else t.target
    val under = if (t.forward) t.target else turns.current
    val p = if (t.forward) t.amount else 1f - t.amount // how far the sheet is turned away
    val h = t.horizontal
    val extent = if (h) size.width else size.height
    val fold = extent * (1f - p)
    val flapStart = extent * (1f - 2f * p)
    val lift = sin(PI * p).toFloat() // shadows are strongest mid-turn

    drawPage(under)
    // The fold's shadow falling on the uncovered page.
    val foldShadow = 48.dp.toPx() * (0.4f + lift)
    drawRect(
        Brush.linearGradient(
            listOf(Color.Black.copy(alpha = 0.32f * lift), Color.Transparent),
            start = along(h, fold),
            end = along(h, fold + foldShadow),
        ),
        topLeft = along(h, fold),
        size = span(h, foldShadow),
    )

    // The part of the sheet still lying flat.
    clipRect(right = if (h) fold else size.width, bottom = if (h) size.height else fold) { drawPage(sheet) }

    if (flapStart > -extent) {
        // The flap's shadow on the flat part of the sheet.
        val flapShadow = 18.dp.toPx() * (0.5f + lift)
        val shadowEnd = flapStart.coerceAtLeast(0f)
        if (shadowEnd > 0f) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f * lift)),
                    start = along(h, shadowEnd - flapShadow),
                    end = along(h, shadowEnd),
                ),
                topLeft = along(h, shadowEnd - flapShadow),
                size = span(h, flapShadow),
            )
        }
        // The flap: the back of the sheet, mirrored about the fold.
        val left = if (h) maxOf(0f, flapStart) else 0f
        val top = if (h) 0f else maxOf(0f, flapStart)
        clipRect(left = left, top = top, right = if (h) fold else size.width, bottom = if (h) size.height else fold) {
            withTransform({
                if (h) scale(-1f, 1f, pivot = Offset(fold, 0f)) else scale(1f, -1f, pivot = Offset(0f, fold))
            }) { drawPage(sheet) }
            drawRect(paper.copy(alpha = 0.86f))
            // Curved paper: brighter where it's lifted, darker into the fold.
            drawRect(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.20f * (0.5f + lift))),
                    start = along(h, flapStart),
                    end = along(h, fold),
                ),
            )
        }
    }
}

private fun DrawScope.along(horizontal: Boolean, at: Float) = if (horizontal) Offset(at, 0f) else Offset(0f, at)
private fun DrawScope.span(horizontal: Boolean, length: Float) =
    if (horizontal) Size(length, size.height) else Size(size.width, length)

/**
 * Page gestures: drag left or up to turn forward, right or down to turn back (the page follows the
 * finger and finishes, or falls back, on release). Tap the left third to go back, the right third
 * to go forward, and the middle to show the reading controls. While they're up ([controlsShown]),
 * any tap or turn hides them ([onMenuTap]).
 */
internal fun Modifier.pageGestures(
    turns: PageTurns,
    scope: CoroutineScope,
    controlsShown: () -> Boolean,
    onMenuTap: () -> Unit,
) = pointerInput(turns) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var turn: PageTurns.Turn? = null
        var horizontal = true
        var moved = false
        var multiTouch = false
        var up: PointerInputChange? = null
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(change.uptimeMillis, change.position)
                val d = change.position - down.position
                if (!moved && (abs(d.x) > slop || abs(d.y) > slop)) {
                    moved = true
                    if (!multiTouch) {
                        horizontal = abs(d.x) >= abs(d.y)
                        if (controlsShown()) onMenuTap()
                        turn = turns.begin(forward = if (horizontal) d.x < 0 else d.y < 0, horizontal = horizontal)
                    }
                }
                turn?.let { t ->
                    change.consume()
                    val along = if (horizontal) d.x / size.width else d.y / size.height
                    turns.drag(t, if (t.forward) -along else along)
                }
                if (!change.pressed) {
                    up = change
                    break
                }
            }
        } finally {
            turn?.let { t ->
                val v = velocity.calculateVelocity()
                val speed = (if (horizontal) v.x else v.y).let { if (t.forward) -it else it }
                val fling = FLING.toPx()
                val commit = up != null && when {
                    speed > fling -> true
                    speed < -fling -> false
                    else -> t.amount > 0.3f
                }
                turns.release(scope, t, commit)
            }
        }
        val end = up ?: return@awaitEachGesture
        if (turn != null || moved || multiTouch) return@awaitEachGesture
        if (end.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
        when {
            controlsShown() -> onMenuTap()
            down.position.x < size.width / 3f -> turns.tap(scope, forward = false)
            down.position.x > size.width * 2f / 3f -> turns.tap(scope, forward = true)
            else -> onMenuTap()
        }
    }
}

/** A fling this fast (per second) decides a turn whatever the distance dragged. */
private val FLING = 350.dp

/**
 * Calls [onTap] for a quick tap anywhere, without consuming anything, so scrolling and text
 * selection work as usual. A tap while text is selected only clears the selection.
 */
internal fun Modifier.readerTaps(toolbar: TextToolbar?, onTap: () -> Unit) = pointerInput(toolbar) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val selecting = toolbar?.status == TextToolbarStatus.Shown
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
            if (!change.pressed) {
                val quick = change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                if (quick && !selecting) onTap()
                return@awaitEachGesture
            }
        }
    }
}
