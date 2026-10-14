package io.github.satyanvm.reader.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Window-by-window reading (like Play Books) on top of the flowing-text list. The list doesn't
 * scroll freely: a tap or swipe moves it by one screen, and every screen starts and ends on a
 * whole line. Lines are found from the text layouts of the items on screen, so the content is
 * still one list and positions are tracked exactly as when scrolling continuously.
 */
internal class Pager(private val list: LazyListState) {
    /** Something on screen that mustn't be split across two screens: a text block or an image. */
    internal class Entry(val item: Int) {
        var coords: LayoutCoordinates? = null
        /** For text, each line can start a screen; otherwise the whole entry is one unit. */
        var text: TextLayoutResult? = null
    }

    private val entries = LinkedHashSet<Entry>()
    /** The list's own coordinates; positions are measured from its top. */
    var viewport: LayoutCoordinates? = null
    /** Where earlier screens started, so "back" returns to exactly the previous screen. */
    private val history = ArrayDeque<Pair<Int, Int>>()
    private var busy = false

    /** The screen as drawn, so a turn can show the old screen while the new one is underneath. */
    var layer: GraphicsLayer? = null
    var size = IntSize.Zero

    /** The screen being turned away from, drawn beside the live one during a turn. */
    var leaving by mutableStateOf<ImageBitmap?>(null)
        private set
    /** How far the current turn has gone, 0 (not started) to 1 (done). Follows the finger while dragging. */
    private var progress by mutableFloatStateOf(0f)
    /** The live list has moved to the new screen, so the turn can be shown. */
    private var ready by mutableStateOf(false)
    var forward by mutableStateOf(true)
        private set
    var horizontal by mutableStateOf(true)
        private set
    /** Where the list was before the turn, and the history entry it used, to undo a cancelled turn. */
    private var from = 0 to 0
    private var usedBack: Pair<Int, Int>? = null
    private var prepared: Job? = null

    private val shown: Float get() = if (ready) progress else 0f
    private val extent: Float get() = (if (horizontal) size.width else size.height).toFloat()

    /**
     * Like a stack of pages: the earlier page lies on top. Turning forward, the old screen (a
     * picture) moves off and uncovers the new one; turning back, the earlier screen (the live
     * list) moves in over the picture.
     */
    val liveOnTop: Boolean get() = !forward
    val liveOffset: Float get() = if (leaving == null || forward) 0f else -(1 - shown) * extent
    val leavingOffset: Float get() = if (forward) -shown * extent else 0f
    /** The leading edge of the page on top, measured along the turn: everything past it is uncovered. */
    val edge: Float get() = (if (forward) leavingOffset else liveOffset) + extent

    fun add(entry: Entry) = entries.add(entry)
    fun remove(entry: Entry) = entries.remove(entry)

    private val height: Float get() = list.layoutInfo.viewportSize.height.toFloat()

