package com.junbingao.remotecontrol.win.unseen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The red dot (A47): 8 px of the Danger red, centred in the row's leading gutter and on the title's
 * line, drawn rather than laid out, so it moves nothing when it comes or goes.
 */
class UnseenDotTests {
    private class Drawn(val pixels: PixelMap, val titleX: Float)

    /** A 20 px gutter and a one-line title 20 px tall after it, 10 px down a white row. */
    private fun draw(unseen: Boolean): Drawn {
        var titleX = -1f
        val scene = ImageComposeScene(200, 40, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(
                    Modifier
                        .padding(start = 20.dp, top = 10.dp)
                        .size(100.dp, 20.dp)
                        .unseenTitle(unseen, reach = 20.dp, gutter = 20.dp)
                        .onGloballyPositioned { titleX = it.positionInRoot().x },
                )
            }
        }
        val pixels = scene.render().toComposeImageBitmap().toPixelMap()
        scene.close()
        return Drawn(pixels, titleX)
    }

    @Test
    fun theDotSitsInTheGutterOnTheTitlesLine() {
        val drawn = draw(unseen = true)
        assertEquals(Palette.danger.toArgb(), drawn.pixels[10, 20].toArgb(), "centred in the gutter, on the line's middle")
        assertEquals(Color.White.toArgb(), drawn.pixels[10, 14].toArgb(), "8 px across, so 6 px above its centre is the row")
        assertEquals(Color.White.toArgb(), drawn.pixels[4, 20].toArgb())
        assertEquals(Color.White.toArgb(), drawn.pixels[25, 20].toArgb(), "and the title's own space is untouched")
    }

    @Test
    fun itMovesNothingWhenItComesOrGoes() {
        val marked = draw(unseen = true)
        val quiet = draw(unseen = false)
        assertEquals(quiet.titleX, marked.titleX, "the title starts where it did")
        assertNotEquals(Palette.danger.toArgb(), quiet.pixels[10, 20].toArgb(), "and without the mark there is no dot")
    }
}
