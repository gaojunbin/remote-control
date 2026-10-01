package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SwiftUI's stacks with SwiftUI's defaults, so a view ported from the Mac keeps its layout: an
 * `HStack` centres its children across, a `VStack` centres them along, and a `ZStack` centres
 * them both ways — where Compose's `Row`, `Column` and `Box` start at the top and the leading
 * edge. The two stacks keep SwiftUI's fractions of a pixel as they stack (`ExactHeight`).
 */
@Composable
fun HStack(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable HStackScope.() -> Unit,
) {
    val exact = remember { ExactHeight() }
    val frame = remember { StackFrame(vertical = false) }
    val policy = remember(spacing, alignment, exact, frame) { HStackPolicy(spacing, alignment, exact, frame) }
    Layout(content = { HStackScopeInstance.content() }, modifier = modifier.exactHeight(exact).stackFrame(frame), measurePolicy = policy)
}

@Composable
fun VStack(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable VStackScope.() -> Unit,
) {
    val exact = remember { ExactHeight() }
    val frame = remember { StackFrame(vertical = true) }
    val policy = remember(spacing, alignment, exact, frame) { VStackPolicy(spacing, alignment, exact, frame) }
    Layout(content = { VStackScopeInstance.content() }, modifier = modifier.exactHeight(exact).stackFrame(frame), measurePolicy = policy)
}

@Composable
fun ZStack(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = alignment, content = content)
}

/** What a child of an `HStack` can ask of it. */
@LayoutScopeMarker
@Immutable
interface HStackScope {
    /** A share, by `weight`, of the width the other children leave — SwiftUI's `Spacer()` is `Spacer(Modifier.weight(1f))`. */
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = stackWeight(weight, fill)

    /** This child's own place across the stack. */
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = stackAlign(vertical = alignment)
}

/** What a child of a `VStack` can ask of it. */
@LayoutScopeMarker
@Immutable
interface VStackScope {
    /** A share, by `weight`, of the height the other children leave, when the stack's height is bounded. */
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = stackWeight(weight, fill)

    /** This child's own place across the stack. */
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = stackAlign(horizontal = alignment)
}

private object HStackScopeInstance : HStackScope

private object VStackScopeInstance : VStackScope
