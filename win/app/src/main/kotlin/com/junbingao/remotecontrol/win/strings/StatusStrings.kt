// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** The `status` group of the web's string table. */
class StatusStrings(
    val working: (String) -> String,
    val workingQueued: (String) -> String,
    val workingSteer: (String) -> String,
    val needsApproval: String,
    val needsInput: String,
    val terminalControlled: String,
    val terminalBusy: String,
    /** Added to the line above only where the agent can be taken over. */
    val takeOverToSend: String,
    val starting: String,
    val stopped: String,
    val errored: String,
    val idle: String,
    val offline: String,
) {
    companion object {
        val en = StatusStrings(
            working = { agent -> "$agent is working" },
            workingQueued = { agent -> "$agent is working · your message will be queued" },
            workingSteer = { agent -> "$agent is working · your message will steer the turn" },
            needsApproval = "Needs your approval",
            needsInput = "Waiting for your answer",
            terminalControlled = "Controlled by the terminal",
            terminalBusy = "a turn is running there",
            takeOverToSend = "take over to send",
            starting = "Starting the agent…",
            stopped = "Stopped",
            errored = "Errored",
            idle = "Idle",
            offline = "Device offline",
        )

        val zhHans = StatusStrings(
            working = { agent -> "$agent 正在工作" },
            workingQueued = { agent -> "$agent 正在工作 · 消息将排队" },
            workingSteer = { agent -> "$agent 正在工作 · 消息将转向当前任务" },
            needsApproval = "需要你的批准",
            needsInput = "等待你的回答",
            terminalControlled = "由终端控制",
            terminalBusy = "那边正在执行一轮任务",
            takeOverToSend = "接管后可发送",
            starting = "正在启动 agent…",
            stopped = "已停止",
            errored = "出错",
            idle = "空闲",
            offline = "设备离线",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): StatusStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
