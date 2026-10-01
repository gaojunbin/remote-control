package com.junbingao.remotecontrol.win.preview

import androidx.compose.runtime.CompositionLocalProvider
import com.junbingao.remotecontrol.win.app.RootView
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.LocalShowsCaret
import com.junbingao.remotecontrol.win.preview.scenarios.PreviewContext
import com.junbingao.remotecontrol.win.preview.scenarios.PreviewScenario
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Renders scenarios one at a time, each in a scene of its own, and writes `<out>/<name>.png`.
 * Everything runs on the renderer's one thread (`context`), which the scene's effects run on too.
 *
 * Every render is ephemeral: nothing of the person's is read or written. Stage 2 builds a fresh
 * app model per scenario here — signed in through the core on `--demo` or `--gateway`, taken to
 * the scenario's route — and draws the real root; until then a scenario draws its `content`.
 */
class PreviewRenderer(private val arguments: PreviewArguments, private val context: CoroutineContext) {
    suspend fun render(scenario: PreviewScenario, directory: File): File = withContext(context) {
        val draw = scenario.content ?: throw ArgumentError("${scenario.name}: a route needs the app model, which stage 2 brings")
        InterfaceLanguageSource.current = scenario.language ?: arguments.language ?: InterfaceLanguage.en
        val preview = PreviewContext(arguments.gatewayUrl)
        scenario.setup(preview)
        val width = scenario.width ?: arguments.width
        val height = scenario.height ?: arguments.height
        val scene = PreviewScene(width, height, arguments.scale, context) {
            CompositionLocalProvider(LocalPreviewStage provides scenario.stage, LocalShowsCaret provides false) {
                RootView { draw(preview) }
            }
        }
        scene.use {
            it.frame()
            scenario.prepare(preview)
            it.settle(scenario.settle)
            val file = File(directory, "${scenario.name}.png")
            file.writeBytes(it.png())
            file
        }
    }
}
