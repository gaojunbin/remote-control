package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.win.layout.Landing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTests {
    @Test
    fun historyWalksBackAndForwardAsABrowserDoes() {
        val router = Router()
        router.replace(Route.Sessions)
        router.go(Route.Devices)
        router.go(Route.Device("mac"))
        assertTrue(router.canGoBack)
        router.back()
        assertEquals(Route.Devices, router.route)
        router.back()
        assertEquals(Route.Sessions, router.route)
        assertFalse(router.canGoBack)
        router.forward()
        assertEquals(Route.Devices, router.route)
        // A link followed from the middle of the history drops what was ahead.
        router.go(Route.Settings)
        assertFalse(router.canGoForward)
    }

    @Test
    fun goingWhereTheAppAlreadyIsAddsNothing() {
        val router = Router()
        router.replace(Route.Sessions)
        router.go(Route.Sessions)
        assertFalse(router.canGoBack)
    }

    /** The web's redirect to the login page carries the page in `state.from`. */
    @Test
    fun aSessionTheGatewayEndedReturnsWhereTheAppWas() {
        val router = Router()
        router.replace(Route.Settings)
        router.go(Route.Devices)
        router.signedOut(keepingPlace = true)
        assertEquals(Route.Login, router.route)
        assertFalse(router.canGoBack)
        router.signedIn()
        assertEquals(Route.Devices, router.route)
        router.signedOut(keepingPlace = true)
        router.signedOut(keepingPlace = true)
        router.signedIn()
        assertEquals(Route.Devices, router.route)
    }

    /** The web's Sign out goes to the login page with no state. */
    @Test
    fun signingOutLandsTheNextSignInByTheLandingRule() {
        val router = Router()
        router.replace(Route.Settings)
        router.signedOut(keepingPlace = true)
        router.signedIn()
        router.signedOut(keepingPlace = false)
        assertEquals(Route.Login, router.route)
        assertNull(router.returnTo)
        router.signedIn()
        assertEquals(Route.Landing, router.route)
    }

    @Test
    fun aSignInWithNowhereToReturnLands() {
        val router = Router()
        router.signedOut(keepingPlace = true)
        router.signedIn()
        assertEquals(Route.Landing, router.route)
    }

    @Test
    fun aMemberWhoAsksForUsersIsSentToSessions() {
        val router = Router()
        var admin = false
        router.canSeeUsers = { admin }
        router.go(Route.Users)
        assertEquals(Route.Sessions, router.route)
        admin = true
        router.go(Route.Users)
        assertEquals(Route.Users, router.route)
    }

    @Test
    fun newSessionIsAskedForOnce() {
        val router = Router()
        router.replace(Route.Devices)
        router.requestNewSession()
        assertEquals(Route.Sessions, router.route)
        assertTrue(router.takeNewSessionRequest())
        assertFalse(router.takeNewSessionRequest())
    }

    @Test
    fun routesAreTheWebsPaths() {
        val routes = listOf(
            Route.Landing, Route.Login, Route.Devices, Route.Device("dev mac"), Route.Terminal("dev-mac"),
            Route.Sessions, Route.Chat("dev-mac", "ses-vite"), Route.Settings, Route.Users,
        )
        for (route in routes) assertEquals(route, Route.of(route.path), route.path)
        assertEquals("/sessions/dev-mac/ses-vite", Route.Chat("dev-mac", "ses-vite").path)
        assertEquals("/devices/dev%20mac", Route.Device("dev mac").path)
        assertEquals(Route.Device("雷的 Mac/2"), Route.of(Route.Device("雷的 Mac/2").path))
        assertEquals(Route.Landing, Route.of("/pair"))
        assertEquals(Route.Landing, Route.of("/nowhere/at/all/here"))
    }

    @Test
    fun theTopbarMarksATabForItsPlaceAndThoseBelowIt() {
        assertEquals(Route.Devices, Route.Device("x").tab)
        assertEquals(Route.Sessions, Route.Chat("d", "s").tab)
        assertNull(Route.Users.tab)
        assertTrue(Route.Devices.isInLayout && !Route.Chat("d", "s").isInLayout)
    }

    @Test
    fun landingPicksSessionsOnlyWhenThereIsADevice() {
        assertEquals(Route.Sessions, Landing.destination(hasDevices = true))
        assertEquals(Route.Devices, Landing.destination(hasDevices = false))
    }
}
