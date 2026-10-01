package com.junbingao.remotecontrol.win.preview.scenarios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.agent
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * One picture the renderer takes: a place in the app, the size of the window, a stage the views
 * can read to show a state that takes a click, and what to do before the picture is taken — the
 * Mac renderer's `PreviewScenario`, field for field, so a scenario's two pictures compare.
 *
 * A feature's scenarios go in its own file beside this one (`ChatScenarios`, `ComposerScenarios`,
 * `ListsScenarios`, `SettingsScenarios`), and `PreviewScenarios.all` joins them.
 */
data class PreviewScenario(
    val name: String,
    val route: Route = Route.Landing,
    /** The window's size in CSS px; null is the command line's (1280 × 860). */
    val width: Int? = null,
    val height: Int? = null,
    /** Put in the composition as `LocalPreviewStage`. */
    val stage: String? = null,
    val account: Account = Account.signedIn,
    val language: InterfaceLanguage? = null,
    /** How long the scene is left to draw before the picture is taken. */
    val settle: Duration = 900.milliseconds,
    /** Run once the account is reached, before the scene exists: what the first frame must already read. */
    val setup: suspend (PreviewContext) -> Unit = {},
    /** Run after the scene shows the route, before it settles. */
    val prepare: suspend (PreviewContext) -> Unit = {},
    /**
     * A view drawn in place of the route, for a scenario about one view. It reads what `setup`
     * opened through the context — a conversation, say.
     */
    val content: (@Composable (PreviewContext) -> Unit)? = null,
) {
    /** Who the window is signed in as. */
    enum class Account {
        /** Signed in, on whichever gateway the command line named. */
        signedIn,

        /** Nobody yet: the sign-in form, on the command line's gateway (the offline demo's account form under `--demo`). */
        signedOut,

        /** The offline demo, with a minimum above this build (A46). */
        updateRequired,
    }
}

/**
 * What a scenario's preparation can reach: the model the scene draws, the gateway the command line
 * named, and a way to wait for the gateway.
 */
class PreviewContext(
    val model: WinAppModel,
    /** The gateway the command line named, or null under `--demo`. */
    val gateway: URI?,
    /** What `setup` opened for `content` to draw. One per render. */
    val opened: PreviewOpened,
) {
    /** The conversation `openChat` opened, if it has. */
    val chat: ChatStore? get() = opened.chat

    /**
     * Open one session's conversation, for a scenario that draws a piece of it alone — the
     * composer, a block — while the page that normally opens it belongs to another feature. Call it
     * from `setup`; `content` reads it back as `chat`. Null when the gateway has no such session.
     */
    suspend fun openChat(deviceId: String, sessionId: String): ChatStore? {
        val session = model.connection.sessions.firstOrNull { it.deviceID == deviceId && it.sessionID == sessionId } ?: return null
        val channel = model.connection.channel ?: return null
        val store = ChatStore(session = session, channel = channel, tasks = model.tasks)
        store.agent = model.agent(session)
        store.detailSource = { model.settings.timelineDetail }
        model.connection.addFrameHandler("preview-chat") { frame -> store.receive(frame) }
        store.open()
        opened.chat = store
        return store
    }

    /**
     * Wait until `condition` holds, for at most `timeout`. Before a scene draws there is no frame to
     * tell the model's observers what the stores changed, so the wait tells them itself.
     */
    suspend fun wait(timeout: Duration = 5.seconds, condition: () -> Boolean): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            Snapshot.sendApplyNotifications()
            if (condition()) return true
            if (start.elapsedNow() >= timeout) return false
            delay(50)
        }
    }
}

/** What a render's `setup` opened, held for the length of the render. */
class PreviewOpened {
    var chat: ChatStore? = null

    suspend fun close(connection: ConnectionStore) {
        val chat = chat ?: return
        connection.removeFrameHandler("preview-chat")
        chat.close()
        this.chat = null
    }
}

object PreviewScenarios {
    /** Every scenario, in the order `--all` renders them. */
    val all: List<PreviewScenario>
        get() = FoundationScenarios.all + ChatScenarios.all + ComposerScenarios.all + ListsScenarios.all + SettingsScenarios.all
}
