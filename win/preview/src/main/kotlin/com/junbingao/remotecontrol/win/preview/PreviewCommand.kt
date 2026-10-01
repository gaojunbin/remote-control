package com.junbingao.remotecontrol.win.preview

import com.junbingao.remotecontrol.win.preview.scenarios.PreviewScenarios
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** Reads the command line, renders what it names and says what it wrote. */
object PreviewCommand {
    fun run(argv: List<String>): Int {
        val arguments = try {
            PreviewArguments(argv)
        } catch (error: ArgumentError) {
            System.err.println("${error.message}\n${PreviewArguments.usage}")
            return 64
        }
        val registry = PreviewScenarios.all
        if (arguments.list) {
            for (scenario in registry) println(scenario.name)
            return 0
        }
        val unknown = arguments.scenarios.toSet() - registry.map { it.name }.toSet()
        if (unknown.isNotEmpty()) {
            System.err.println("unknown scenario: ${unknown.sorted().joinToString(", ")}")
            return 64
        }
        val chosen = if (arguments.all) registry else registry.filter { it.name in arguments.scenarios }
        val out = arguments.out ?: return 64
        out.mkdirs()
        val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "preview").apply { isDaemon = true } }
        val context = executor.asCoroutineDispatcher()
        val renderer = PreviewRenderer(arguments, context)
        var failures = 0
        runBlocking(context) {
            for (scenario in chosen) {
                try {
                    val file = renderer.render(scenario, out)
                    println("rendered ${scenario.name} → ${file.path}")
                } catch (error: Exception) {
                    failures++
                    System.err.println("failed ${scenario.name}: ${error.message ?: error}")
                }
            }
        }
        executor.shutdownNow()
        println("${chosen.size - failures} of ${chosen.size} scenarios rendered")
        return if (failures == 0) 0 else 1
    }
}
