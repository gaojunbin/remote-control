package com.junbingao.remotecontrol.android.scanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.attachments.CameraAccess

/**
 * What the pairing screen needs from a camera (amendment A23, `CodeScanner.swift`): a view that
 * reports every payload it reads and keeps reading afterwards, because a code for another gateway
 * is answered in the strip above it rather than by closing the camera.
 *
 * This is what lets the demo and a test run the whole flow with no camera at all: they supply a
 * stand-in that hands over a printed payload on a tap, and everything above it — the overlay, the
 * claim, the errors — is the same either way.
 */
interface CodeScanning {
    /**
     * Whether the app may open this scanner's camera, asked of the person the first time.
     * `docs/DESIGN.md` § "The three screens" → **A camera the app may not use says so**: the
     * scanner is never drawn over a refusal. A scanner with no camera behind it has nothing to
     * ask for.
     */
    suspend fun requestAccess(): CameraAccess = CameraAccess.allowed

    /** The viewfinder, reporting each payload it reads on the main thread. */
    @Composable
    fun Viewfinder(onCode: (String) -> Unit, modifier: Modifier)
}
