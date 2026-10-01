// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `devices` group of the web's string table. */
class DevicesStrings(
    val title: String,
    val subtitleCount: (Int, Int) -> String,
    val empty: String,
    val emptyHint: String,
    val add: String,
    val sessionsCount: (Int) -> String,
    val noSessions: String,
    val offline: String,
    val lastSeen: (String) -> String,
    val renameTitle: String,
    val renameLabel: String,
    /**
     * A36: a device updates itself, so nothing says which client it runs. An
     * app speaks only while an update runs or has failed, and the only action
     * left is trying a failure again.
     */
    val retryUpdate: String,
    val updating: String,
    val updateFailed: (String) -> String,
    val updateTitle: String,
    val updateBody: (String, String?) -> String,
    val updateConfirm: String,
    /** A38: the row's tap needs the same sentence, so the two share one key. */
    val deviceOffline: String,
    val updateNoBuild: String,
    /**
     * A38: rule 20 — the row's menu, in this order, and what a tap that opens
     * nothing says in its place.
     */
    val showQuota: String,
    val noTerminal: String,
    val revokeTitle: String,
    val revokeBody: (String) -> String,
    val revokeConfirm: String,
    val noAgents: String,
    val online: String,
) {
    companion object {
        val en = DevicesStrings(
            title = "Devices",
            subtitleCount = { online, total -> "$online connected · $total ${if (total == 1) "device" else "devices"}" },
            empty = "No devices yet.",
            emptyHint = "Add the machine where your coding agents are installed.",
            add = "Add device",
            sessionsCount = { n -> "$n ${if (n == 1) "session" else "sessions"}" },
            noSessions = "idle",
            offline = "offline",
            lastSeen = { rel -> "last seen $rel" },
            renameTitle = "Rename device",
            renameLabel = "Device name",
            retryUpdate = "Retry update",
            updating = "Updating…",
            updateFailed = { message -> "Update failed · $message" },
            updateTitle = "Update device",
            updateBody = { name, version -> if (version == null) "Update $name to the gateway's client? Its service restarts; sessions it drives are stopped." else "Update $name to $version? Its service restarts; sessions it drives are stopped." },
            updateConfirm = "Update device",
            deviceOffline = "This device is offline.",
            updateNoBuild = "This gateway is not serving a client build.",
            showQuota = "Show quota",
            noTerminal = "This device does not offer a terminal.",
            revokeTitle = "Revoke device",
            revokeBody = { name -> "Revoke $name? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts." },
            revokeConfirm = "Revoke device",
            noAgents = "No agents detected",
            online = "online",
        )

        val zhHans = DevicesStrings(
            title = "设备",
            subtitleCount = { online, total -> "已连接 $online · 共 $total 台设备" },
            empty = "还没有设备。",
            emptyHint = "添加安装了编程 agent 的机器。",
            add = "添加设备",
            sessionsCount = { n -> "$n 个会话" },
            noSessions = "空闲",
            offline = "离线",
            lastSeen = { rel -> "最后在线 $rel" },
            renameTitle = "重命名设备",
            renameLabel = "设备名称",
            retryUpdate = "重试更新",
            updating = "更新中…",
            updateFailed = { message -> "更新失败 · $message" },
            updateTitle = "更新设备",
            updateBody = { name, version -> if (version == null) "将 $name 更新到网关提供的客户端？它的服务会重启，由它驱动的会话会被停止。" else "将 $name 更新到 $version？它的服务会重启，由它驱动的会话会被停止。" },
            updateConfirm = "更新设备",
            deviceOffline = "此设备已离线。",
            updateNoBuild = "本网关未提供客户端安装包。",
            showQuota = "显示额度",
            noTerminal = "此设备不提供终端。",
            revokeTitle = "吊销设备",
            revokeBody = { name -> "吊销 $name？它的令牌会失效，它的会话也会离开本网关。机器上的 agent 和记录都会保留。" },
            revokeConfirm = "吊销设备",
            noAgents = "未检测到 agent",
            online = "在线",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): DevicesStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
