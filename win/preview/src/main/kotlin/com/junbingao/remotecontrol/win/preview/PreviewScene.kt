package com.junbingao.remotecontrol.win.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A window of the app's size that is never on a screen: Compose's `ImageComposeScene`, which lays
 * out and draws the same composition a window does, through Skia, into an image. It runs on the
 * renderer's one thread, and frames are drawn on the clock as a window would draw them, so
 * animations run their course and what a view launches — a delayed opening, a form filled in —
 * lands before the picture is taken.
 *
 * What it cannot show: the pointer's hover states and a text field's blinking caret, which the
 * renderer turns off so the picture compares with the Mac renderer's, which cannot show it either.
 */
class PreviewScene(
    width: Int,
    height: Int,
    scale: Int,
    context: CoroutineContext,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val started = TimeSource.Monotonic.markNow()
    private val scene = ImageComposeScene(width * scale, height * scale, Density(scale.toFloat()), coroutineContext = context, content = content)

    /** Draw a frame now. */
    fun frame() {
        scene.render(started.elapsedNow().inWholeNanoseconds)
    }

    /**
     * Keep drawing, a frame every 16 ms, for `duration`, and then until nothing is left to draw —
     * a frame that took long (a face loaded for the first time) can leave an animation or a focus
     * move half done at the deadline — for at most a second more, which is what a spinner that
     * never stops costs.
     */
    suspend fun settle(duration: Duration) {
        val until = started.elapsedNow() + duration
        while (started.elapsedNow() < until) {
            frame()
            delay(16)
        }
        val limit = started.elapsedNow() + 1.seconds
        while (scene.hasInvalidations() && started.elapsedNow() < limit) {
            frame()
            delay(16)
        }
    }

    /** The last frame as PNG. */
    fun png(): ByteArray {
        val image = scene.render(started.elapsedNow().inWholeNanoseconds)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("the picture could not be encoded as PNG")
    }

    override fun close() {
        scene.close()
    }
}
