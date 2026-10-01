package com.junbingao.remotecontrol.android.screens.chat

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
 * The conversation's states in both languages and both appearances, for laying beside the iPhone's
 * English pictures and for reading the Chinese and dark ones on their own: the transcript at
 * Detailed, the composer's cards, dictation, the Up next sheet and the cards that wait on the reader.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun everywhere(arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) {
        for (variant in IPhone.variants) {
            DemoApp(compose, "chatPictures", arguments, variant).use { app -> ChatDriver(compose, app).steps() }
        }
    }

    @Test
    fun detailedTranscript() = everywhere {
        chooseDetailedTranscript()
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        attach("detailed-transcript")
        tap("chat.todos")
        waitFor("chat.todoList", 10_000)
        attach("todo-popover")
    }

    @Test
    fun commandPanel() = everywhere {
        openSession(DemoFixtures.piSessionID)
        type("composer.prompt", "/")
        waitFor("composer.commands", 10_000)
        attach("command-panel")
    }

    @Test
    fun modelCard() = everywhere {
        openSession(DemoFixtures.codexSharedSessionID)
        tap("composer.modelCard")
        waitFor("composer.speed", 10_000)
        attach("model-card")
    }

    @Test
    fun dictation() = everywhere {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        tap("composer.voice")
        waitFor("voice.done", 15_000)
        await("listening") { isEnabled("voice.done") }
        pause(500)
        attach("voice-listening")
    }

    @Test
    fun queueSheet() = everywhere(DemoApp.launchArguments + "--demo-queue") {
        openSession(DemoFixtures.liveSessionID)
        waitFor("composer.queue", 10_000)
        attach("queue-chip")
        tap("composer.queue")
        await("the list", 10_000) { nodes("queue.entry").size == 3 }
        attach("queue-sheet")
    }

    @Test
    fun approvalAndQuestion() = everywhere {
        openSession(DemoFixtures.approvalSessionID)
        waitFor("chat.approval", 15_000)
        attach("approval-card")
        tap("nav.back")
        openSession(DemoFixtures.sharedSessionID)
        await("the attached CLI's question", 20_000) { app.model.chat?.pendingQuestion != null && exists("chat.question") }
        attach("question-card")
    }

    @Test
    fun pausedSession() = everywhere {
        openSession(DemoFixtures.pausedSessionID)
        waitFor("chat.resumeNotice", 15_000)
        attach("resume-notice")
    }
}
