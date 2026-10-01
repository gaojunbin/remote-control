package com.junbingao.remotecontrol.android.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** `PhotoPreparation.jpeg` on images made here, decoded by the real graphics stack. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPreparationTest {
    @Test
    fun aLargePhotoIsSentAtMost2048PixelsOnItsLongestSide() {
        val sent = PhotoPreparation.jpeg(jpeg(4000, 3000))
        val size = bounds(sent)
        assertEquals(2048, size.first)
        assertEquals(1536, size.second)
    }

    @Test
    fun aPhotoThatFitsKeepsItsSize() {
        assertEquals(640 to 480, bounds(PhotoPreparation.jpeg(jpeg(640, 480))))
    }

    @Test
    fun whatIsSentIsAlwaysAJpegEvenFromAPng() {
        val png = ByteArrayOutputStream().also { canvas(300, 200).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val sent = PhotoPreparation.jpeg(png)
        assertTrue("JPEG starts with FF D8", sent[0] == 0xFF.toByte() && sent[1] == 0xD8.toByte())
    }

    @Test
    fun aPhotoIsTurnedUprightByItsOwnOrientation() {
        val file = File.createTempFile("rotated", ".jpg")
        try {
            file.writeBytes(jpeg(300, 200))
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            assertEquals(200 to 300, bounds(PhotoPreparation.jpeg(file.readBytes())))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theSourcesMetadataIsNotSent() {
        val file = File.createTempFile("located", ".jpg")
        try {
            file.writeBytes(jpeg(320, 240))
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            val sent = File.createTempFile("sent", ".jpg")
            try {
                sent.writeBytes(PhotoPreparation.jpeg(file.readBytes()))
                assertEquals(null, ExifInterface(sent.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            } finally {
                sent.delete()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun somethingThatIsNotAnImageIsSaidToBeUnreadable() {
        expect(PhotoPreparationError.InvalidImage) { PhotoPreparation.jpeg("not an image".toByteArray()) }
    }

    @Test
    fun anInputPastTheCapIsRefusedBeforeDecoding() {
        expect(PhotoPreparationError.ImageTooLarge) { PhotoPreparation.jpeg(ByteArray(PhotoPreparation.maxInputBytes + 1)) }
    }

    @Test
    fun theFitKeepsTheShapeAndNeverUpscales() {
        assertEquals(2048 to 1536, PhotoPreparation.fitted(4000, 3000))
        assertEquals(1536 to 2048, PhotoPreparation.fitted(3000, 4000))
        assertEquals(100 to 50, PhotoPreparation.fitted(100, 50))
        assertEquals(2048 to 1, PhotoPreparation.fitted(10000, 2))
    }

    private fun expect(error: PhotoPreparationError, work: () -> Unit) {
        try {
            work()
            fail("expected $error")
        } catch (thrown: PhotoPreparationError) {
            assertEquals(error, thrown)
        }
    }

    private fun canvas(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(34, 160, 107))
    }

    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { canvas(width, height).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun bounds(data: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        return options.outWidth to options.outHeight
    }
}
