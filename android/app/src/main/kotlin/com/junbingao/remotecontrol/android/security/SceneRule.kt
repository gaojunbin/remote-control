package com.junbingao.remotecontrol.android.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * What the activity's lifecycle means for the app, in one place — `SceneRule.swift` over
 * Android's states, with `RESUMED` for the iPhone's `.active`, `STARTED` (visible, not in front:
 * a system dialog, the biometric prompt, the other half of a split screen) for `.inactive`, and
 * anything below for `.background`.
 *
 * Only the background is leaving the app: dictation is suspended with its words kept, the app
 * lock engages, and the draft is written out. The privacy shield alone follows the inactive
 * state too, because that is when the recents screen's picture may be taken.
 */
object SceneRule {
    /** The app has left the screen altogether. */
    fun isBackground(state: Lifecycle.State): Boolean = !state.isAtLeast(Lifecycle.State.STARTED)

    /** The app is on screen and reading the stream. */
    fun isForeground(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.RESUMED)

    /** The privacy shield covers the app the moment it stops being the one in front. */
    fun shields(state: Lifecycle.State): Boolean = !state.isAtLeast(Lifecycle.State.RESUMED)
}

/** The screen's lifecycle state as it changes, for the three answers above. */
@Composable
fun currentSceneState(): Lifecycle.State {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state
}
