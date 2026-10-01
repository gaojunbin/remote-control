package com.junbingao.remotecontrol.android.harness

/**
 * How long a step that drives the whole app may wait on real time. The ported tests keep the
 * iPhone's own bounds — 20 seconds for a screen to come, 15 for a turn to answer — which hold on
 * an idle machine; with another build beside this one, a JVM drawing the whole app at the iPhone's
 * size can take several times as long to get to the same point, and a bound that fails there says
 * nothing about the app. So every bound is stretched by [slack] (the `rc.test.slack` system
 * property, three unless set): a step that passes still returns the moment its condition holds,
 * and only a step that would have failed waits longer to say so.
 */
object RealTime {
    val slack: Long = System.getProperty("rc.test.slack")?.toLongOrNull()?.coerceAtLeast(1) ?: 3

    /** The bound a step waits within for [millis] as the test states it. */
    fun bound(millis: Long): Long = millis * slack
}
