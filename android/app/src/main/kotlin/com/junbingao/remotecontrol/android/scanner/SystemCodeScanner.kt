package com.junbingao.remotecontrol.android.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.android.attachments.rememberCameraAccessRequest

/** The scanner this build runs on a real phone: the camera, asked for by the screen that shows it. */
object SystemCodeScanner {
    @Composable
    fun make(): CodeScanning {
        val access = rememberCameraAccessRequest()
        return remember(access) { CameraCodeScanner(access) }
    }
}
