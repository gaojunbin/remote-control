package com.junbingao.remotecontrol.android.navigation

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** `remotecontrol://session?device=…&id=…`, the link a notification's tap delivers. */
@RunWith(AndroidJUnit4::class)
class SessionLinkTest {
    @Test
    fun theIPhonesLinkNamesItsDeviceAndSession() {
        val link = SessionLink.parse(Uri.parse("remotecontrol://session?device=mac-studio-office&id=sess-01"))
        assertEquals(SessionLink("mac-studio-office", "sess-01"), link)
    }

    @Test
    fun aLinkWritesAndReadsBackTheSame() {
        val link = SessionLink("dev/1", "a b&c")
        assertEquals(link, SessionLink.parse(link.uri()))
        assertEquals("remotecontrol", link.uri().scheme)
    }

    @Test
    fun anythingElseIsNoLink() {
        assertNull(SessionLink.parse(null))
        assertNull(SessionLink.parse(Uri.parse("https://session?device=a&id=b")))
        assertNull(SessionLink.parse(Uri.parse("remotecontrol://device?device=a&id=b")))
        assertNull(SessionLink.parse(Uri.parse("remotecontrol://session?device=a")))
        assertNull(SessionLink.parse(Uri.parse("remotecontrol://session?device=&id=b")))
    }
}
