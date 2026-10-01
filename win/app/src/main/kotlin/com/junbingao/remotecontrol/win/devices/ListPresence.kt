package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.MutableState

/**
 * An overlay's `isPresented` for the item it is about, the way the web opens a dialog with
 * `open={item !== null}`: shown while there is one, and closing it — Escape, the backdrop, its own
 * buttons — forgets the item.
 */
class ListPresence(val isPresented: Boolean, val onDismiss: () -> Unit) {
    companion object {
        fun <Item> of(item: MutableState<Item?>): ListPresence = ListPresence(item.value != null) { item.value = null }
    }
}
