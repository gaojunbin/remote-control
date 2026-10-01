package com.junbingao.remotecontrol.android.harness

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.junbingao.remotecontrol.android.design.FieldScrollProbe
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.screens.lock.AppLockWindow
import com.junbingao.remotecontrol.android.shell.AppEnvironment
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.RootView
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import com.junbingao.remotecontrol.core.state.L10n as CoreL10n

/**
 * The whole app in its offline demo, launched as the iPhone's UI tests launch theirs, for a test
 * ported from `ios/UITests/`: the arguments of `RemoteControlUITests.setUp` ([launchArguments]),
 * or of its `launchSignedOut` ([signedOut]), with what the test adds; the model built as the
 * process builds its one; the root view and the lock over it, as `MainActivity` draws them, in an
 * activity of its own at the iPhone 17's size and safe area, in [variant]'s language and
 * appearance.
 *
 * It runs on real time, as the app does: the demo gateway's scripts run on their own threads, so a
 * step waits for what it looks at ([await], [waitFor]) rather than advancing a clock. The screens
 * carry the iPhone's accessibility identifiers as test tags, so a step is `tap("session.…")` or
 * Compose's own API on [node]; [attach] is the iPhone test's `attach(name:)`, drawing
 * `<test>/<name>-<variant>.png` to lay beside the iPhone's `<test>__<name>.png`. The test's
 * compose rule is `createEmptyComposeRule()`, and the app is closed at the end — `use { app -> … }`.
 */
class DemoApp(
    private val compose: ComposeTestRule,
    /** The iPhone test's own name, `testSessionsAndChat`: the folder this run's pictures go in. */
    private val test: String,
    arguments: List<String> = launchArguments,
    val variant: Variant = Variant(L10n.english, dark = false),
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tasks = MainScope()
    private val scenario = ActivityScenario.launch(ComponentActivity::class.java)

    /** The app's one model, built from the launch; a test reads the stores through it. */
    val model: AppModel = AppEnvironment.model(context, LaunchOptions(arguments + language(arguments), debug = true), tasks)

    init {
        FieldScrollProbe.enable(model.options.fieldScrollProbe)
        scenario.onActivity { activity ->
            activity.setContent {
                IPhoneFrame(variant, over = { AppLockWindow(model.isLocked) { model.isLocked = false } }) { RootView(model) }
            }
        }
    }

    /** Wait until [condition] holds, failing with [what] after [timeoutMillis]. */
    fun await(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) =
        compose.awaitOnRealTime(what, timeoutMillis, condition)

    /** Whether anything carrying [tag] is on screen now, asked once — `exists`. */
    fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** `waitForExistence(timeout:)`, failing rather than answering false. */
    fun waitFor(tag: String, timeoutMillis: Long = 20_000) = await("“$tag” on screen", timeoutMillis) { exists(tag) }

    /** `waitForNonExistence`: a row is removed by a reply from the gateway, so it is still there when the tap returns. */
    fun waitForAbsence(tag: String, timeoutMillis: Long = 20_000) = await("“$tag” gone", timeoutMillis) { !exists(tag) }

    /** The first element carrying [tag] — `firstMatch` — for Compose's own assertions and gestures. */
    fun node(tag: String): SemanticsNodeInteraction = compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()

    /** Wait for [tag], then tap it. */
    fun tap(tag: String) {
        waitFor(tag)
        node(tag).performClick()
    }

    /** The system's Back: the topmost presentation first, then the stack. */
    fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Draw what is on screen now as the picture called [name]. */
    fun attach(name: String) = compose.capture(test, name, variant)

    override fun close() {
        tasks.cancel()
        scenario.close()
        FieldScrollProbe.enable(false)
        L10n.use(L10n.english)
        CoreL10n.use(InterfaceLanguage.en)
    }

    /** A Chinese picture is a run in Chinese: the language is pinned unless the test pins one itself. */
    private fun language(arguments: List<String>): List<String> =
        if (variant.language == L10n.chinese && arguments.none { it.startsWith("--language=") }) listOf("--language=${L10n.chinese}") else emptyList()

    companion object {
        /** `RemoteControlUITests.setUp`: the demo, a fresh start, the scripted dictation. */
        val launchArguments = listOf("--ui-testing", "--demo", "--reset-state", "--voice-preview")

        /** `launchSignedOut`: the sign-in form, with the offline gateway behind it. */
        val signedOut = listOf("--ui-testing", "--demo-account", "--reset-state")
    }
}
