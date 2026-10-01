package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.terminal.TerminalScreen
import com.junbingao.remotecontrol.win.users.UsersModel
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * The settings feature's launch hook: the notifier that posts this PC's own notifications, the
 * accounts list the Users screen keeps between visits, and the terminals a sign-out ends — one set
 * per model, found again with `state(of)`.
 */
object SettingsFeature {
    fun install(on: WinAppModel) {
        state(of = on)
    }

    /**
     * The feature's state for this model, made and wired the first time it is asked for (which
     * `install` does when the model is built). Nothing in it holds the model but weakly, so a
     * model that has gone — a render's, a test's — takes its entry with it.
     */
    fun state(of: WinAppModel): SettingsFeatureState = synchronized(states) {
        states.getOrPut(of) {
            SettingsFeatureState(model = of, platform = NotificationPlatforms.make(of)).also { wire(it, to = of) }
        }
    }

    private val states = WeakHashMap<WinAppModel, SettingsFeatureState>()

    private fun wire(state: SettingsFeatureState, to: WinAppModel) {
        to.onSessionTransition { previous, current -> state.notifier.sessionChanged(from = previous, to = current) }
        to.connection.addFrameHandler("settings.notifications") { frame -> state.notifier.receive(frame) }
        to.onSignOut { state.signOut() }
        to.tasks.launch { state.notifier.refresh() }
    }
}

/** What the settings feature keeps for the life of one model. */
class SettingsFeatureState(model: WinAppModel, platform: NotificationPlatform) {
    val notifier = TurnNotifier(model = model, platform = platform)
    val users = UsersModel()

    /** The terminal pages on screen, whose shells a sign-out ends while the connection still names the account. */
    private val terminals = mutableSetOf<TerminalScreen>()

    fun track(terminal: TerminalScreen) {
        terminals += terminal
    }

    fun forget(terminal: TerminalScreen) {
        terminals -= terminal
    }

    /** `web/src/stores/signOut.ts`: nothing of the account stays behind. */
    suspend fun signOut() {
        for (terminal in terminals.toList()) terminal.end()
        terminals.clear()
        notifier.signedOut()
        users.reset()
    }
}
