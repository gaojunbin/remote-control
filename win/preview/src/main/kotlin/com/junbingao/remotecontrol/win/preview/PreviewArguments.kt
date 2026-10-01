package com.junbingao.remotecontrol.win.preview

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import java.io.File
import java.net.URI

/**
 * `./gradlew :preview:run --args="[--demo | --gateway <url> --username <u> --password <p>]
 * (--scenario <name>… | --all | --list) [--width W --height H] [--scale 1|2]
 * [--language en|zh-Hans] --out <dir>"` — the Mac renderer's command line.
 */
class PreviewArguments(arguments: List<String>) {
    sealed interface Source {
        data object Demo : Source
        data class Gateway(val url: URI, val username: String, val password: String) : Source
    }

    var source: Source = Source.Demo
        private set
    val scenarios = mutableListOf<String>()
    var all = false
        private set
    var list = false
        private set
    var width = 1280
        private set
    var height = 860
        private set
    var scale = 2
        private set
    var language: InterfaceLanguage? = null
        private set
    var out: File? = null
        private set

    init {
        var gateway: URI? = null
        var username = ""
        var password = ""
        val queue = ArrayDeque(arguments)
        fun value(flag: String): String = queue.removeFirstOrNull() ?: throw ArgumentError("$flag needs a value")
        while (queue.isNotEmpty()) {
            when (val flag = queue.removeFirst()) {
                "--demo" -> source = Source.Demo
                "--gateway" -> gateway = runCatching { URI(value(flag)) }.getOrElse { throw ArgumentError("--gateway is not a URL") }
                "--username" -> username = value(flag)
                "--password" -> password = value(flag)
                "--scenario" -> scenarios += value(flag).split(',').filter { it.isNotEmpty() }
                "--all" -> all = true
                "--list" -> list = true
                "--width" -> width = value(flag).toDoubleOrNull()?.toInt() ?: 1280
                "--height" -> height = value(flag).toDoubleOrNull()?.toInt() ?: 860
                "--scale" -> scale = value(flag).toIntOrNull() ?: 2
                "--language" -> language = InterfaceLanguage.of(value(flag))
                "--out" -> out = File(value(flag))
                else -> {
                    // `-Key value` is a user default on the Mac; nothing here reads one.
                    if (flag.startsWith("-") && !flag.startsWith("--")) queue.removeFirstOrNull() else throw ArgumentError("unknown argument $flag")
                }
            }
        }
        gateway?.let { source = Source.Gateway(it, username, password) }
        if (!list && !all && scenarios.isEmpty()) throw ArgumentError("name a --scenario, or --all")
        if (!list && out == null) throw ArgumentError("--out is required")
        if (scale !in 1..3) throw ArgumentError("--scale is 1, 2 or 3")
    }

    val gatewayUrl: URI? get() = (source as? Source.Gateway)?.url

    companion object {
        const val usage = """usage: preview [--demo | --gateway <url> --username <u> --password <p>]
               (--scenario <name>[,<name>…] | --all | --list)
               [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>"""
    }
}

class ArgumentError(message: String) : Exception(message)
