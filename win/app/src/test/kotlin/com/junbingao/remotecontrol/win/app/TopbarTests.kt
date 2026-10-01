package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.junbingao.remotecontrol.win.layout.AppLayout
import com.junbingao.remotecontrol.win.layout.Landing
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell on a stand-in for the app model: the tabs move the router, and an open lands by the rule. */
class TopbarTests {
    private class Shell : ShellState {
        override val router = Router()
        override val origin = "https://rc.example.com"
        override val username = "admin"
        override val connectionIsOpen = true
        override var hasSnapshot by mutableStateOf(false)
        override val hasDevices = false
    }

    @Test
    fun aTabIsALinkFollowed() {
        val shell = Shell()
        shell.router.replace(Route.Devices)
        val scene = ImageComposeScene(1280, 860, Density(1f)) {
            CompositionLocalProvider(LocalShellState provides shell) { RootView { AppLayout {} } }
        }
        scene.render(0)
        // The middle of the Sessions tab, where a 1280 px window draws it.
        val x = 394f
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, 30f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, 30f))
        scene.render(16_000_000)
        assertEquals(Route.Sessions, shell.router.route)
        shell.router.back()
        assertEquals(Route.Devices, shell.router.route)
        scene.close()
    }

    @Test
    fun anOpenLandsOnceTheSnapshotHasArrived() {
        val shell = Shell()
        val scene = ImageComposeScene(800, 600, Density(1f)) {
            CompositionLocalProvider(LocalShellState provides shell) { RootView { Landing() } }
        }
        scene.render(0)
        assertEquals(Route.Landing, shell.router.route)
        shell.hasSnapshot = true
        scene.render(16_000_000)
        scene.render(32_000_000)
        assertEquals(Route.Devices, shell.router.route)
        scene.close()
    }
}
