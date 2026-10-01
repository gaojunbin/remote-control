package com.junbingao.remotecontrol.android.awake

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.core.state.ScreenAwakeRule

/**
 * The conversations on screen at this moment.
 *
 * Opening a conversation from a notification lays it over the one being read, and the new one
 * enters before the covered one leaves. Counting is what stops the screen being allowed to sleep
 * again while a conversation is still in front of the reader (`ScreenAwake.swift`).
 */
internal object OpenConversations {
    private var count = 0

    val any: Boolean get() = count > 0

    fun entered() {
        count += 1
    }

    fun left() {
        count = maxOf(0, count - 1)
    }
}

/**
 * Holds the screen on while a conversation is on screen and the app is in front — the only place
 * that touches the window's keep-screen-on flag, and the conversation its only caller.
 * `docs/DESIGN.md` § "The screen stays awake in a conversation": dictating a long message, or
 * watching a turn with the phone propped up, must never end because the screen went dark. The rule
 * is the core's `ScreenAwakeRule`, and "in front" is `SceneRule.isForeground`.
 */
fun Modifier.keepsScreenAwake(): Modifier = composed {
    val activity = LocalContext.current.findActivity()
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active by rememberUpdatedState(SceneRule.isForeground(state))
    DisposableEffect(activity) {
        OpenConversations.entered()
        apply(activity, ScreenAwakeRule.awake(chatOnScreen = OpenConversations.any, sceneActive = active))
        onDispose {
            OpenConversations.left()
            apply(activity, ScreenAwakeRule.awake(chatOnScreen = OpenConversations.any, sceneActive = active))
        }
    }
    LaunchedEffect(active) {
        apply(activity, ScreenAwakeRule.awake(chatOnScreen = OpenConversations.any, sceneActive = active))
    }
    this
}

private fun apply(activity: Activity?, awake: Boolean) {
    val window = activity?.window ?: return
    if (awake) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