    /** Unbreakable runs on screen (a line of text, an image) as top..bottom px from the viewport top. */
    private fun spans(): List<ClosedFloatingPointRange<Float>> {
        val root = viewport?.takeIf { it.isAttached } ?: return emptyList()
        val visible = list.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.index }
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        for (e in entries) {
            if (e.item !in visible) continue
            val c = e.coords?.takeIf { it.isAttached } ?: continue
            val y = root.localPositionOf(c, Offset.Zero).y
            val t = e.text
            if (t == null) {
                out += y..(y + c.size.height)
            } else {
                for (i in 0 until t.lineCount) out += (y + t.getLineTop(i))..(y + t.getLineBottom(i))
            }
        }
        return out
    }

    /** Where the line cut off by the bottom edge starts, if any. The screen ends there. */
    fun cut(): Float? {
        val h = height
        return spans()
            .filter { it.start > EPS && it.start < h - EPS && it.endInclusive > h + EPS }
            .minOfOrNull { it.start }
    }

    private fun atEnd(): Boolean {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        return last.index == info.totalItemsCount - 1 && last.offset + last.size <= height + EPS
    }

    /** Tap to turn: the whole turn animates on its own. */
    suspend fun next() = turn(forward = true)
    suspend fun previous() = turn(forward = false)

    private suspend fun turn(forward: Boolean) {
        if (busy) return
        busy = true
        try {
            if (prepare(forward, horizontal = true)) finish(commit = true)
        } finally {
            reset()
        }
    }

    /** A drag has begun turning the page; [dragTo] moves it, [release] lets go. False if it can't turn. */
    fun beginDrag(scope: CoroutineScope, forward: Boolean, horizontal: Boolean): Boolean {
        if (busy) return false
        busy = true
        progress = 0f
        prepared = scope.launch { if (!prepare(forward, horizontal)) reset() }
        return true
    }

    /** [fraction] of the screen the finger has moved in the turning direction. */
    fun dragTo(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
    }

    fun release(scope: CoroutineScope, commit: Boolean) {
        val job = prepared ?: return
        prepared = null
        scope.launch {
            try {
                job.join()
                if (ready) finish(commit)
            } finally {
                reset()
            }
        }
    }

    /**
     * Pictures the current screen and moves the live list to the new one behind it, without
     * showing anything yet (the picture covers the list, or the list is held off-screen).
     */
    private suspend fun prepare(forward: Boolean, horizontal: Boolean): Boolean {
        if (forward && atEnd()) return false
        val back = if (forward) null else history.removeLastOrNull()
        if (!forward && back == null && !list.canScrollBackward) return false
        from = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        usedBack = back
        this.forward = forward
        this.horizontal = horizontal
        leaving = layer?.takeIf { size.width > 0 && size.height > 0 }
            ?.let { runCatching { it.toImageBitmap() }.getOrNull() }
        if (forward) {
            history.addLast(from)
            list.scrollBy(cut() ?: height)
        } else if (back != null) {
            list.scrollToItem(back.first, back.second)
        } else {
            // No screen to return to (e.g. after opening here): go back a screen and start on
            // the next whole line, so nothing is skipped.
            list.scrollBy(-height)
            awaitLayout()
            straddlingTop()?.let { list.scrollBy(it.endInclusive) }
        }
        ready = true
        return true
    }

    /** Completes the turn, or undoes it and returns to where it started. */
    private suspend fun finish(commit: Boolean) {
        if (leaving != null) {
            val target = if (commit) 1f else 0f
            val ms = (TURN_MS * abs(target - progress)).toInt().coerceAtLeast(MIN_SETTLE_MS)
            animate(progress, target, animationSpec = tween(ms, easing = FastOutSlowInEasing)) { v, _ -> progress = v }
        }
        if (!commit) {
            if (forward) history.removeLastOrNull() else usedBack?.let { history.addLast(it) }
            list.scrollToItem(from.first, from.second)
            awaitLayout()
        }
    }

    private fun reset() {
        leaving = null
        ready = false
        progress = 0f
        usedBack = null
        busy = false
    }

    /** After a jump or restore: start the screen on the line the position falls in. */
    suspend fun alignTop() {
        history.clear()
        awaitLayout()
        straddlingTop()?.let { list.scrollBy(it.start) }
    }

    private fun straddlingTop() = spans().firstOrNull { it.start < -EPS && it.endInclusive > EPS }

    private companion object {
        const val EPS = 0.5f
        const val TURN_MS = 340
        const val MIN_SETTLE_MS = 120
    }
}

/** Lets new items lay out (and report where their lines are). */
private suspend fun awaitLayout() {
    withFrameNanos { }
    withFrameNanos { }
}

/** Registers something in list item [item] that must stay whole on one screen. */
@Composable
internal fun rememberPagerEntry(pager: Pager?, item: Int): Pager.Entry? {
    pager ?: return null
    val entry = remember(pager, item) { Pager.Entry(item) }
    DisposableEffect(pager, entry) {
        pager.add(entry)
        onDispose { pager.remove(entry) }
    }
    return entry
}

internal fun Modifier.pagerEntry(entry: Pager.Entry?) =
    if (entry == null) this else onGloballyPositioned { entry.coords = it }

/** The list's viewport: hides a line cut off at the bottom; it starts the next screen. */
internal fun Modifier.pagerViewport(pager: Pager, background: Color) = this
    .onGloballyPositioned { pager.viewport = it }
    .drawWithContent {
        drawContent()
        pager.cut()?.let { top ->
            drawRect(background, topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
        }
    }

/**
 * The paged screen: records what's drawn, and during a turn shows the old and new screens as two
 * sheets, the top one sliding over the other with a shadow along its edge.
 */
@Composable
internal fun PagerScreen(pager: Pager, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    SideEffect { pager.layer = layer }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { pager.size = it }) {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(if (pager.liveOnTop) 1f else 0f)
                .graphicsLayer {
                    val o = pager.liveOffset
                    if (pager.horizontal) translationX = o else translationY = o
                }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) { content() }
        pager.leaving?.let { picture ->
            Canvas(
                Modifier
                    .matchParentSize()
                    .zIndex(if (pager.liveOnTop) 0f else 1f)
                    .graphicsLayer {
                        val o = pager.leavingOffset
                        if (pager.horizontal) translationX = o else translationY = o
                    },
            ) { drawImage(picture) }
            Canvas(Modifier.matchParentSize().zIndex(2f)) { drawTurnShade(pager) }
        }
    }
}

