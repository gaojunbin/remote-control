package com.junbingao.remotecontrol.android.shell

import android.content.Context
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.screens.alerts.PushController
import com.junbingao.remotecontrol.android.screens.alerts.TurnNotifier
import com.junbingao.remotecontrol.core.persistence.DraftStore
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.InstalledApp
import com.junbingao.remotecontrol.core.state.MemoryUserDefaults
import com.junbingao.remotecontrol.core.state.SessionStore
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.state.UserDefaults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An app model as a test drives one: the core's stores on the test's clock, the demo gateway on
 * it too, nothing written outside [root], and no gateway to reach unless the demo is entered.
 */
internal class AppModelHarness(private val context: Context, private val root: File) {
    val drafts = DraftStore(root.resolve("drafts"))

    fun model(scope: TestScope, arguments: List<String> = emptyList(), defaults: UserDefaults = MemoryUserDefaults()): AppModel {
        val connection = ConnectionStore(
            scope.backgroundScope, InstalledApp.android, LocalCache(root.resolve("cache")),
            makeAPI = { error("this test reaches no gateway") },
        )
        return AppModel(
            tasks = scope.backgroundScope,
            connection = connection,
            settings = SettingsStore(defaults),
            sessions = SessionStore(defaults),
            push = PushController(context),
            turns = TurnNotifier(context),
            drafts = drafts,
            options = LaunchOptions(arguments, debug = true),
            demoIsolation = StandardTestDispatcher(scope.testScheduler),
        )
    }
}

/**
 * Let the test's clock run until [condition] holds or [timeout] of it has passed — the iPhone's
 * checks' `settle`, on virtual time, so a scripted delay costs nothing. The drafts and the cache
 * write files on threads of their own, so real time is given too, a few milliseconds a step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle(timeout: Duration = 10.seconds, condition: () -> Boolean) {
    val end = testScheduler.currentTime + timeout.inWholeMilliseconds
    val realEnd = System.nanoTime() + REAL_BUDGET_NANOS
    runCurrent()
    while (!condition() && (testScheduler.currentTime < end || System.nanoTime() < realEnd)) {
        if (testScheduler.currentTime < end) advanceTimeBy(50)
        runCurrent()
        Thread.sleep(2)
    }
}

/** Let what is under way finish — a second of the test's clock, and the files written meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.drain() {
    repeat(100) {
        advanceTimeBy(10)
        runCurrent()
        Thread.sleep(2)
    }
}

private const val REAL_BUDGET_NANOS = 5_000_000_000L
