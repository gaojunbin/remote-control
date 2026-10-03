package com.junbingao.remotecontrol.win.platform

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.image.BufferedImage

/**
 * The red disc with the number that Windows lays over the app's icon (A47, `docs/DESIGN.md` § "A
 * red dot for a session that stopped and waits for you" → Windows): the Danger red, the number in
 * white and bold, centred on its own ink, and "99+" past two figures — as much as a disc the size of
 * a small icon holds legibly, and what Windows' own badges say there.
 */
object BadgeImage {
    /** `--danger`. */
    private val danger = Color(0xD2, 0x3F, 0x31)

    /** What the disc says for the badge's `label`. */
    fun text(label: String): String = if (label.length > 2) "99+" else label

    /** A `size` × `size` picture of the disc, transparent around it. */
    fun disc(label: String, size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            graphics.color = danger
            graphics.fillOval(0, 0, size, size)
            val text = text(label)
            // One figure fills the disc as Windows' own badges do; more of them take a smaller size.
            val scale = when (text.length) {
                1 -> 0.68f
                2 -> 0.56f
                else -> 0.42f
            }
            val layout = TextLayout(text, Font("Segoe UI", Font.BOLD, 1).deriveFont(size * scale), graphics.fontRenderContext)
            val ink = layout.bounds
            graphics.color = Color.WHITE
            layout.draw(graphics, (size - ink.width.toFloat()) / 2 - ink.x.toFloat(), (size - ink.height.toFloat()) / 2 - ink.y.toFloat())
        } finally {
            graphics.dispose()
        }
        return image
    }
}
