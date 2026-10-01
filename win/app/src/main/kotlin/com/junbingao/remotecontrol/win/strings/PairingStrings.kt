// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `pairing` group of the web's string table. */
class PairingStrings(
    val title: String,
    val intro: String,
    val singleUse: String,
    val expiresIn: (String) -> String,
    val expired: String,
    val newCode: String,
    val waiting: String,
    val listening: (String) -> String,
    val stepGateway: String,
    val stepHandshake: String,
    val stepAgents: String,
    val connected: (String) -> String,
    val noCurl: String,
    val manualInstall: String,
    val manualTitle: String,
    val manualSteps: List<String>,
    val manualPairCommand: (String, String) -> String,
    val createFailed: String,
    val scanTitle: String,
    val scanBody: String,
    val scanCommand: (String) -> String,
    val claimTitle: String,
    val claimIntro: String,
    val claiming: String,
    val claimInvalid: String,
    val claimExpired: String,
    val claimUsed: String,
    val claimFailed: String,
) {
    companion object {
        val en = PairingStrings(
            title = "Add device",
            intro = "Run one command on the machine where your agents live. It dials out to the gateway — nothing is exposed on the host.",
            singleUse = "single use",
            expiresIn = { clock -> "expires in $clock" },
            expired = "expired",
            newCode = "New code",
            waiting = "Waiting for this device",
            listening = { clock -> "listening $clock" },
            stepGateway = "Gateway ready",
            stepHandshake = "Device handshake",
            stepAgents = "Detect installed agents",
            connected = { name -> "$name connected" },
            noCurl = "No curl on the host?",
            manualInstall = "Manual install",
            manualTitle = "Manual install",
            manualSteps = listOf(
                "Install the client: python3 -m pip install --user rc-client",
                "Pair it with this gateway using the code below.",
                "The client keeps running in the background and dials out only."
            ),
            manualPairCommand = { origin, code -> "rc-client pair --gateway $origin --code $code" },
            createFailed = "Could not create a pairing code.",
            scanTitle = "From your phone",
            scanBody = "The host prints a QR code. Scan it with the phone app, or open its link here.",
            scanCommand = { origin -> "curl -fsSL $origin/install.sh | sh" },
            claimTitle = "Pair this host",
            claimIntro = "The host that printed this code is joining your gateway.",
            claiming = "Claiming this code…",
            claimInvalid = "This link carries no code.",
            claimExpired = "This code has expired. Run the command again on the host.",
            claimUsed = "This code was already used.",
            claimFailed = "Could not claim this code.",
        )

        val zhHans = PairingStrings(
            title = "添加设备",
            intro = "在运行 agent 的机器上执行一条命令。它主动外连网关，主机不对外暴露任何端口。",
            singleUse = "一次性",
            expiresIn = { clock -> "$clock 后过期" },
            expired = "已过期",
            newCode = "新配对码",
            waiting = "等待此设备",
            listening = { clock -> "监听中 $clock" },
            stepGateway = "网关就绪",
            stepHandshake = "设备握手",
            stepAgents = "检测已安装的 agent",
            connected = { name -> "$name 已连接" },
            noCurl = "主机上没有 curl？",
            manualInstall = "手动安装",
            manualTitle = "手动安装",
            manualSteps = listOf(
                "安装客户端：python3 -m pip install --user rc-client",
                "用下面的配对码与本网关配对。",
                "客户端在后台常驻，只主动外连。"
            ),
            manualPairCommand = { origin, code -> "rc-client pair --gateway $origin --code $code" },
            createFailed = "无法创建配对码。",
            scanTitle = "从手机添加",
            scanBody = "主机会打印一个二维码。用手机 App 扫描它，或在这里打开它的链接。",
            scanCommand = { origin -> "curl -fsSL $origin/install.sh | sh" },
            claimTitle = "配对这台主机",
            claimIntro = "打印出这个码的主机正在加入你的网关。",
            claiming = "正在认领此码…",
            claimInvalid = "此链接不含配对码。",
            claimExpired = "此码已过期。请在主机上重新执行该命令。",
            claimUsed = "此码已被使用。",
            claimFailed = "无法认领此码。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): PairingStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
