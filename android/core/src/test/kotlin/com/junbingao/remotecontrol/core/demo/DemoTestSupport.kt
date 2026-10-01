@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/**
 * A demo gateway on the test's virtual clock, so every scripted delay is the scheduler's and none
 * is waited for. RCCore's suites build theirs with `resumeDelay: nil` wherever the archived
 * session moving would get in the way; here that is the default.
 */
internal fun TestScope.demoGateway(
    echoDelay: Duration = DemoGateway.defaultEchoDelay,
    resumeDelay: Duration? = null,
    registrationOpen: Boolean = false,
    minimumAppVersion: String = AppBuild.version,
    changesPreferencesElsewhere: Boolean = true,
    elsewhereDelay: Duration = DemoGateway.defaultElsewhereDelay,
    holdsQueue: Boolean = false,
): DemoGateway = DemoGateway(echoDelay = echoDelay, resumeDelay = resumeDelay, registrationOpen = registrationOpen,
                             minimumAppVersion = minimumAppVersion,
                             changesPreferencesElsewhere = changesPreferencesElsewhere,
                             elsewhereDelay = elsewhereDelay, holdsQueue = holdsQueue,
                             isolation = StandardTestDispatcher(testScheduler))

/**
 * Every scripted moment run through, and everything it published read. `advanceUntilIdle` stops
 * once only background work is left, and the [EventLog] reads in the background, so the frames of
 * a script's last moment are read by the `runCurrent` after it.
 */
internal fun TestScope.settle() {
    advanceUntilIdle()
    runCurrent()
}

/** Everything a gateway publishes, read as a store's pump reads it. */
internal class EventLog(scope: CoroutineScope, gateway: DemoGateway) {
    val events = mutableListOf<GatewayEvent>()

    init {
        scope.launch { gateway.events.collect { events.add(it) } }
    }

    val states: List<ConnectionState> get() = events.mapNotNull { (it as? GatewayEvent.State)?.state }

    val frames: List<AppFrame> get() = events.mapNotNull { (it as? GatewayEvent.Frame)?.frame }

    val hello: HelloFrame? get() = frames.filterIsInstance<AppFrame.Hello>().lastOrNull()?.hello

    /** One session's events, in the order they were published. */
    fun events(sessionID: String): List<SessionEvent> =
        frames.filterIsInstance<AppFrame.SessionEvent>().filter { it.sessionID == sessionID }.map { it.event }

    /** One session's summaries, in the order they were published. */
    fun updates(sessionID: String): List<Session> =
        frames.filterIsInstance<AppFrame.SessionUpdated>().map { it.session }.filter { it.sessionID == sessionID }

    /** The line the device last said it holds for a session (A43), or nothing before it said any. */
    fun queue(sessionID: String): List<QueuedMessage> =
        events(sessionID).mapNotNull { (it.body as? SessionEventBody.Queue)?.payload?.pending }.lastOrNull().orEmpty()

    fun clear() = events.clear()
}
