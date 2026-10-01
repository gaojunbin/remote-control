package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.AppVersion
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.MemoryUserDefaults
import com.junbingao.remotecontrol.win.login.LoginErrorText
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's `ModelTests`, case for case, on the core's offline demo. */
class ModelTests {
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun launchArgumentsAreRead() {
        val options = LaunchOptions(listOf("--demo", "--ephemeral", "--language=zh-Hans"))
        assertTrue(options.demo && options.ephemeral && !options.demoAccount)
        assertEquals(InterfaceLanguage.zhHans, options.language)
        assertNull(LaunchOptions(listOf("--language=fr")).language)
    }

    @Test
    fun anEphemeralRunKeepsNothingOfThePersons() {
        val persistence = Persistence(ephemeral = true)
        assertTrue(persistence.isEphemeral)
        assertTrue(persistence.defaults is MemoryUserDefaults)
        assertTrue(persistence.secrets is MemorySecretStore)
        persistence.discard()
    }

    @Test
    fun theDemoSignsInAndReachesItsSnapshot() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(isSignedIn && isDemo)
            assertTrue(harness.waitFor { connection.hasSnapshot })
            assertTrue(connection.devices.isNotEmpty())
            assertEquals(Route.Landing, router.route)
            assertFalse(isResuming)
            signOut()
        }
    }

    @Test
    fun signingOutRunsEveryHandlerAndLandsOnTheForm() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            val calls = mutableListOf<String>()
            onSignOut { calls += "first:$isSignedIn" }
            onSignOut { calls += "second" }
            router.go(Route.Settings)
            deviceUpdateErrors = mapOf("d" to "refused")
            signOut()
            assertEquals(listOf("first:true", "second"), calls)
            assertFalse(isSignedIn)
            assertEquals(Route.Login, router.route)
            // Signed out from Settings, the next sign-in lands by the landing rule.
            assertNull(router.returnTo)
            assertTrue(deviceUpdateErrors.isEmpty())
        }
    }

    @Test
    fun aSessionTheGatewayEndedReturnsToThePageOnTheNextSignIn() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            router.go(Route.Settings)
            endSession(keepingPlace = true)
            assertEquals(Route.Login, router.route)
            assertEquals(Route.Settings, router.returnTo)
        }
    }

    @Test
    fun theFormSaysWhatTheGatewayRefused() = ModelHarness(LaunchOptions(demoAccount = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            val origin = "https://demo.remote-control.invalid"
            signIn(origin = origin, username = "nobody", password = "wrongpass")
            assertFalse(isSignedIn)
            assertEquals(S.login.failed, LoginErrorText.signIn(lastSignInError))
            signIn(origin = origin, username = "admin", password = "longenough")
            assertTrue(isSignedIn)
            assertNull(lastSignInError)
            assertEquals(origin, settings.lastOrigin)
            assertEquals("admin", settings.username(origin))
            signOut()
        }
    }

    @Test
    fun theInterfaceLanguageFollowsTheSignedInAccount() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            settings.language = InterfaceLanguage.zhHans
            assertTrue(harness.waitFor { InterfaceLanguageSource.current == InterfaceLanguage.zhHans })
            // The login page is the web's: English, whatever the account chose.
            signOut()
            assertTrue(harness.waitFor { InterfaceLanguageSource.current == InterfaceLanguage.en })
        }
    }

    @Test
    fun aLanguageFixedAtLaunchHoldsOnTheLoginPageToo() =
        ModelHarness(LaunchOptions(demoAccount = true, ephemeral = true, language = InterfaceLanguage.zhHans)).use {
            assertEquals(InterfaceLanguage.zhHans, InterfaceLanguageSource.current)
        }

    @Test
    fun updateRequiredIsReachedFromTheDemo() =
        ModelHarness(LaunchOptions(demo = true, demoUpdateRequired = true, ephemeral = true)).use { harness ->
            harness.run {
                restoreOrPrompt()
                assertTrue(harness.waitFor { connection.updateRequired != null })
                assertEquals(AppVersion(DemoFixtures.laterAppVersion), connection.updateRequired?.minimum)
                signOut()
                assertNull(connection.updateRequired)
            }
        }

    /** A44: `stt_language` is the phone recogniser's. The web never writes it, and Windows has no recogniser, so a sign-in leaves it as it was. */
    @Test
    fun aSignInWritesNoDictationLanguage() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(harness.waitFor { connection.hasSnapshot })
            preferenceSync.settle()
            val held = assertNotNull(connection.api).preferences().preferences
            assertNull(held.sttLanguage)
            signOut()
        }
    }

    @Test
    fun aTransitionReachesEveryHandler() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        var seen = 0
        harness.model.onSessionTransition { _, _ -> seen += 1 }
        harness.model.onSessionTransition { _, _ -> seen += 1 }
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "", cwd = "/")
        harness.model.connection.onSessionTransition?.invoke(session, session)
        assertEquals(2, seen)
    }

    /** A click on one of the app's notifications opens its conversation (`docs/DESIGN.md` § "The Windows app" → **Notify me posts from the app**). */
    @Test
    fun aNotificationClickedOpensItsConversation() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        var shown = false
        harness.model.showWindow = { shown = true }
        harness.model.toasts.onOpen?.invoke(com.junbingao.remotecontrol.win.platform.ToastTarget(deviceId = "d", sessionId = "s"))
        assertTrue(shown)
        assertEquals(Route.Chat(deviceId = "d", sessionId = "s"), harness.model.router.route)
    }

    /** The report names the app that wrote it (round 56): this one is Windows'. */
    @Test
    fun theDiagnosticReportNamesWindows() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            val report = diagnosticReport()
            assertTrue(report.startsWith("Remote Control for Windows"), report.lineSequence().first())
            assertTrue("Platform: Windows" in report && "Mode: offline demo" in report)
            signOut()
        }
    }
}
