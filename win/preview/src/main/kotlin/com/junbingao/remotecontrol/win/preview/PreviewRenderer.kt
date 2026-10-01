package com.junbingao.remotecontrol.win.preview

import androidx.compose.runtime.CompositionLocalProvider
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.RootView
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.WithAppModel
import com.junbingao.remotecontrol.win.app.signIn
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.LocalShowsCaret
import com.junbingao.remotecontrol.win.preview.scenarios.PreviewContext
import com.junbingao.remotecontrol.win.preview.scenarios.PreviewOpened
import com.junbingao.remotecontrol.win.preview.scenarios.PreviewScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Renders scenarios one at a time, each in a fresh ephemeral model and a scene of its own, and
 * writes `<out>/<name>.png`. Everything runs on the renderer's one thread (`context`): the model's
 * work, the stores' snapshot state and the scene's effects.
 */
class PreviewRenderer(private val arguments: PreviewArguments, private val context: CoroutineContext) {
    suspend fun render(scenario: PreviewScenario, directory: File): File = withContext(context) {
        val tasks = CoroutineScope(SupervisorJob() + context)
        val model = WinAppModel(options = options(scenario), tasks = tasks)
        try {
            reach(scenario, model)
            model.router.replace(scenario.route)
            val preview = PreviewContext(model = model, gateway = arguments.gatewayUrl, opened = PreviewOpened())
            scenario.setup(preview)
            val width = scenario.width ?: arguments.width
            val height = scenario.height ?: arguments.height
            val scene = PreviewScene(width, height, arguments.scale, context) {
                CompositionLocalProvider(LocalPreviewStage provides scenario.stage, LocalShowsCaret provides false) {
                    WithAppModel(model) {
                        val draw = scenario.content
                        if (draw != null) RootView { draw(preview) } else RootView()
                    }
                }
            }
            val file = scene.use {
                it.frame()
                it.drawing { scenario.prepare(preview) }
                it.settle(scenario.settle)
                File(directory, "${scenario.name}.png").apply { writeBytes(it.png()) }
            }
            preview.opened.close(model.connection)
            if (model.isSignedIn && !model.isDemo) model.signOut()
            file
        } finally {
            tasks.cancel()
            model.discardEphemeralState()
        }
    }

    /** Every run is ephemeral: nothing of the person's is read or written. */
    private fun options(scenario: PreviewScenario): LaunchOptions {
        val demo = arguments.source == PreviewArguments.Source.Demo
        return LaunchOptions(
            demo = (demo && scenario.account == PreviewScenario.Account.signedIn) || scenario.account == PreviewScenario.Account.updateRequired,
            demoAccount = demo && scenario.account == PreviewScenario.Account.signedOut,
            demoUpdateRequired = scenario.account == PreviewScenario.Account.updateRequired,
            ephemeral = true,
            language = scenario.language ?: arguments.language,
        )
    }

    /** Sign in, or stay at the form, as the scenario asks. */
    private suspend fun reach(scenario: PreviewScenario, model: WinAppModel) {
        val context = PreviewContext(model = model, gateway = arguments.gatewayUrl, opened = PreviewOpened())
        when (scenario.account) {
            PreviewScenario.Account.signedIn -> {
                val source = arguments.source
                if (source is PreviewArguments.Source.Gateway) {
                    model.signIn(origin = source.url.toString(), username = source.username, password = source.password)
                    if (!model.isSignedIn) throw ArgumentError("could not sign in to ${source.url} as ${source.username}")
                }
                model.restoreOrPrompt()
                if (!context.wait(timeout = 10.seconds) { model.connection.hasSnapshot }) {
                    throw ArgumentError("${scenario.name}: no hello from the gateway")
                }
            }
            PreviewScenario.Account.signedOut -> model.restoreOrPrompt()
            PreviewScenario.Account.updateRequired -> {
                model.restoreOrPrompt()
                if (!context.wait { model.connection.updateRequired != null }) {
                    throw ArgumentError("${scenario.name}: the demo asked for no update")
                }
            }
        }
    }
}
