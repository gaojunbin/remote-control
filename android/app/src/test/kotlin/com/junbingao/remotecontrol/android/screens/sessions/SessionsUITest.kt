package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of the Sessions list (`ios/UITests/RemoteControlUITests.swift`), on the demo
 * through the same steps: every dot tone, the origin words and the legend, a device's Archive, Close,
 * a folded group, the agent filter and the bar at the foot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SessionsUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val list = "sessions.list"

    private fun sessions(test: String, body: (DemoApp, ListsDriver) -> Unit) = DemoApp(compose, test).use { app ->
        app.waitFor("sessions.new")
        app.waitFor(row("demo-session-auth"))
        body(app, ListsDriver(compose, app))
    }

    private fun row(sessionID: String) = "session.$sessionID"

    @Test
    fun sessionsListShowsEveryStatusTone() = sessions("testSessionsListShowsEveryStatusTone") { app, lists ->
        // The grey one sits in the Archive of the machine whose CLI exited. That Archive's open or
        // closed state is remembered per machine, so this opens it only if it is shut and leaves it
        // as it found it.
        val archive = "sessions.archive.demo-ci-runner"
        val archived = row("demo-session-otlp")
        var opened = false
        if (!lists.scrollDown(archived)) {
            assertTrue("the offline machine carries its own Archive", lists.scrollDown(archive))
            app.tap(archive)
            opened = true
            assertTrue("the list carries a session nothing owns, on a machine that is gone", lists.scrollDown(archived))
        }

        // The machine in between holds nothing this is about, and the five together are taller
        // than the screen, so its group is folded away for the duration and put back at the end.
        val middle = "sessions.device.demo-macbook-air"
        assertTrue("the middle machine has a header to fold", lists.scrollDown(middle))
        app.tap(middle)
        app.waitForAbsence(row("demo-session-rename"))

        repeat(8) { lists.swipeDown(list) }
        for (id in listOf("demo-session-vite", "demo-session-auth", "demo-session-toolchain", "demo-session-shared")) {
            assertTrue("the list carries the $id row, and it is reachable", lists.scrollDown(row(id)))
            app.attach("30-status-tone-${id.removePrefix("demo-session-")}")
        }

        // And the fifth, further down than a screen reaches.
        assertTrue("the grey row, owned by nothing, is still reachable", lists.scrollDown(archived))
        app.attach("30-status-tone-grey")

        assertTrue("the folded machine is still there", lists.scrollDown(middle))
        app.tap(middle)
        if (opened) {
            assertTrue("the Archive header is still reachable", lists.scrollDown(archive))
            app.tap(archive)
        }
    }

    /**
     * `docs/DESIGN.md` § "The session row says where it came from" and § "A legend, once, and
     * quiet": a row's word is where the session came from, its state is the dot's colour alone, and
     * one caption above the list says what the colours mean — on this screen and on no other.
     */
    @Test
    fun sessionRowsNameTheirOriginAndTheDotsAreExplainedOnce() = sessions("testSessionRowsNameTheirOriginAndTheDotsAreExplainedOnce") { app, lists ->
        val remote = lists.label(row("demo-session-auth"))
        assertTrue("its row says where it came from — $remote", remote.contains("Remote Control"))
        assertFalse("rather than what it is doing, which the green dot already says", remote.contains("running"))

        val terminal = lists.label(row("demo-session-vite"))
        assertTrue("a session a terminal started says so — $terminal", terminal.contains("Terminal"))
        assertFalse("and leaves the waiting to the amber dot", terminal.contains("needs approval"))

        app.waitFor("sessions.legend")
        assertEquals("in four entries, read as one line", "Working, For you, Not running, Error", lists.label("sessions.legend"))
        app.attach("31-origin-and-legend")

        app.tap("tab.devices")
        app.waitFor("devices.add")
        assertFalse("and carries no legend of its own, because nothing is explained twice", app.exists("sessions.legend"))
    }

    /** A machine's finished sessions sit in its own collapsed Archive, and a search reaches inside it without opening it by hand. */
    @Test
    fun deviceArchiveOpensOnTapAndOnSearch() = sessions("testDeviceArchiveOpensOnTapAndOnSearch") { app, lists ->
        val archived = row("demo-session-otlp")
        assertFalse("a session whose CLI exited is not among the live rows", app.exists(archived))

        // The list is lazy, so the group has to be scrolled into view first.
        val archive = "sessions.archive.demo-ci-runner"
        assertTrue("the machine carries its own Archive", lists.scrollDown(archive))
        assertTrue("and its header counts what is inside", lists.label(archive).contains("1"))

        app.tap(archive)
        assertTrue("one tap opens it", lists.scrollDown(archived))
        // The list ends here, so one more swipe settles it at the foot with the whole Archive in view.
        lists.swipeUp(list)
        app.attach("15-archive-open")

        assertTrue("the header is still reachable", lists.scrollDown(archive))
        app.tap(archive)
        app.waitForAbsence(archived)

        // A search finds it wherever it is, without a second tap. The search field hides itself
        // while the list rests, and a pull past the list's top brings it out.
        repeat(6) { if (!app.exists("search.field")) lists.swipeDown(list) }
        app.waitFor("search.field")
        app.tap("search.field")
        app.node("search.field").performTextInput("OTLP")
        app.waitFor(archived)
        app.attach("16-archive-search")
    }

    /**
     * Amendment A39, `docs/DESIGN.md` § "Close, then the Archive": the row action is Close, not
     * Archive. A working session is asked about first and Cancel really cancels; an idle one closes
     * on the tap; either way the row lands in that machine's Archive, marked "Archived", and offers
     * nothing further.
     */
    @Test
    fun closingASessionAsksOnlyWhileTheAgentIsWorking() = sessions("testClosingASessionAsksOnlyWhileTheAgentIsWorking") { app, lists ->
        // A running session this app drives. Its swipe carries one action.
        val live = row("demo-session-auth")
        lists.revealRowActions(live)
        val close = "session.close.demo-session-auth"
        lists.waitShown(close)
        assertEquals("by that word, and no longer Archive", "Close", lists.label(close))
        lists.tapShown(close)

        // The agent is working, so the tap asks before it throws the turn away.
        lists.waitText("Close this session?")
        assertTrue("over the sentence that says what is lost", lists.onScreen("The agent is still working; what it has not finished is lost."))
        assertEquals("with the same word the row used", "Close", lists.label("session.close.confirm"))
        assertTrue("and a way out", app.exists("alert.cancel"))
        app.attach("61-session-close-dialog")

        app.tap("alert.cancel")
        app.waitForAbsence("alert.cancel")
        assertTrue("and leaves the session where it was", app.exists(live))
        assertFalse("unarchived, and still live", lists.label(live).contains("archived"))

        // The same swipe, answered this time.
        lists.revealRowActions(live)
        lists.waitShown(close)
        lists.tapShown(close)
        lists.waitText("Close this session?")
        app.tap("session.close.confirm")
        app.waitForAbsence(live, 15_000)

        // An idle session the device drives has nothing to lose, so it goes on the tap, with no
        // dialog in between.
        val idle = row("demo-session-parser")
        assertTrue("the idle session this app drives is listed", lists.scrollDown(idle))
        lists.revealRowActions(idle)
        val closeIdle = "session.close.demo-session-parser"
        lists.waitShown(closeIdle)
        lists.tapShown(closeIdle)
        val asked = runCatching { app.await("an alert", 3_000) { app.exists("alert.cancel") } }.isSuccess
        assertFalse("and nothing is asked, because nothing is lost", asked)
        app.waitForAbsence(idle, 15_000)

        // Both are in that machine's Archive, marked and finished with.
        val archive = "sessions.archive.demo-mac-studio"
        assertTrue("the machine's Archive has grown", lists.scrollDown(archive))
        app.tap(archive)
        assertTrue("the session that was working is inside it", lists.scrollDown(live))
        assertTrue("marked as filed by hand — ${lists.label(live)}", lists.label(live).contains("archived"))
        assertTrue("and so is the one that was idle", lists.scrollDown(idle))
        assertTrue("with the same mark — ${lists.label(idle)}", lists.label(idle).contains("archived"))
        app.attach("62-session-closed-archive")

        lists.revealRowActions(live)
        assertFalse("a row in the Archive offers nothing, not even unarchive", app.exists(close))
    }

    /** A device header folds its whole group away and brings it back. */
    @Test
    fun deviceGroupCollapses() = sessions("testDeviceGroupCollapses") { app, lists ->
        val header = "sessions.device.demo-mac-studio"
        assertTrue("the busiest machine heads the list", lists.scrollDown(header))
        val auth = row("demo-session-auth")
        assertTrue("with its live sessions under it", app.exists(auth))

        app.tap(header)
        app.waitForAbsence(auth)
        app.attach("17-device-collapsed")

        assertTrue("the header stays put", lists.scrollDown(header))
        app.tap(header)
        app.waitFor(auth)
    }

    /** The agent filter narrows the list to one agent and drops any machine left with nothing to show. */
    @Test
    fun agentFilterNarrowsTheListToOneAgent() = sessions("testAgentFilterNarrowsTheListToOneAgent") { app, _ ->
        val claudeRow = row("demo-session-auth")
        val codexRow = row("demo-session-vite")
        assertTrue("both agents are listed to start with", app.exists(codexRow))

        app.tap("sessions.agentFilter")
        app.waitFor("sessions.agentFilter.codex")
        app.tap("sessions.agentFilter.codex")

        app.waitForAbsence(claudeRow)
        assertTrue("the chosen agent's rows stay", app.exists(codexRow))
        assertFalse("and a machine left with nothing disappears with them", app.exists("sessions.device.demo-macbook-air"))
        app.attach("18-agent-filter")

        app.tap("sessions.agentFilter")
        app.tap("sessions.agentFilter.all")
        app.waitFor(claudeRow)
    }

    /**
     * `docs/DESIGN.md` § "The three screens": on the phone the Sessions list ends the way the
     * Devices list ends — one primary button in the bottom bar, exactly where Devices puts Add
     * device — and the inventory summary is the list's own last row rather than a strip under it.
     */
    @Test
    fun sessionsListEndsWithTheNewSessionButtonInTheBottomBar() = sessions("testSessionsListEndsWithTheNewSessionButtonInTheBottomBar") { app, lists ->
        val sessionsBar = lists.bounds("sessions.new")
        assertTrue("the summary is a row inside the list", lists.scrollDown("sessions.summary"))
        assertTrue("and sits above the bar rather than in it", lists.bounds("sessions.summary").bottom < sessionsBar.top)
        app.attach("69-sessions-bottom-bar")

        app.tap("tab.devices")
        app.waitFor("devices.add")
        val addDevice = lists.bounds("devices.add")
        val point = compose.density.density
        assertTrue("both bars stand at the same height", abs(sessionsBar.top - addDevice.top) <= point)
        assertTrue("and are drawn to the same size", abs(sessionsBar.height - addDevice.height) <= point)
        assertTrue("with the same page padding", abs(sessionsBar.left - addDevice.left) <= point)
    }
}
