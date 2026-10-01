// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** A24: the words about an account, wherever one is named or typed. */
class AccountStrings(
    val username: String,
    val password: String,
    val currentPassword: String,
    val newPassword: String,
    val role: String,
    val rules: String,
    val taken: String,
    val disabled: String,
    val wrongCurrentPassword: String,
    val notAllowed: String,
    val gone: String,
) {
    companion object {
        val en = AccountStrings(
            username = "Username",
            password = "Password",
            currentPassword = "Current password",
            newPassword = "New password",
            role = "Role",
            rules = "Usernames are 3 to 32 characters: lower-case letters, digits, dots, underscores and hyphens. Passwords are 8 characters or more.",
            taken = "That username is taken.",
            disabled = "This account is disabled.",
            wrongCurrentPassword = "That is not your current password.",
            notAllowed = "This account cannot be changed.",
            gone = "That account no longer exists.",
        )

        val zhHans = AccountStrings(
            username = "用户名",
            password = "密码",
            currentPassword = "当前密码",
            newPassword = "新密码",
            role = "角色",
            rules = "用户名为 3 到 32 个字符：小写字母、数字、点、下划线和连字符。密码至少 8 个字符。",
            taken = "该用户名已被占用。",
            disabled = "该账户已被禁用。",
            wrongCurrentPassword = "当前密码不正确。",
            notAllowed = "该账户无法修改。",
            gone = "该账户已不存在。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): AccountStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
