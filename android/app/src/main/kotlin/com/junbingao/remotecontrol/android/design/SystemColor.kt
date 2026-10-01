package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The iPhone's own colours — UIKit's semantic system colours — for the pieces the system draws
 * there and this app draws here: text with no colour of its own, the switch's off track, the
 * alert's destructive word, a separator. The app's palette is [Theme]; these are only what iOS
 * would have supplied.
 */
object SystemColor {
    /** `.primary`: text that sets no colour. */
    val label: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF000000), Color(0xFFFFFFFF))
    val secondaryLabel: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x993C3C43), Color(0x99EBEBF5))
    val tertiaryLabel: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x4C3C3C43), Color(0x4CEBEBF5))

    /** A list's hairline between two rows. */
    val separator: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x4A3C3C43), Color(0x99545458))

    val systemFill: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x33787880), Color(0x5C787880))
    val secondarySystemFill: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x29787880), Color(0x52787880))
    val tertiarySystemFill: Color @Composable @ReadOnlyComposable
        get() = pick(Color(0x1F767680), Color(0x3D767680))

    val systemRed: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFFFF3B30), Color(0xFFFF453A))
    val systemBlue: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF007AFF), Color(0xFF0A84FF))
    val systemGray: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF8E8E93), Color(0xFF8E8E93))

    /** The dimming behind an alert, a sheet or a menu. */
    val dimming: Color @Composable @ReadOnlyComposable get() = pick(Color(0x33000000), Color(0x66000000))

    @Composable
    @ReadOnlyComposable
    private fun pick(light: Color, dark: Color): Color = if (LocalAppearance.current.isDark) dark else light
}
