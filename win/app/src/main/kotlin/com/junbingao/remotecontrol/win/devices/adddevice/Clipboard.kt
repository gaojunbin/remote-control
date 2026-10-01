package com.junbingao.remotecontrol.win.devices.adddevice

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** `navigator.clipboard.writeText`, on the system clipboard. */
object Clipboard {
    fun write(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
}
