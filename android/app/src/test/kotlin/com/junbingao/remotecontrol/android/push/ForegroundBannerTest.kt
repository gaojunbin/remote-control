package com.junbingao.remotecontrol.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks `ios/VerificationUI/main.swift` makes of `ForegroundBanner`. */
class ForegroundBannerTest {
    @Test
    fun aLocalAlertIsAlwaysShown() {
        assertTrue(ForegroundBanner.shows(remote = false, suppressesRemote = true))
    }

    @Test
    fun aPushIsShownWhenTheAppIsNotReadingTheStream() {
        assertTrue(ForegroundBanner.shows(remote = true, suppressesRemote = false))
    }

    @Test
    fun aPushTheAppAlreadyAnnouncedIsDropped() {
        assertFalse(ForegroundBanner.shows(remote = true, suppressesRemote = true))
    }

    @Test
    fun provisionalAndAuthorizedRaiseBannersAndNothingElseDoes() {
        assertTrue(PushAuthorization.authorized.raisesBanners)
        assertTrue(PushAuthorization.provisional.raisesBanners)
        assertFalse(PushAuthorization.denied.raisesBanners)
        assertFalse(PushAuthorization.notDetermined.raisesBanners)
        assertFalse(PushAuthorization.unsupported.raisesBanners)
    }
}
