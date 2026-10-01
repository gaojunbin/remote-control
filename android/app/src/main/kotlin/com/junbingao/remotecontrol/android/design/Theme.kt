package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The product palette from the design brief: a light, quiet page with white surfaces, hairline
 * borders and black primary actions — `ios/Sources/RCUI/Design/Theme.swift`, value for value.
 *
 * Every colour is defined for both appearances so the app stays legible when the system is dark.
 * Version 1 is designed light; the dark values keep contrast rather than inverting the design.
 * A colour is read inside a composable, where [LocalAppearance] says which of the two applies.
 */
object Theme {
    val canvas: Color @Composable @ReadOnlyComposable get() = dynamic(0xF5F5F4, 0x121211)
    val surface: Color @Composable @ReadOnlyComposable get() = dynamic(0xFFFFFF, 0x1C1C1B)
    val surfaceSunken: Color @Composable @ReadOnlyComposable get() = dynamic(0xF0EFED, 0x232322)
    val border: Color @Composable @ReadOnlyComposable get() = dynamic(0xE6E5E1, 0x33332F)
    val ink: Color @Composable @ReadOnlyComposable get() = dynamic(0x111111, 0xF2F2F0)
    val inkSecondary: Color @Composable @ReadOnlyComposable get() = dynamic(0x6B6B6B, 0xA0A09B)

    /**
     * The quietest ink there is: a mark that has to be visible without being read, such as a
     * stop on the effort slider's unfilled track.
     */
    val inkTertiary: Color @Composable @ReadOnlyComposable get() = ink.copy(alpha = 0.28f)
    val accent: Color @Composable @ReadOnlyComposable get() = dynamic(0x111111, 0xF2F2F0)
    val onAccent: Color @Composable @ReadOnlyComposable get() = dynamic(0xFFFFFF, 0x111111)

    /**
     * The only line allowed inside a surface, and never after its last row. Softer than
     * [border], which still edges a card that needs an edge.
     */
    val hairline: Color @Composable @ReadOnlyComposable get() = ink.copy(alpha = 0.09f)

    /** The fill behind a quiet chip or a quiet button. Tinted, never outlined. */
    val quietFill: Color @Composable @ReadOnlyComposable get() = ink.copy(alpha = 0.06f)

    val running: Color @Composable @ReadOnlyComposable get() = dynamic(0x22A06B, 0x36BE85)

    /**
     * Amber, not orange: a session blocked on the user has to read as yellow beside a green one,
     * and still clear 3:1 against both the white surface a row sits on (3.67:1) and the canvas
     * behind the chat status line (3.36:1). The light value is the shared `--attention` token in
     * `docs/DESIGN.md`.
     */
    val attention: Color @Composable @ReadOnlyComposable get() = dynamic(0xB07C00, 0xE3B341)
    val resting: Color @Composable @ReadOnlyComposable get() = dynamic(0xB5B5B0, 0x6E6E69)
    val danger: Color @Composable @ReadOnlyComposable get() = dynamic(0xD23F31, 0xE8695C)

    val added: Color @Composable @ReadOnlyComposable get() = dynamic(0x1F7A4D, 0x49B57F)
    val removed: Color @Composable @ReadOnlyComposable get() = dynamic(0xB03227, 0xE0736A)

    /** Corner radii: cards and sheets, then pills. */
    object Radius {
        val card = 14.dp
        val sheet = 16.dp
        val control = 12.dp
        val pill = 999.dp
    }

    /** A 4-point spacing scale. */
    object Space {
        val hair = 2.dp
        val tight = 6.dp
        val small = 10.dp
        val medium = 16.dp
        val large = 24.dp
        val page = 20.dp
    }

    /**
     * The square an agent's logo is drawn in. `docs/DESIGN.md` § "Agents": the vectors are
     * full-bleed, so the box is the cap height of the line beside them and the mark stands
     * exactly as tall as the capitals it sits next to.
     */
    object Mark {
        /** Beside a word, on a chip or in a menu row: the cap height of the `meta` line. */
        val inline = 9.dp

        /**
         * Alone, in the new-session form's agent control, where there is no word to match and
         * the segment itself is the target.
         */
        val control = 17.dp
    }

    /** The minimum comfortable target, and the primary thumb target. */
    object Touch {
        val minimum = 44.dp
        val primary = 48.dp
    }

    val mono: TextStyle = SystemFont.footnote.monospaced()
    val monoBody: TextStyle = SystemFont.callout.monospaced()

    /**
     * Hierarchy is carried by type, not by boxes. Everything scales with the phone's font size,
     * so these are the text styles rather than fixed sizes: `callout` is 16 at the default
     * setting, `footnote` 13, `caption` 12.
     */
    object Text {
        /** A row title: the name of a session or a device. */
        val title: TextStyle = SystemFont.callout.weight(FontWeight.SemiBold)

        /**
         * A settings label, and any row whose weight would shout: the control beside it is the
         * point, not the word.
         */
        val label: TextStyle = SystemFont.callout

        /** The line under a title: status, path, host. */
        val meta: TextStyle = SystemFont.footnote

        /** The smallest supporting line, and the value beside a settings label. */
        val caption: TextStyle = SystemFont.caption

        /** Monospace at meta weight, for a path or a branch in a list row. */
        val metaMono: TextStyle = SystemFont.caption.monospaced()
    }

    @Composable
    @ReadOnlyComposable
    private fun dynamic(light: Long, dark: Long): Color =
        Color(0xFF000000 or if (LocalAppearance.current.isDark) dark else light)
}
