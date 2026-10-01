package com.junbingao.remotecontrol.android.harness

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Wait for [condition] while the app runs on real time — the demo gateway's scripts on their own
 * threads, the main thread idled between looks — for a test that drives the whole app rather than
 * a store on the test's clock. Fails with [what] when [timeoutMillis] pass first.
 */
fun ComposeTestRule.awaitOnRealTime(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (true) {
        waitForIdle()
        if (condition()) return
        check(System.currentTimeMillis() < end) { "still waiting after $timeoutMillis ms for $what" }
        Thread.sleep(50)
    }
}
