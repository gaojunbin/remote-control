package com.junbingao.remotecontrol.android.screens.lock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The lock above everything — the iPhone's `AppLockWindow`, which raises its own window over the
 * alert level so an open sheet cannot show queued prompt text over the lock. The root draws this
 * above the presentations; while [locked], [AppLockView] covers the screen and takes every touch.
 *
 * Placeholder for `android-settings`, which ports the window's own rules; the signature stays.
 */
@Composable
fun AppLockWindow(locked: Boolean, onUnlock: () -> Unit) {
    AnimatedVisibility(locked, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}) {
            AppLockView(onUnlock)
        }
    }
}
