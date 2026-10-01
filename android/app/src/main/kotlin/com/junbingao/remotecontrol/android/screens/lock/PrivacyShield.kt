package com.junbingao.remotecontrol.android.screens.lock

import androidx.compose.runtime.Composable

/**
 * Covers the whole app, any presented sheet included, while the recents screen may take its
 * picture: the root draws it above everything whenever `SceneRule.shields` says so, and
 * `RecentsShield` is the platform's half (`docs/DESIGN.md` § "The Android app"). Touches pass
 * through it, as they do through the iPhone's shield window, so nothing the person reaches for in
 * a split screen or under the biometric prompt is swallowed.
 */
@Composable
fun PrivacyShield(visible: Boolean) {
    if (visible) AppPrivacyCover()
}
