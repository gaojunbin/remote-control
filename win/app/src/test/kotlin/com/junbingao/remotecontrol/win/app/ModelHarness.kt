package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * A model on a thread of its own, as the window's main thread is the app's: the model's work, the
 * stores' state and the test's reads all run there, and the harness stands in for the frame clock
 * that tells snapshot observers what changed.
 */
internal class ModelHarness(options: LaunchOptions) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "model").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val model: WinAppModel = runBlocking(dispatcher) { WinAppModel(options = options, tasks = scope) }

    fun <T> run(block: suspend WinAppModel.() -> T): T = runBlocking(dispatcher) { model.block() }

    /** Wait until `condition` holds, for at most five seconds. */
    suspend fun waitFor(condition: () -> Boolean): Boolean {
        repeat(200) {
            Snapshot.sendApplyNotifications()
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    override fun close() {
        scope.cancel()
        model.discardEphemeralState()
        executor.shutdownNow()
    }
}
