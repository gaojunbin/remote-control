package com.junbingao.remotecontrol.win.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A window of the app's size that is never on a screen: Compose's `ImageComposeScene`, which lays
 * out and draws the same composition a window does, through Skia, into an image. It runs on the
 * renderer's one thread, and frames are drawn on the clock as a window would draw them — while a
 * scenario prepares as well as while it settles, as the Mac renderer's window keeps refreshing —
 * so animations run their course and what a view starts when it is next composed — a delayed
 * opening, a form filled in, Add device's request for a code — lands before the picture is taken.
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
    private var frames = 0

    /** Draw a frame now. */
    fun frame() {
        scene.render(started.elapsedNow().inWholeNanoseconds)
        frames++
    }

    /** Run `work` — a scenario's preparation — with a frame drawn every 16 ms while it waits. */
    suspend fun <T> drawing(work: suspend () -> T): T = coroutineScope {
        val drawer = launch {
            while (true) {
                frame()
                delay(16)
            }
        }
        try {
            work()
        } finally {
            drawer.cancel()
        }
    }

    /**
     * Keep drawing, a frame every 16 ms, for `duration`: the time the Mac renderer leaves its window
     * before it takes the picture, and no longer — a spinner never stops asking for frames, and the
     * demo's handshake and every clock on the page go on meanwhile, so a later picture would show a
     * later moment than the Mac's. But never before the scene has drawn `minimumFrames`: the Mac's
     * window draws sixty a second, so what a page starts on its first frames — a staged dialog that
     * opens, registers, then rises — is on screen within its first tenth of a second, where a frame
     * here behind a modal's blur takes as long as a tenth of a second by itself.
     */
    suspend fun settle(duration: Duration) {
        val until = started.elapsedNow() + duration
        while (started.elapsedNow() < until || frames < minimumFrames) {
            frame()
            delay(16)
        }
    }

    /** The last frame as PNG. */
    fun png(): ByteArray {
        val image = scene.render(started.elapsedNow().inWholeNanoseconds)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("the picture could not be encoded as PNG")
    }

    private companion object {
        /** The Mac window's first tenth of a second. */
        const val minimumFrames = 6
    }

    override fun close() {
        scene.close()
    }
}
