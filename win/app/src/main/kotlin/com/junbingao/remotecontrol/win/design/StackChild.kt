package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Density

/** What a child tells the stack it is in: its share of the room left, its own alignment, its exact height. */
internal class StackChild {
    var weight = 0f
    var fill = true
    var horizontal: Alignment.Horizontal? = null
    var vertical: Alignment.Vertical? = null
    var exact: ExactHeight? = null
}

internal val Measurable.stackChild: StackChild? get() = parentData as? StackChild

/** Hands the box's `ExactHeight` to the stack it sits in. */
internal fun Modifier.exactHeight(holder: ExactHeight): Modifier = this then StackChildElement(exact = holder)

internal fun Modifier.stackWeight(weight: Float, fill: Boolean): Modifier {
    require(weight > 0f) { "A weight is more than zero: $weight" }
    return this then StackChildElement(weight = weight, fill = fill)
}

internal fun Modifier.stackAlign(horizontal: Alignment.Horizontal? = null, vertical: Alignment.Vertical? = null): Modifier =
    this then StackChildElement(horizontal = horizontal, vertical = vertical)

private data class StackChildElement(
    val weight: Float = 0f,
    val fill: Boolean = true,
    val horizontal: Alignment.Horizontal? = null,
    val vertical: Alignment.Vertical? = null,
    val exact: ExactHeight? = null,
) : ModifierNodeElement<StackChildNode>() {
    override fun create() = StackChildNode(this)

    override fun update(node: StackChildNode) {
        node.element = this
    }
}

private class StackChildNode(var element: StackChildElement) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = ((parentData as? StackChild) ?: StackChild()).also {
        if (element.weight > 0f) {
            it.weight = element.weight
            it.fill = element.fill
        }
        element.horizontal?.let { alignment -> it.horizontal = alignment }
        element.vertical?.let { alignment -> it.vertical = alignment }
        element.exact?.let { holder -> it.exact = holder }
    }
}
