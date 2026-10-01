package com.junbingao.remotecontrol.android.attachments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every photo is re-encoded before it is attached, as `PhotoPreparation.jpeg` does on the
 * iPhone: decoded straight to at most 2048 pixels on its longest side, so a full-resolution raster
 * never sits in memory, turned upright by its own orientation, and written as JPEG at quality 86.
 * Re-encoding drops the source's metadata, location included.
 */
object PhotoPreparation {
    /** Larger than this is refused before decoding, as on the iPhone. */
    const val maxInputBytes = 64 * 1024 * 1024

    /** The attachment limit a photo has to fit after re-encoding. */
    const val maxOutputBytes = 6 * 1024 * 1024

    /** The longest side of what is sent, in pixels. */
    const val maxPixelSize = 2048

    /** `kCGImageDestinationLossyCompressionQuality: 0.86`. */
    const val quality = 86

    fun jpeg(from: ByteArray): ByteArray {
        if (from.size > maxInputBytes) throw PhotoPreparationError.ImageTooLarge
        val image = decode(from)
        try {
            val out = ByteArrayOutputStream()
            if (!image.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw PhotoPreparationError.InvalidImage
            if (out.size() > maxOutputBytes) throw PhotoPreparationError.ImageTooLarge
            return out.toByteArray()
        } finally {
            image.recycle()
        }
    }

    /**
     * The size a photo of [width] by [height] is decoded to: unchanged when it already fits,
     * otherwise scaled so its longest side is [limit].
     */
    fun fitted(width: Int, height: Int, limit: Int = maxPixelSize): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= limit || longest <= 0) return width to height
        val scale = limit.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * `ImageDecoder` applies the photo's orientation itself, which is what
     * `kCGImageSourceCreateThumbnailWithTransform` does, and decodes at the target size rather
     * than decoding the whole raster and shrinking it.
     */
    private fun decode(data: ByteArray): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            // A software bitmap, because a hardware one cannot be compressed.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = fitted(info.size.width, info.size.height)
            if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
        }
    } catch (_: IOException) {
        throw PhotoPreparationError.InvalidImage
    } catch (_: IllegalArgumentException) {
        throw PhotoPreparationError.InvalidImage
    } catch (_: OutOfMemoryError) {
        throw PhotoPreparationError.ImageTooLarge
    }
}
