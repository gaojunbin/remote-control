package com.junbingao.remotecontrol.android.screens.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell's tests of `ios/UITests/`, step for step: the tabs and the landing rule, the launch
 * rule in both directions, the sign-in form and its refusals, registration, the blocking update
 * screen, and the gateway remembered across a launch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ShellUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    private fun launch(test: String, arguments: List<String> = DemoApp.launchArguments, run: (DemoApp, Driving) -> Unit) =
        DemoApp(compose, test, arguments).use { app -> run(app, Driving(compose, app)) }

    /** `launchSignedOut(extra:)`: the sign-in form, with the offline gateway behind it. */
    private fun launchSignedOut(test: String, vararg extra: String, run: (DemoApp, Driving) -> Unit) =
        launch(test, DemoApp.signedOut + extra, run)

    /** `docs/DESIGN.md` § "Three tabs, one order, one landing rule": the demo account has machines, so the app opens on the conversation. */
    @Test
    fun tabsReadDevicesSessionsSettingsAndLandOnSessions() = launch("testTabsReadDevicesSessionsSettingsAndLandOnSessions") { app, drive ->
        assertTrue("the shell is up", drive.waitFor(hasTestTag("tab.devices"), 20_000))
        val devices = drive.frame("tab.devices")
        val sessions = drive.frame("tab.sessions")
        val settings = drive.frame("tab.settings")
        assertTrue("Devices stands first", devices.left < sessions.left)
        assertTrue("then Sessions, then Settings", sessions.left < settings.left)
        app.await("an account with a machine lands where the conversation is") { selected("tab.sessions", app) }
        ForeignScreen.requiresTheSessionsList()
        assertTrue("on the sessions list itself", drive.waitFor(hasTestTag("sessions.new"), 10_000))
    }

    /** "Launch shows the app, never the sign-in form, when there is an account": the demo is an account. */
    @Test
    fun launchWithAnAccountNeverShowsTheSignInForm() = launch("testLaunchWithAnAccountNeverShowsTheSignInForm") { app, drive ->
        assertFalse("the form is not what a launch with an account draws first", drive.exists("login.connect"))
        var formSeen = false
        app.await("the sessions screen is the first screen", 20_000) {
            formSeen = formSeen || drive.exists("login.connect")
            drive.reads("Sessions")
        }
        assertFalse("and the form never appeared on the way", formSeen || drive.exists("login.connect"))
        app.attach("42-launch-with-account")
    }

    /** And the other half of the rule: with nothing stored the form is the answer, so it is what a fresh install opens on. */
    @Test
    fun launchWithNothingStoredShowsTheSignInForm() =
        launch("testLaunchWithNothingStoredShowsTheSignInForm", listOf("--ui-testing", "--reset-state")) { app, drive ->
            assertTrue("a fresh install asks for a gateway", drive.waitFor(hasTestTag("login.connect"), 20_000))
            assertTrue(drive.exists("login.gateway"))
            app.attach("43-launch-without-account")
        }

    /** The form asks for the gateway, the username and the password, and offers no way to create an account on a gateway that is not taking them. */
    @Test
    fun signInAsksForAUsernameAndOffersNoRegistrationWhenItIsClosed() =
        launchSignedOut("testSignInAsksForAUsernameAndOffersNoRegistrationWhenItIsClosed") { app, drive ->
            assertTrue("the form asks for a gateway", drive.waitFor(hasTestTag("login.gateway"), 20_000))
            assertTrue("and for a username", drive.exists("login.username"))
            assertTrue("and for a password", drive.exists("login.password"))
            assertTrue("in that order, gateway first", drive.frame("login.username").top > drive.frame("login.gateway").top)
            app.attach("71-sign-in-form")

            drive.typeGateway()
            assertFalse("a gateway that is not taking accounts offers no way to create one", drive.waitFor(hasTestTag("login.register"), 5_000))
        }

    /** A disabled account is told so, and a wrong password is not told which half was wrong. */
    @Test
    fun disabledAccountIsToldSoAndAWrongOneIsNot() = launchSignedOut("testDisabledAccountIsToldSoAndAWrongOneIsNot") { app, drive ->
        drive.signIn(username = "bob", password = "correct horse")
        assertTrue("a refused sign-in says why", drive.waitFor(hasTestTag("login.error"), 15_000))
        assertEquals("This account is disabled.", drive.label("login.error"))
        app.attach("72-disabled-account")

        // "bobby" is nobody on this gateway, which reads the same as a wrong password: the form
        // never says which half it did not recognise.
        app.node("login.username").performTextInput("by")
        app.tap("login.connect")
        app.await("an unknown account is not told that it is unknown", 15_000) {
            drive.exists("login.error") && drive.label("login.error") == "Wrong username or password."
        }
    }

    /** "Create an account" appears only when the gateway reports registration open, and swaps the card for the registration form. */
    @Test
    fun registrationLinkAppearsOnlyWhenTheGatewayIsOpen() =
        launchSignedOut("testRegistrationLinkAppearsOnlyWhenTheGatewayIsOpen", "--registration-open") { app, drive ->
            drive.typeGateway()
            assertTrue("a gateway taking accounts offers to create one", drive.waitFor(hasTestTag("login.register"), 15_000))
            assertTrue("under the button, where the design puts it", drive.frame("login.register").top > drive.frame("login.connect").top)
            app.attach("73-registration-offered")

            app.tap("login.register")
            assertTrue("with a way back to signing in", drive.waitFor(hasTestTag("login.signInInstead"), 10_000))
            assertTrue("the card asks for a username", drive.exists("login.username"))
            assertTrue("and a password", drive.exists("login.password"))
            app.attach("74-registration-form")

            app.node("login.username").performTextInput("carol")
            app.node("login.password").performTextInput("correct horse battery staple")
            compose.waitForIdle()
            app.tap("login.connect")
            assertTrue("creating an account signs it in", drive.waitFor(hasTestTag("tab.settings"), 25_000))
        }

    /** Protocol 8.16: below the gateway's minimum the app shows one screen and nothing else. */
    @Test
    fun appBelowTheGatewayMinimumShowsOnlyTheUpdateScreen() =
        launch("testAppBelowTheGatewayMinimumShowsOnlyTheUpdateScreen", DemoApp.launchArguments + "--demo-update-required") { app, drive ->
            assertTrue("the blocking screen is up", drive.waitFor(hasTestTag("update.title"), 20_000))
            assertEquals("and says what is required", "Update required", drive.label("update.title"))
            assertTrue("with this build's version and the one the gateway asks for", drive.exists("update.versions"))
            assertTrue("with somewhere to get the newer build", drive.exists("update.open"))
            assertTrue("and a way to another gateway", drive.exists("update.signOut"))
            app.attach("ios-update-required")
        }

    /**
     * A gateway that worked once is remembered, so the next launch does not ask for the address
     * again. Leaving the app is part of the test: it is where the iPhone used to rebuild its model
     * and re-apply `--reset-state` over the address the sign-in had just stored.
     *
     * The iPhone's test runs against a real gateway; this one signs in to the offline gateway behind
     * the form and relaunches onto the same kind of gateway. Either outcome the iPhone's accepts is
     * accepted: the main screens, because the account was restored, or the form, prefilled with the
     * gateway that worked.
     */
    @Test
    fun remembersTheGatewayAcrossALaunch() {
        DemoApp(compose, "testRemembersTheGatewayAcrossALaunch", DemoApp.signedOut).use { app ->
            val drive = Driving(compose, app)
            drive.signIn(username = "admin", password = "correct horse")
            assertTrue("signing in lands on the app", drive.waitFor(hasTestTag("tab.sessions"), 40_000))
            // At a tab's root the system's Back leaves the app.
            app.back()
            Thread.sleep(3_000)
        }
        DemoApp(compose, "testRemembersTheGatewayAcrossALaunch", listOf("--ui-testing", "--demo-account")).use { relaunched ->
            val drive = Driving(compose, relaunched)
            relaunched.await("the launch has decided what to show", 20_000) { !relaunched.model.isResuming }
            relaunched.await("the relaunched app shows either the main screens or the login screen", 20_000) {
                relaunched.exists("tab.sessions") || relaunched.exists("login.gateway")
            }
            if (!relaunched.exists("tab.sessions")) {
                val model = relaunched.model
                assertEquals(
                    "the login screen prefills the gateway that worked last time " +
                        "(stored ${model.settings.lastOrigin}, ${model.connection.phase}, ${model.connection.errorMessage})",
                    Driving.TEST_GATEWAY,
                    drive.text("login.gateway"),
                )
            }
            relaunched.attach("06-relaunch")
        }
    }

    private fun selected(tag: String, app: DemoApp): Boolean =
        app.node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
}
