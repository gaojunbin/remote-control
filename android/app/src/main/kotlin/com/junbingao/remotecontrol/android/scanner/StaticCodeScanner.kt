package com.junbingao.remotecontrol.android.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The stand-in: one button, one payload, no camera. It is what the demo and the tests scan with,
 * since neither the JVM nor an emulator has a camera to point at a printed code.
 */
class StaticCodeScanner(private val payload: String) : CodeScanning {
    @Composable
    override fun Viewfinder(onCode: (String) -> Unit, modifier: Modifier) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // `.borderedProminent`: iOS 26's filled capsule in the tint colour, which is the app's
            // accent, the words in the body's own weight — 144 by 34 points on the iPhone 17.
            Text(
                L10n.string("Simulate a scan"),
                modifier = Modifier
                    .testTag("scan.simulate")
                    .background(Theme.accent, CapsuleShape)
                    .clickable(role = Role.Button) { onCode(payload) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                style = SystemFont.body,
                color = Theme.onAccent,
            )
        }
    }
}
