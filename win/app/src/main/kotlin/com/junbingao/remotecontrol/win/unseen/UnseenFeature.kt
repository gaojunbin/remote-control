package com.junbingao.remotecontrol.win.unseen

import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.SeenReporter
import com.junbingao.remotecontrol.core.state.UnseenMark
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Amendment A47's launch hook: the conversation open in the window that has focus is reported seen
 * whenever it carries a red dot, and the icon's badge follows the number of dots — for the life of
 * the model, one set per model, found again with `state(of)`.
 *
 * The reporter and the keeper's following run in the model's own scope and end with it. Nothing the
 * feature keeps holds the model but weakly, so a model that has gone — a render's, a test's — takes
 * its entry with it.
 */
object UnseenFeature {
    fun install(on: WinAppModel) {
        state(of = on)
    }

    /** The feature's state for this model, made and started the first time it is asked for (which `install` does when the model is built). */
    fun state(of: WinAppModel): UnseenFeatureState = synchronized(states) {
        states.getOrPut(of) { UnseenFeatureState(of).also { start(it, on = of) } }
    }

    private val states = WeakHashMap<WinAppModel, UnseenFeatureState>()

    private fun start(state: UnseenFeatureState, on: WinAppModel) {
        val model = WeakReference(on)
        SeenReporter(on.connection, on.tasks) { model.get()?.conversationInFront }.start()
        state.badge.start(on.tasks)
    }
}

/** What the feature keeps for the life of one model. */
class UnseenFeatureState(model: WinAppModel) {
    private val reference = WeakReference(model)

    /** The dots are the signed-in account's, so nobody signed in has none. */
    val badge = TaskbarBadgeKeeper(InertTaskbarBadge()) {
        val app = reference.get()
        if (app == null || !app.isSignedIn) 0 else UnseenMark.count(app.connection.sessions, excluding = app.conversationInFront)
    }
}

/** The conversation the person has in front of them: the one the route shows, while the window it is in has focus. */
val WinAppModel.conversationInFront: String?
    get() {
        if (!isWindowActive) return null
        val chat = router.route as? Route.Chat ?: return null
        return "${chat.deviceId}/${chat.sessionId}"
    }

/**
 * Whether a row draws the session's red dot: it carries the mark and is not the conversation in
 * front of the person, which is being looked at while the `session.seen` that clears the mark is on
 * its way — the web's `useFront` rule, so the dot never flickers on the row that is open.
 */
fun WinAppModel.showsUnseenDot(session: Session): Boolean = session.unseen && session.id != conversationInFront
