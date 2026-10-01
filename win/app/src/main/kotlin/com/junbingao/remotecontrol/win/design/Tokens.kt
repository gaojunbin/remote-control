package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * `web/src/styles/tokens.css`, one constant per custom property and named after it
 * (`--surface-sunken` is `Palette.surfaceSunken`, `--fs-13` is `FontSize.fs13`). `docs/DESIGN.md`
 * § "Palette and type" and § "Surfaces, rows and controls" are the rules they encode: one canvas,
 * soft surfaces instead of bordered boxes, spacing rather than lines between rows. A visual change
 * starts here and in the web's file, never in a view. 1 CSS px is 1 dp.
 */
object Palette {
    // surfaces
    val canvas = Color.hex(0xF5F5F4)
    val surface = Color.hex(0xFFFFFF)
    val surfaceSunken = Color.hex(0xFAFAF9)
    val surfaceMuted = Color.hex(0xF1F1EF)
    val surfaceHover = Color.hex(0xF5F5F4)
    val surfaceActive = Color.hex(0xECECEA)
    val overlay = Color.rgb(24, 24, 22, opacity = 0.28f)

    // lines
    val line = Color.hex(0xE6E5E1)
    val lineStrong = Color.hex(0xD6D5D0)

    /**
     * A bar's edge — the chat header, a page's section break — and never a line between two rows:
     * every list, Settings included, is parted by spacing.
     */
    val hairline = Color.rgb(17, 17, 17, opacity = 0.08f)

    /** A list row under the pointer, and the stronger step the selected row holds. */
    val hover = Color.rgb(17, 17, 17, opacity = 0.04f)
    val hoverSelected = Color.rgb(17, 17, 17, opacity = 0.07f)

    // ink
    val ink = Color.hex(0x111111)
    val inkSecondary = Color.hex(0x6B6B6B)
    val inkTertiary = Color.hex(0x767570)
    val inkInverse = Color.hex(0xFFFFFF)

    // status
    val running = Color.hex(0x22A06B)
    val runningSoft = Color.hex(0xE7F4EE)

    /**
     * Amber, not orange: a blocked session reads as yellow beside the greens, and stays above 3:1
     * on every row background (3.67:1 on `surface`).
     */
    val attention = Color.hex(0xB07C00)
    val attentionSoft = Color.hex(0xFBF3E0)
    val idle = Color.hex(0xB5B5B0)
    val danger = Color.hex(0xD23F31)
    val dangerSoft = Color.hex(0xFDECEB)
    val accent = Color.hex(0x111111)

    // diff
    val diffAdd = Color.hex(0x1F7A4D)
    val diffAddBg = Color.hex(0xEEFAF3)
    val diffDel = Color.hex(0xC23A2C)
    val diffDelBg = Color.hex(0xFDEEEC)
}

/** `--fs-*`: the type scale, in CSS px. */
object FontSize {
    const val fs11 = 11f
    const val fs12 = 12f
    const val fs13 = 13f
    const val fs14 = 14f
    const val fs15 = 15f
    const val fs16 = 16f
    const val fs17 = 17f
    const val fs22 = 22f
    const val fs30 = 30f
}

/** `--sp-*`: the spacing scale. */
object Space {
    val sp1 = 4.dp
    val sp2 = 8.dp
    val sp3 = 12.dp
    val sp4 = 16.dp
    val sp5 = 20.dp
    val sp6 = 24.dp
    val sp8 = 32.dp
    val sp10 = 40.dp
}

/** `--r-*`: corner radii. A pill is a capsule at any height. */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

/**
 * `--row-h*`: every device and session row is this tall, whichever list it sits in. A narrow
 * screen stacks the row's last line, and a phone stacks the device meta as well, so each stacked
 * form has a height of its own.
 */
object RowHeight {
    val rowH = 64.dp
    val rowHStacked = 96.dp
    val rowHStackedTall = 108.dp

    /** Three lines of text — a title and two meta lines — with the same breathing room. */
    val rowHThree = 84.dp

    /**
     * A settings row is a floor, not a clip: a sentence that wraps makes its row taller and moves
     * nothing at the widths where it does not wrap.
     */
    val rowHSetting = 56.dp
}

/** `--sidebar-w`, `--header-h`, `--content-max`. */
object LayoutSize {
    val sidebarW = 264.dp
    val headerH = 60.dp
    val contentMax = 1080.dp
}

/**
 * `--z-*`. A popover is always drawn above every overlay, because the thing that opened it may
 * itself be inside a modal or the drawer.
 */
object ZLayer {
    const val sticky = 20f
    const val overlay = 60f
    const val popover = 100f
}

/**
 * `--ease`, `--dur-fast`, `--dur`: one easing curve, two durations, and none at all under reduced
 * motion (the web's `prefers-reduced-motion` rule).
 */
object Motion {
    /** In milliseconds. */
    const val durFast = 120
    const val dur = 200

    /** `cubic-bezier(0.22, 0.61, 0.36, 1)`. */
    val curve = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    /** The one curve for `duration` milliseconds, or no animation when the reader asked for less. */
    fun <T> ease(duration: Int, reduceMotion: Boolean = false): AnimationSpec<T> =
        if (reduceMotion) snap() else tween(durationMillis = duration, easing = curve)
}

/** An sRGB colour written the way the stylesheet writes it: `0xF5F5F4`. */
fun Color.Companion.hex(hex: Int, opacity: Float = 1f): Color = Color(
    red = ((hex shr 16) and 0xFF) / 255f,
    green = ((hex shr 8) and 0xFF) / 255f,
    blue = (hex and 0xFF) / 255f,
    alpha = opacity,
)

/** `rgba(r, g, b, a)`. */
fun Color.Companion.rgb(red: Int, green: Int, blue: Int, opacity: Float): Color =
    Color(red = red / 255f, green = green / 255f, blue = blue / 255f, alpha = opacity)
