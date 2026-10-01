package com.junbingao.remotecontrol.android.design

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * What the iPhone's colours and motion resolve against: the system appearance (`docs/DESIGN.md`
 * § "The Android app": it follows the system as the iPhone app's does) and the system's reduced
 * motion setting, the counterpart of Reduce Motion.
 */
data class Appearance(val isDark: Boolean, val reduceMotion: Boolean = false)

val LocalAppearance = compositionLocalOf { Appearance(isDark = false) }

/** The appearance the system asks for, as the iPhone's dynamic colours read it. */
@Composable
fun systemAppearance(): Appearance {
    val dark = isSystemInDarkTheme()
    // Reading the configuration makes a change of either setting recompose the screen.
    LocalConfiguration.current
    val resolver = LocalContext.current.contentResolver
    val scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return Appearance(isDark = dark, reduceMotion = scale == 0f)
}

/** Draws [content] in [appearance], so the tokens below it resolve light or dark. */
@Composable
fun ProvideAppearance(appearance: Appearance, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppearance provides appearance, content = content)
}
