package com.junbingao.remotecontrol.win.platform

import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The red disc Windows lays over the app's icon (A47): what it says, its colour, and where it sits on the notification-area icon. */
class BadgeImageTests {
    private val danger = 0xD23F31

    private fun rgb(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) and 0xFFFFFF

    private fun alpha(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    @Test
    fun theDiscSaysTheNumberAndNinetyNinePlusPastTwoFigures() {
        assertEquals("7", BadgeImage.text("7"))
        assertEquals("42", BadgeImage.text("42"))
        assertEquals("99+", BadgeImage.text("120"))
    }

    @Test
    fun theDiscIsTheDangerRedWithWhiteFigures() {
        val disc = BadgeImage.disc("8", 32)
        assertEquals(32, disc.width)
        assertEquals(0, alpha(disc, 0, 0), "its corners are clear")
        assertEquals(danger, rgb(disc, 16, 3), "inside its rim it is the Danger red")
        val white = (0 until 32).sumOf { x -> (0 until 32).count { y -> alpha(disc, x, y) == 255 && rgb(disc, x, y) == 0xFFFFFF } }
        assertTrue(white > 10, "and the figure is drawn in white ($white px)")
    }

    @Test
    fun theNotificationAreaCarriesTheDiscInItsLowerCorner() {
        val badged = AppIcon.trayImage("2") as BaseMultiResolutionImage
        val plain = AppIcon.trayImage as BaseMultiResolutionImage
        assertEquals(plain.resolutionVariants.map { it.getWidth(null) }, badged.resolutionVariants.map { it.getWidth(null) },
                     "at every size the notification area picks from")
        val small = badged.resolutionVariants.first() as BufferedImage
        val size = small.width
        // Beside the figure, inside the rim: the disc is ten sixteenths of the icon, in its lower trailing corner.
        assertEquals(danger, rgb(small, size - 2, size - 5), "the disc is in the lower trailing corner")
        val original = plain.resolutionVariants.first() as BufferedImage
        assertEquals(original.getRGB(1, 1), small.getRGB(1, 1), "and the rest of the icon is untouched")
    }
}
