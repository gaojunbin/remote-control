package com.junbingao.remotecontrol.win.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.modifier.ModifierLocalModifierNode
import androidx.compose.ui.modifier.modifierLocalMapOf
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.node.ModifierNodeElement

/**
 * A box's height as the Mac's layout keeps it. SwiftUI lays views out in fractions of a point —
 * a 12 px caption at `line-height: 1.4` is 16.8 tall, and the view under it starts 16.8 lower —
 * and rounds each edge to the pixel only as it draws it; Compose lays out in whole pixels. A box
 * that rounded its height says by how much (`fraction`, in pixels), and a stack adds the
 * fractions back as it stacks (`VStack`, `HStack`), so a long page's views sit on the pixels the
 * Mac's do instead of drifting a pixel every few captions.
 */
internal class ExactHeight {
    /** The exact height less the height laid out, in pixels. Set as the box measures. */
    var fraction = 0f
}

/**
 * Where a stack's exact layout put its children: for each, the run of the stack it was placed
 * in and how far below the pixel it was placed at the exact layout puts it. A text anywhere
 * inside a child — a button's label, a pill's — asks the stack around it (`offsetOf`) and rounds
 * its line to the point from there, as the Mac's does from its exact place. A scroll view's
 * content is an `origin`: the Mac lays it out afresh from the scroll view's pixel and rounds the
 * lines in it from there (`lineTop`).
 */
internal class StackFrame(private val vertical: Boolean, private val origin: Boolean = false) {
    /** One child: `start until end` along the stack, in its pixels, and its exact offset. */
    data class Span(val start: Int, val end: Int, val offset: Float)

    /** The stack this one sits in, if any. */
    var parent: StackFrame? = null
    private var coordinates: LayoutCoordinates? = null
    private var spans by mutableStateOf(emptyList<Span>())

    /** How far below its pixel the exact layout puts `node`, a view inside this stack, in pixels. */
    fun offsetOf(node: LayoutCoordinates): Float {
        val stack = coordinates?.takeIf { it.isAttached } ?: return 0f
        if (!node.isAttached) return 0f
        val position = stack.localPositionOf(node, Offset.Zero)
        val along = (if (vertical) position.y else position.x) + 0.5f
        return spans.lastOrNull { it.start <= along }?.offset ?: 0f
    }

    /**
     * How far down `node`, a view inside this stack, sits in the space the Mac rounds its lines
     * in: the content of the scroll view around it, or the window.
     */
    fun lineTop(node: LayoutCoordinates): Float {
        var frame: StackFrame? = this
        while (frame != null && !frame.origin) frame = frame.parent
        val space = frame?.coordinates?.takeIf { it.isAttached && node.isAttached }
        return space?.localPositionOf(node, Offset.Zero)?.y ?: node.positionInRoot().y
    }

    /** Where this stack itself sits off the pixel, from the stack around it. */
    fun base(own: LayoutCoordinates?): Float = own?.let { parent?.offsetOf(it) } ?: 0f

    /** What a placement put where. A placement run only to read alignment lines has no coordinates and says nothing. */
    fun placed(own: LayoutCoordinates?, spans: List<Span>) {
        if (own == null) return
        coordinates = own
        if (this.spans != spans) this.spans = spans
    }
}

/** The stack frame around a view, for the views inside it. */
internal val ModifierLocalStackFrame = modifierLocalOf<StackFrame?> { null }

/** Hands `frame` to the views inside, and learns the frame of the stack around. */
internal fun Modifier.stackFrame(frame: StackFrame): Modifier = this then StackFrameElement(frame)

private data class StackFrameElement(val frame: StackFrame) : ModifierNodeElement<StackFrameNode>() {
    override fun create() = StackFrameNode(frame)

    override fun update(node: StackFrameNode) {
        node.frame = frame
        node.provide(ModifierLocalStackFrame, frame)
        frame.parent = with(node) { ModifierLocalStackFrame.current }
    }
}

private class StackFrameNode(var frame: StackFrame) : Modifier.Node(), ModifierLocalModifierNode {
    override val providedValues = modifierLocalMapOf(ModifierLocalStackFrame to frame)

    override fun onAttach() {
        frame.parent = ModifierLocalStackFrame.current
    }
}
