package com.junbingao.remotecontrol.android.push

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri

/**
 * The icon's number on Huawei's and Honor's launchers (`docs/DESIGN.md` § "A red dot for a session
 * that stopped and waits for you" → Android). They draw an app's number from their own badge
 * interface rather than from its notifications, so the number [LauncherBadge]'s notification shows
 * is handed to them too — through `change_badge` on each launcher's settings provider, which is
 * declared in the manifest's `<queries>` and asked for with each launcher's `CHANGE_BADGE`
 * permission. Any other launcher has no such provider, and every failure there or here says
 * nothing: where the number does not arrive, the notification is still the badge.
 */
object HuaweiBadge {
    /** Huawei's launcher (EMUI, HarmonyOS) and Honor's (MagicOS), which kept the same interface. */
    private val providers = listOf("com.huawei.android.launcher.settings", "com.hihonor.android.launcher.settings")

    /** Put `number` on the icon, or take it off with 0. */
    fun set(context: Context, number: Int) {
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className ?: return
        val extras = Bundle().apply {
            putString("package", context.packageName)
            putString("class", launcher)
            putInt("badgenumber", number)
        }
        for (authority in providers) {
            try {
                context.contentResolver.call("content://$authority/badge/".toUri(), "change_badge", null, extras)
            } catch (_: Exception) {
                // Not this launcher, or it refused: the notification stands for the badge there.
            }
        }
    }
}
