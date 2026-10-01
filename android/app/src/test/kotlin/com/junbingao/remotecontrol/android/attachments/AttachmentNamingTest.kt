package com.junbingao.remotecontrol.android.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The checks `ios/VerificationUI/main.swift` makes of `AttachmentNaming`. */
class AttachmentNamingTest {
    @Test
    fun theFirstPhotoIsNamedForItsPlace() {
        assertEquals("photo-1.jpg", AttachmentNaming.libraryPhoto(1))
        assertEquals("photo-2.jpg", AttachmentNaming.libraryPhoto(2))
    }

    @Test
    fun aCameraShotIsTheOnePhotoThereIs() {
        assertEquals("photo.jpg", AttachmentNaming.cameraPhoto)
    }

    @Test
    fun noNameCarriesAPathSeparator() {
        assertFalse(AttachmentNaming.libraryPhoto(1).contains("/"))
    }
}
