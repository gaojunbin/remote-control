package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of the New session sheet and the directory picker it opens
 * (`ios/UITests/RemoteControlUITests.swift`), on the demo through the same steps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class NewSessionUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val form = "newsession.form"

    private fun sheet(test: String, body: (DemoApp, ListsDriver) -> Unit) = DemoApp(compose, test).use { app ->
        app.tap("sessions.new")
        app.waitFor("newsession.start")
        body(app, ListsDriver(compose, app))
    }

    /**
     * `docs/DESIGN.md` § "Agents", amendment A26: four agents on one machine. The segmented
     * control carries each agent's logo rather than its name, because four names do not fit a
     * phone; assistive technology still reads the name. And the form asks about nothing the agent
     * does not have.
     */
    @Test
    fun newSessionSheetMarksEveryAgentAndDrawsOnlyWhatItHas() = sheet("testNewSessionSheetMarksEveryAgentAndDrawsOnlyWhatItHas") { app, lists ->
        app.waitFor("newsession.agent.pi")
        val segments = listOf("claude", "codex", "grok", "pi").map { "newsession.agent.$it" }.sortedBy { lists.left(it) }
        assertEquals(
            "one segment per agent the machine reported, each named to a screen reader",
            listOf("Claude Code", "Codex", "Grok Build", "pi"),
            segments.map { lists.label(it) },
        )
        app.attach("82-new-session-agents")

        // Amendment A26: pi's permission modes are the device's own, enforced by the extension it
        // loads, so the form asks about them like any other.
        app.tap("newsession.agent.pi")
        assertTrue("pi offers the thinking levels it does have", app.exists("newsession.effort"))
        assertTrue("and the three permission modes the extension enforces (A26)", app.exists("newsession.permissions"))
        app.attach("83-new-session-pi")
    }

    @Test
    fun newSessionSheetOffersDeviceAndAgent() = sheet("testNewSessionSheetOffersDeviceAndAgent") { app, lists ->
        assertTrue("an agent picker is present", app.exists("newsession.agent"))
        assertFalse("and the sheet no longer asks for a first message", app.exists("newsession.prompt"))

        // `docs/DESIGN.md` § "The composer": forms list Model, Effort, Permissions in that order,
        // with the speed switch after the three. The sheet opens on Claude, which lists no tier, so
        // the agent that has one is chosen first — the lists all follow the agent picker.
        app.tap("newsession.agent.codex")
        val rows = listOf("newsession.model", "newsession.effort", "newsession.permissions", "newsession.speed")
        for (row in rows) assertTrue("the sheet offers $row", lists.scrollDown(row))
        app.attach("52-new-session-settings")
        // The order is read off the tree rather than off coordinates, as the iPhone reads it off the
        // accessibility hierarchy.
        val seen = lists.tagsInOrder(form).filter { it in rows }.distinct()
        assertEquals("and lists them in the order Model, Effort, Permissions, Speed", rows.filter { it in seen }, seen)
        // The iPhone flings the form back to its top. The swipe that does it there runs on past the
        // form's top here, and the rest of it pulls the sheet itself down, so the form is put back
        // at its top directly.
        lists.scrollToTop(form)
        app.attach("04-new-session")
    }

    /**
     * Amendment A37 and rule 19: the browser can make a folder where a session will work. A name
     * something already has is refused beside the name and left to be corrected; the folder that is
     * made becomes the listing on screen, and the same Select picks it as the working directory.
     */
    @Test
    fun directoryPickerMakesAFolderAndPicksIt() = sheet("testDirectoryPickerMakesAFolderAndPicksIt") { app, lists ->
        assertTrue("the working directory offers the browser", lists.scrollDown("newsession.browse"))
        app.tap("newsession.browse")

        app.waitFor("dirs.select")
        // New folder is offered once there is a listing to make one in.
        app.await("the listing") { lists.isEnabled("dirs.newFolder") }
        app.tap("dirs.newFolder")

        // `gateway` is a directory the demo device already has.
        app.waitFor("dirs.folderName")
        app.node("dirs.folderName").performTextInput("gateway")
        app.tap("dirs.create")
        lists.waitText("A folder with that name already exists.")
        assertEquals("and the name is kept for editing", "gateway", lists.editable("dirs.folderName"))
        app.attach("53-directory-new-folder-clash")

        // Cancel takes the row away without making anything.
        app.tap("dirs.cancelFolder")
        app.waitForAbsence("dirs.folderName")

        app.tap("dirs.newFolder")
        app.waitFor("dirs.folderName")
        app.node("dirs.folderName").performTextInput("round-41")
        app.attach("54-directory-new-folder")
        app.tap("dirs.create")

        // The device answered with the new directory's listing, so the picker now stands in it: its
        // name is the title, and it holds nothing.
        lists.waitText("No subdirectories here.")
        assertTrue("the picker stands in the folder it made", lists.hasText("round-41"))
        app.attach("55-directory-picker-in-new-folder")
        app.tap("dirs.select")

        app.waitForAbsence("dirs.select")
        app.await("the working directory is the folder that was just made") { lists.editable("newsession.cwd") == "/Users/me/dev/round-41" }
    }
}
