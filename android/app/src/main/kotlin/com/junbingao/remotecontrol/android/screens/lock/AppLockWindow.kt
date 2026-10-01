package com.junbingao.remotecontrol.android.screens.lock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The lock above everything.
 *
 * The iPhone hosts it in a window of its own above the alert level, because a sheet is presented
 * by UIKit above the hosting controller and a lock drawn as a sibling view renders behind it. Here
 * the root draws this above the presentations, so an open sheet cannot show queued prompt text over
 * the lock either. It is up from the first frame it is asked for and gone on the frame it is not —
 * a lock that faded in would show the transcript through it on the way.
 *
 * While it is up it takes every touch, and the system's Back leaves the app as it does at a tab's
 * root: what is under the lock is not reachable, and closing a sheet or a screen behind it would
 * change what the owner finds there.
 */
@Composable
fun AppLockWindow(locked: Boolean, onUnlock: () -> Unit) {
    if (!locked) return
    val activity = LocalActivity.current
    // Composed only while locked, so it is the last Back handler registered and the first asked.
    BackHandler { activity?.moveTaskToBack(true) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        AppLockView(onUnlock)
    }
}
