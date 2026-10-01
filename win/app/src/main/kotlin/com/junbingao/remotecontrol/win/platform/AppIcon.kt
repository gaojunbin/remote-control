package com.junbingao.remotecontrol.win.platform

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Image
import java.awt.image.BaseMultiResolutionImage
import javax.imageio.ImageIO

/**
 * The app's icon — the Mac app's, drawn at every size Windows asks for from the same vectors —
 * for the window and the notification area. The installer's `.ico` holds the same pictures.
 */
object AppIcon {
    private val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 256)

    private fun image(size: Int): java.awt.image.BufferedImage =
        AppIcon::class.java.getResourceAsStream("/icon/icon-$size.png").use { ImageIO.read(it) }

    /** The window's icon, from the largest picture. */
    val painter: BitmapPainter by lazy { BitmapPainter(image(256).toComposeImageBitmap()) }

    /** Every size, so the notification area picks the one for its display's scale. */
    val trayImage: Image by lazy { BaseMultiResolutionImage(*sizes.map(::image).toTypedArray()) }
}
