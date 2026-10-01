package com.junbingao.remotecontrol.win.layout

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far into the window's top-leading corner the Mac's traffic lights reach, which a page's top
 * strip starts its content after there. The Windows window keeps the system title bar above the
 * page (`docs/DESIGN.md` § "The Windows app" → **The window is Windows'**), so the page's strip has
 * no buttons in it and does not drag the window: the inset is always zero, and a page ported from
 * the Mac reads it and needs no `WindowStrip` of its own.
 */
val LocalTrafficLightInset = staticCompositionLocalOf<Dp> { 0.dp }
