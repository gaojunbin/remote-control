// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** A24: the admin's accounts screen. */
class UsersStrings(
    val title: String,
    val registration: String,
    val registrationCaption: String,
    val add: String,
    val meta: (String, String) -> String,
    val deviceCount: (Int) -> String,
    val lastSignIn: (String) -> String,
    val never: String,
    val resetPassword: String,
    val resetPasswordTitle: (String) -> String,
    val disable: String,
    val enable: String,
    val deleteAction: String,
    val deleteTitle: String,
    val deleteBody: (String) -> String,
    val deleteBodyDevices: (String, Int) -> String,
    val deleteConfirm: String,
    val loadFailed: String,
    val registrationFailed: String,
) {
    companion object {
        val en = UsersStrings(
            title = "Users",
            registration = "Registration",
            registrationCaption = "Anyone with the gateway address can create an account",
            add = "Add user",
            meta = { role, state -> "$role · $state" },
            deviceCount = { n -> "$n ${if (n == 1) "device" else "devices"}" },
            lastSignIn = { rel -> "last sign-in $rel" },
            never = "never",
            resetPassword = "Reset password",
            resetPasswordTitle = { username -> "Reset password for $username" },
            disable = "Disable",
            enable = "Enable",
            deleteAction = "Delete",
            deleteTitle = "Delete account",
            deleteBody = { username -> "Delete $username? Its sign-ins stop working at once and nothing of it is kept." },
            deleteBodyDevices = { username, n -> "Delete $username and its $n ${if (n == 1) "device" else "devices"}? The devices are revoked and their sessions leave this gateway. The machines keep their agents and transcripts." },
            deleteConfirm = "Delete account",
            loadFailed = "Could not load the accounts.",
            registrationFailed = "Could not change that setting.",
        )

        val zhHans = UsersStrings(
            title = "用户",
            registration = "开放注册",
            registrationCaption = "知道网关地址的人都可以创建账户",
            add = "添加用户",
            meta = { role, state -> "$role · $state" },
            deviceCount = { n -> "$n 台设备" },
            lastSignIn = { rel -> "上次登录 $rel" },
            never = "从未",
            resetPassword = "重设密码",
            resetPasswordTitle = { username -> "为 $username 重设密码" },
            disable = "禁用",
            enable = "启用",
            deleteAction = "删除",
            deleteTitle = "删除账户",
            deleteBody = { username -> "删除 $username？它的登录会立即失效，不保留任何内容。" },
            deleteBodyDevices = { username, n -> "删除 $username 及其 $n 台设备？这些设备将被吊销，其会话会离开本网关。机器上的 agent 和记录不受影响。" },
            deleteConfirm = "删除账户",
            loadFailed = "无法加载账户列表。",
            registrationFailed = "无法修改该设置。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): UsersStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
