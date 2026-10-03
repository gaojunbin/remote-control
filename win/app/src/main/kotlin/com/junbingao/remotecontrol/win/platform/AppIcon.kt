package com.junbingao.remotecontrol.win.platform

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Image
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The app's icon — the Mac app's, drawn at every size Windows asks for from the same vectors —
 * for the window and the notification area, and the notification area's with the badge (A47). The
 * installer's `.ico` holds the same pictures.
 */
object AppIcon {
    private val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 256)

    private fun image(size: Int): BufferedImage =
        AppIcon::class.java.getResourceAsStream("/icon/icon-$size.png").use { ImageIO.read(it) }

    /** The window's icon, from the largest picture. */
    val painter: BitmapPainter by lazy { BitmapPainter(image(256).toComposeImageBitmap()) }

    private val pictures: List<BufferedImage> by lazy { sizes.map(::image) }

    /** Every size, so the notification area picks the one for its display's scale. */
    val trayImage: Image by lazy { BaseMultiResolutionImage(*pictures.toTypedArray()) }

    /**
     * The same, with the red disc and `label` over its lower trailing corner (A47): what the
     * notification area shows while the window, and with it the taskbar button, is closed.
     */
    fun trayImage(label: String): Image = BaseMultiResolutionImage(*pictures.map { badged(it, label) }.toTypedArray())

    /** Ten sixteenths of the icon, the part of it Windows gives an overlay. */
    private fun badged(icon: BufferedImage, label: String): BufferedImage {
        val size = icon.width
        val disc = (size * 10 + 8) / 16
        val picture = BufferedImage(size, icon.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = picture.createGraphics()
        try {
            graphics.drawImage(icon, 0, 0, null)
            graphics.drawImage(BadgeImage.disc(label, disc), size - disc, icon.height - disc, null)
        } finally {
            graphics.dispose()
        }
        return picture
    }
}
