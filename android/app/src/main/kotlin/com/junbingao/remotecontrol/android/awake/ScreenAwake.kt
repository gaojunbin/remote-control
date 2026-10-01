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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

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
 * watching a turn with the phone propped up, must never end because the screen went dark.
 *
 * [rule] is the core's `ScreenAwakeRule.awake(chatOnScreen:sceneActive:)`, passed in so the rule
 * is written once.
 */
fun Modifier.keepsScreenAwake(rule: (chatOnScreen: Boolean, sceneActive: Boolean) -> Boolean): Modifier = composed {
    val activity = LocalContext.current.findActivity()
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val currentRule by rememberUpdatedState(rule)
    DisposableEffect(activity) {
        OpenConversations.entered()
        apply(activity, currentRule(OpenConversations.any, state.isAtLeast(Lifecycle.State.RESUMED)))
        onDispose {
            OpenConversations.left()
            apply(activity, currentRule(OpenConversations.any, state.isAtLeast(Lifecycle.State.RESUMED)))
        }
    }
    LaunchedEffect(state) {
        apply(activity, currentRule(OpenConversations.any, state.isAtLeast(Lifecycle.State.RESUMED)))
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
