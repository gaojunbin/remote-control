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
import androidx.compose.ui.text.font.FontWeight
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The stand-in: one button, one payload, no camera. It is what the demo and the tests scan with,
 * since neither the JVM nor an emulator has a camera to point at a printed code.
 */
class StaticCodeScanner(private val payload: String) : CodeScanning {
    @Composable
    override fun Viewfinder(onCode: (String) -> Unit, modifier: Modifier) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // `.borderedProminent`: the system's filled capsule in the tint colour.
            Text(
                L10n.string("Simulate a scan"),
                modifier = Modifier
                    .testTag("scan.simulate")
                    .background(SystemColor.systemBlue, CapsuleShape)
                    .clickable(role = Role.Button) { onCode(payload) }
                    .padding(horizontal = Theme.Space.medium, vertical = Theme.Space.tight + Theme.Space.hair),
                style = SystemFont.body.weight(FontWeight.SemiBold),
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}
