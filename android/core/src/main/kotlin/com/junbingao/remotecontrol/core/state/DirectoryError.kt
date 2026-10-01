package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode

/**
 * What the directory picker says when a device refuses to make a folder (A37).
 *
 * A clash is the app's own sentence: `conflict` means exactly one thing here, and the reader should
 * not be shown a path and an errno for it. Every other refusal is the device's own message — only
 * the device knows which rule the name broke, or why it may not write where it was asked to.
 */
object DirectoryError {
    fun makeFolder(error: Throwable): String {
        if (error is GatewayErrorBody && error.code == GatewayErrorCode.conflict) {
            return L10n.string("A folder with that name already exists.")
        }
        return GatewayMessage.text(error)
    }
}
