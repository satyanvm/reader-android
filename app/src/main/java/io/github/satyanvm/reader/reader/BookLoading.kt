package io.github.satyanvm.reader.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.satyanvm.reader.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val MESSAGES = listOf(
    "Your book is being made…",
    "Wishing you a happy reading session",
    "Binding the pages…",
    "Dusting off the cover…",
    "Brewing a cup of tea for you…",
    "Teaching the letters to hold hands…",
    "Fluffing the bookmarks…",
    "Warming up the reading lamp…",
    "Smoothing out the dog-ears…",
    "Finding you a cozy chair…",
    "Shuffling sentences into place…",
    "Asking the commas to behave…",
    "Lining up the paragraphs…",
    "Almost there, turn the page soon…",
)

/**
 * How far along opening the book is, shared by every loading stage (opening the file, preparing
 * the first page's text, laying out pages) so the percentage only ever goes up. Stages that can't
 * measure themselves creep towards 95%; [report] moves it on when something real is known.
 */
internal class LoadingMeter {
    var percent by mutableFloatStateOf(0f)
        private set

    fun report(fraction: Float) {
        percent = maxOf(percent, (fraction * 100f).coerceIn(0f, 99f))
    }

    /** One tick of the creep: a slice of what's left, so it slows down but never stalls at zero. */
    fun creep() {
        percent = maxOf(percent, percent + (95f - percent) * 0.06f)
    }
}

internal val LocalLoadingMeter = compositionLocalOf { LoadingMeter() }

/** The book's loading screen: the Readserene mark, a friendly line that changes, and a percentage. */
@Composable
internal fun BookLoading(colors: ReaderColors, real: Float? = null) {
    val meter = LocalLoadingMeter.current
    LaunchedEffect(real) { real?.let(meter::report) }
    LaunchedEffect(meter) {
        while (true) {
            delay(120)
            meter.creep()
        }
    }
    var message by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // The first line is always "being made"; after that, a random one each time.
        while (true) {
            delay(2600)
            message = (MESSAGES.indices - message).random()
        }
    }
    val shown by animateFloatAsState(meter.percent, tween(300), label = "loading")

    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_readserene),
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(13.dp)),
            )
            AnimatedContent(
                targetState = message,
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
                label = "message",
            ) { i ->
                Text(
                    MESSAGES[i],
                    color = colors.text,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(48.dp),
                )
            }
            LinearProgressIndicator(
                progress = { shown / 100f },
                color = Brand,
                trackColor = colors.muted.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
                modifier = Modifier.width(220.dp).height(6.dp),
            )
            Text("${shown.roundToInt()}%", color = colors.muted, fontSize = 14.sp)
        }
    }
}

private val Brand = Color(0xFF7A5A3A)
