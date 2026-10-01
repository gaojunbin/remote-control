package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Sessions list, the New session sheet and the directory picker in Chinese and dark ([Pictures]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SessionsPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val list = "sessions.list"

    private fun sessions(test: String, steps: (DemoApp, ListsDriver) -> Unit) = Pictures.each(compose, test) { app, lists ->
        app.waitFor("sessions.new")
        app.waitFor("session.demo-session-auth")
        steps(app, lists)
    }

    private fun sheet(test: String, steps: (DemoApp, ListsDriver) -> Unit) = sessions(test) { app, lists ->
        app.tap("sessions.new")
        app.waitFor("newsession.start")
        steps(app, lists)
    }

    @Test
    fun theListAtRest() = sessions("testSessionRowsNameTheirOriginAndTheDotsAreExplainedOnce") { app, _ ->
        app.attach("31-origin-and-legend")
    }

    @Test
    fun anArchiveOpenAndSearched() = sessions("testDeviceArchiveOpensOnTapAndOnSearch") { app, lists ->
        val archive = "sessions.archive.demo-ci-runner"
        val archived = "session.demo-session-otlp"
        lists.scrollDown(archive)
        app.tap(archive)
        lists.scrollDown(archived)
        lists.swipeUp(list)
        app.attach("15-archive-open")

        lists.scrollDown(archive)
        app.tap(archive)
        app.waitForAbsence(archived)
        repeat(6) { if (!app.exists("search.field")) lists.swipeDown(list) }
        app.tap("search.field")
        app.node("search.field").performTextInput("OTLP")
        app.waitFor(archived)
        app.attach("16-archive-search")
    }

    @Test
    fun closingAWorkingSession() = sessions("testClosingASessionAsksOnlyWhileTheAgentIsWorking") { app, lists ->
        lists.revealRowActions("session.demo-session-auth")
        lists.tapShown("session.close.demo-session-auth")
        app.waitFor("session.close.confirm")
        app.attach("61-session-close-dialog")
        app.tap("alert.cancel")
    }

    @Test
    fun oneAgentChosen() = sessions("testAgentFilterNarrowsTheListToOneAgent") { app, _ ->
        app.tap("sessions.agentFilter")
        app.tap("sessions.agentFilter.codex")
        app.waitForAbsence("session.demo-session-auth")
        app.attach("18-agent-filter")
    }

    @Test
    fun theFootOfTheList() = sessions("testSessionsListEndsWithTheNewSessionButtonInTheBottomBar") { app, lists ->
        lists.scrollDown("sessions.summary")
        app.attach("69-sessions-bottom-bar")
    }

    @Test
    fun theNewSessionSheet() = sheet("testNewSessionSheetOffersDeviceAndAgent") { app, lists ->
        app.tap("newsession.agent.codex")
        for (row in listOf("newsession.model", "newsession.effort", "newsession.permissions", "newsession.speed")) lists.scrollDown(row)
        app.attach("52-new-session-settings")
        lists.scrollToTop("newsession.form")
        app.attach("04-new-session")
    }

    @Test
    fun everyAgentOnTheSheet() = sheet("testNewSessionSheetMarksEveryAgentAndDrawsOnlyWhatItHas") { app, _ ->
        app.waitFor("newsession.agent.pi")
        app.attach("82-new-session-agents")
    }

    @Test
    fun theDirectoryPicker() = sheet("testDirectoryPickerMakesAFolderAndPicksIt") { app, lists ->
        lists.scrollDown("newsession.browse")
        app.tap("newsession.browse")
        app.await("the listing") { lists.isEnabled("dirs.newFolder") }
        app.tap("dirs.newFolder")
        app.waitFor("dirs.folderName")
        app.node("dirs.folderName").performTextInput("gateway")
        app.tap("dirs.create")
        app.waitFor("dirs.folderError")
        app.attach("53-directory-new-folder-clash")
        app.tap("dirs.cancelFolder")
        app.waitForAbsence("dirs.folderName")
        app.tap("dirs.newFolder")
        app.waitFor("dirs.folderName")
        app.node("dirs.folderName").performTextInput("round-41")
        app.tap("dirs.create")
        app.await("the new folder's listing") { lists.hasText("round-41") && !app.exists("dirs.folderName") }
        app.attach("55-directory-picker-in-new-folder")
    }
}
