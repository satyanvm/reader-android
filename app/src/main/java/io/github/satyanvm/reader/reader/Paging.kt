package io.github.satyanvm.reader.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    /** The screen as drawn, so a turn can slide the old screen out while the new one slides in. */
    var layer: GraphicsLayer? = null
    var width = 0f
    /** Horizontal offset of the live screen during a turn. */
    val slide = Animatable(0f)
    /** The screen being turned away from, drawn beside the live one while it slides. */
    var leaving by mutableStateOf<ImageBitmap?>(null)
        private set
    private var direction = 1
    val leavingX: Float get() = slide.value - direction * width

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

    suspend fun next() = turn {
        if (atEnd()) return@turn
        history.addLast(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        val step = cut() ?: height
        animated(forward = true) { list.scrollBy(step) }
    }

    suspend fun previous() = turn {
        val back = history.removeLastOrNull()
        if (back == null && !list.canScrollBackward) return@turn
        animated(forward = false) {
            if (back != null) {
                list.scrollToItem(back.first, back.second)
            } else {
                // No screen to return to (e.g. after opening here): go back a screen and start on
                // the next whole line, so nothing is skipped.
                list.scrollBy(-height)
                awaitLayout()
                straddlingTop()?.let { list.scrollBy(it.endInclusive) }
            }
        }
    }

    /**
     * Runs [move] behind a slide: a picture of the current screen moves out one side while the
     * live list, already moved to the new screen, comes in from the other. The live list is put
     * off-screen before it moves, so the new screen never flashes up early.
     */
    private suspend fun animated(forward: Boolean, move: suspend () -> Unit) {
        val snapshot = layer?.takeIf { width > 0f }?.let { runCatching { it.toImageBitmap() }.getOrNull() }
        if (snapshot == null) {
            move()
            return
        }
        direction = if (forward) 1 else -1
        leaving = snapshot
        try {
            slide.snapTo(direction * width)
            move()
            slide.animateTo(0f, tween(TURN_MS, easing = FastOutSlowInEasing))
        } finally {
            slide.snapTo(0f)
            leaving = null
        }
    }

    /** After a jump or restore: start the screen on the line the position falls in. */
    suspend fun alignTop() {
        history.clear()
        awaitLayout()
        straddlingTop()?.let { list.scrollBy(it.start) }
    }

    private fun straddlingTop() = spans().firstOrNull { it.start < -EPS && it.endInclusive > EPS }

    private suspend inline fun turn(block: () -> Unit) {
        if (busy) return
        busy = true
        try {
            block()
        } finally {
            busy = false
        }
    }

    private companion object {
        const val EPS = 0.5f
        const val TURN_MS = 280
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
 * The paged screen: records what's drawn and slides it during a turn, with the picture of the
 * previous screen beside it.
 */
@Composable
internal fun PagerScreen(pager: Pager, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    SideEffect { pager.layer = layer }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { pager.width = it.width.toFloat() }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = pager.slide.value }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) { content() }
        pager.leaving?.let { picture ->
            Canvas(Modifier.matchParentSize().graphicsLayer { translationX = pager.leavingX }) {
                drawImage(picture)
            }
        }
    }
}

/**
 * Tap the left third to go back and anywhere else to go forward; swipe left/right to turn.
 * Watches gestures without consuming them, so long-press text selection still works. While text
 * is selected (its toolbar is up) a tap only clears the selection.
 */
internal fun Modifier.pageTurns(pager: Pager, scope: CoroutineScope, toolbar: TextToolbar) = pointerInput(pager, toolbar) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val selecting = toolbar.status == TextToolbarStatus.Shown
        var up: PointerInputChange? = null
        var multiTouch = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                up = change
                break
            }
        }
        val end = up ?: return@awaitEachGesture
        if (multiTouch) return@awaitEachGesture
        val dx = end.position.x - down.position.x
        val dy = end.position.y - down.position.y
        val slop = viewConfiguration.touchSlop
        val forward = when {
            abs(dx) > slop * 3 && abs(dx) > abs(dy) -> dx < 0
            !selecting && abs(dx) < slop && abs(dy) < slop &&
                end.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis ->
                down.position.x >= size.width / 3f
            else -> return@awaitEachGesture
        }
        scope.launch { if (forward) pager.next() else pager.previous() }
    }
}
