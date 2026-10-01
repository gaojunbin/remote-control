package com.junbingao.remotecontrol.android.demo

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The demo as the iPhone's `testSessionsAndChat` launches and pictures it, in both languages and
 * both appearances: the first screen (`01-sessions`) and the live conversation (`02-chat`), for
 * laying beside the iPhone's pictures of the same steps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DemoPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val live = "session.${DemoFixtures.liveSessionID}"

    @Test
    fun sessionsAndChat() {
        for (variant in IPhone.variants) DemoApp(compose, "testSessionsAndChat", variant = variant).use { app ->
            app.waitFor(live)
            app.attach("01-sessions")
            app.tap(live)
            app.await("the live conversation's transcript") { app.model.chat?.timeline?.entries?.isNotEmpty() == true }
            app.attach("02-chat")
        }
    }
}
