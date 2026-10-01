package com.junbingao.remotecontrol.win.preview.scenarios

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import kotlinx.coroutines.delay
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One picture the renderer takes: a place in the app, the size of the window, a stage the views
 * can read to show a state that takes a click, and what to do before the picture is taken — the
 * Mac renderer's `PreviewScenario`, field for field, so a scenario's two pictures compare.
 *
 * A feature's scenarios go in their own file beside this one, and `PreviewScenarios.all` joins
 * them. A scenario without `content` draws its route, which needs the app model: stage 2 signs the
 * renderer in (`--demo` on the offline demo, `--gateway` on a real one) and draws the root.
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
    /** A view drawn in place of the route, for a scenario about one view. */
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
 * What a scenario's preparation can reach: the gateway the command line named, and a way to wait
 * for something to hold. Stage 2 adds the app model and the conversation a scenario opens.
 */
class PreviewContext(
    /** The gateway the command line named, or null under `--demo`. */
    val gateway: URI?,
) {
    /** Wait until `condition` holds, for at most `timeout`. */
    suspend fun wait(timeout: Duration = 5.seconds, condition: () -> Boolean): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (start.elapsedNow() >= timeout) return false
            delay(50)
        }
        return true
    }
}

object PreviewScenarios {
    /** Every scenario, in the order `--all` renders them. */
    val all: List<PreviewScenario> get() = FoundationScenarios.all
}
