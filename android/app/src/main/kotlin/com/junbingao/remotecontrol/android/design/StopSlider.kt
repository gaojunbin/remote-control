package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.haptics.selectionFeedback
import kotlin.math.roundToInt

/**
 * The effort slider from `docs/DESIGN.md` § "The model card": a thick pill track filled to the
 * thumb in the accent colour, one small dot at every stop so the number of levels is visible
 * before the thumb moves, and a white disc that snaps to the stops. Nothing else is drawn — no
 * numbers, no labels under the track.
 *
 * [index] follows the thumb while it is moving, which is what the word beside the model name
 * reads, and [onIndex] reports each stop it crosses. [onCommit] fires once, when the thumb is let
 * go, so dragging across four levels is one request rather than four. A tap anywhere on the track
 * moves to the nearest stop and commits it; each stop crossed gives one selection tick, so the
 * levels can be counted without looking.
 */
@Composable
fun StopSlider(
    stops: Int,
    index: Int,
    value: String,
    onIndex: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val quiet = Theme.quietFill
    val accent = Theme.accent
    val tertiary = Theme.inkTertiary
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(StopSliderGeometry.trackHeight)
            .selectionFeedback(index)
            // One adjustable element, as VoiceOver reaches the iPhone's: TalkBack moves it a stop
            // at a time and reads the effort word as its value.
            .semantics {
                stateDescription = value
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = index.toFloat(),
                    range = 0f..(stops - 1).coerceAtLeast(1).toFloat(),
                    steps = (stops - 2).coerceAtLeast(0),
                )
                setProgress { target -> adjust(target.roundToInt(), stops, onIndex, onCommit) }
            },
    ) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val thumb = with(density) { StopSliderGeometry.thumbSize.toPx() }
        val travel = (width - thumb).coerceAtLeast(0f)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(StopSliderGeometry.trackHeight)
                .pointerInput(stops, travel) {
                    // Zero minimum distance so a tap on a stop is a drag that begins and ends
                    // there: one gesture covers both ways of moving.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var landed = StopSliderGeometry.stop(down.position.x, travel, thumb, stops)
                        onIndex(landed)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            landed = StopSliderGeometry.stop(change.position.x, travel, thumb, stops)
                            onIndex(landed)
                            if (change.positionChange() != Offset.Zero) change.consume()
                            if (!change.pressed) break
                        }
                        onCommit(landed)
                    }
                },
        ) {
            val height = size.height
            val step = StopSliderGeometry.step(travel, stops)
            val centre = thumb / 2 + step * index
            val radius = CornerRadius(height / 2)
            drawRoundRect(quiet, size = size, cornerRadius = radius)
            drawRoundRect(accent, size = Size(centre, height), cornerRadius = radius)
            val dot = StopSliderGeometry.dotSize.toPx()
            for (stop in 0 until stops.coerceAtLeast(0)) {
                val x = thumb / 2 + step * stop
                // The dots on the filled part are white so they stay visible on the accent.
                val color = if (x <= centre) Color.White.copy(alpha = 0.6f) else tertiary
                drawCircle(color, dot / 2, Offset(x, height / 2))
            }
        }
        val thumbOffset = with(density) { (thumb / 2 + StopSliderGeometry.step(travel, stops) * index - thumb / 2).toDp() }
        Canvas(
            Modifier
                .padding(start = thumbOffset, top = (StopSliderGeometry.trackHeight - StopSliderGeometry.thumbSize) / 2)
                .size(StopSliderGeometry.thumbSize)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f)),
        ) {
            drawCircle(Color.White)
        }
    }
}

private fun adjust(moved: Int, stops: Int, onIndex: (Int) -> Unit, onCommit: (Int) -> Unit): Boolean {
    if (moved < 0 || moved >= stops) return false
    onIndex(moved)
    onCommit(moved)
    return true
}

/** The slider's measurements and the arithmetic that snaps a touch to a stop. */
object StopSliderGeometry {
    val trackHeight = 28.dp
    val thumbSize = 24.dp
    val dotSize = 6.dp

    fun step(travel: Float, stops: Int): Float = if (stops > 1) travel / (stops - 1) else 0f

    /** The stop nearest [x], where [thumb] is the thumb's width and [travel] the track less it. */
    fun stop(x: Float, travel: Float, thumb: Float, stops: Int): Int {
        if (stops <= 1 || travel <= 0f) return 0
        val fraction = (x - thumb / 2) / travel
        return (fraction * (stops - 1)).roundToInt().coerceIn(0, stops - 1)
    }
}
