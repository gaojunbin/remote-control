package com.junbingao.remotecontrol.android.gallery

import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.picture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Every page of the gallery, which between them draw every primitive of the design system and
 * every iPhone system piece, in both languages and both appearances.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class GalleryScreenshots(private val page: String, private val variant: Variant) : IPhoneScreenshotTest() {

    @Test
    fun page() {
        compose.picture("gallery", page, variant) { GalleryPage(page) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> =
            GalleryPages.all.flatMap { page -> IPhone.variants.map { arrayOf(page.id, it) } }
    }
}