/** Dims the uncovered part of the lower page and casts a soft shadow from the top page's edge. */
private fun DrawScope.drawTurnShade(pager: Pager) {
    val extent = if (pager.horizontal) size.width else size.height
    val edge = pager.edge.coerceIn(0f, extent)
    if (extent <= 0f || edge >= extent) return
    val covered = edge / extent
    val shadow = SHADOW.toPx()
    val dim = Color.Black.copy(alpha = 0.18f * covered)
    val shade = Brush.linearGradient(
        colors = listOf(Color.Black.copy(alpha = 0.28f * covered), Color.Transparent),
        start = if (pager.horizontal) Offset(edge, 0f) else Offset(0f, edge),
        end = if (pager.horizontal) Offset(edge + shadow, 0f) else Offset(0f, edge + shadow),
    )
    if (pager.horizontal) {
        drawRect(dim, topLeft = Offset(edge, 0f), size = Size(extent - edge, size.height))
        drawRect(shade, topLeft = Offset(edge, 0f), size = Size(shadow, size.height))
    } else {
        drawRect(dim, topLeft = Offset(0f, edge), size = Size(size.width, extent - edge))
        drawRect(shade, topLeft = Offset(0f, edge), size = Size(size.width, shadow))
    }
}

private val SHADOW = 28.dp

/**
 * Page turns: drag the page left or up to turn forward, right or down to turn back; it follows
 * the finger and finishes (or springs back) on release. Tap the left third to go back, the right
 * third to go forward, and the middle to show or hide the reading controls ([onMenuTap]).
 * Gestures are watched without being consumed until a drag starts turning, so long-press text
 * selection still works. While text is selected (its toolbar is up) a tap only clears the selection.
 */
internal fun Modifier.pageTurns(
    pager: Pager,
    scope: CoroutineScope,
    toolbar: TextToolbar,
    onMenuTap: () -> Unit,
) = pointerInput(pager, toolbar) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val selecting = toolbar.status == TextToolbarStatus.Shown
        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var up: PointerInputChange? = null
        var multiTouch = false
        var dragging = false
        var forward = true
        var horizontal = true
        var fraction = 0f
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(change.uptimeMillis, change.position)
                val d = change.position - down.position
                if (!dragging && !selecting && !multiTouch &&
                    change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                    (abs(d.x) > slop * 2 || abs(d.y) > slop * 2)
                ) {
                    horizontal = abs(d.x) >= abs(d.y)
                    forward = if (horizontal) d.x < 0 else d.y < 0
                    dragging = pager.beginDrag(scope, forward, horizontal)
                    if (!dragging) return@awaitEachGesture
                }
                if (dragging) {
                    change.consume()
                    val along = if (horizontal) d.x / size.width else d.y / size.height
                    fraction = if (forward) -along else along
                    pager.dragTo(fraction)
                }
                if (!change.pressed) {
                    up = change
                    break
                }
            }
        } finally {
            if (dragging) {
                val v = velocity.calculateVelocity()
                val speed = (if (horizontal) v.x else v.y).let { if (forward) -it else it }
                val fling = FLING.toPx()
                val commit = up != null && when {
                    speed > fling -> true
                    speed < -fling -> false
                    else -> fraction > 0.35f
                }
                pager.release(scope, commit)
            }
        }
        if (dragging) return@awaitEachGesture
        val end = up ?: return@awaitEachGesture
        if (multiTouch || selecting) return@awaitEachGesture
        val d = end.position - down.position
        if (abs(d.x) >= slop || abs(d.y) >= slop ||
            end.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
        ) return@awaitEachGesture
        when {
            down.position.x < size.width / 3f -> scope.launch { pager.previous() }
            down.position.x > size.width * 2f / 3f -> scope.launch { pager.next() }
            else -> onMenuTap()
        }
    }
}

/** A fling this fast (per second) decides a turn whatever the distance dragged. */
private val FLING = 400.dp

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
