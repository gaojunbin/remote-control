package com.junbingao.remotecontrol.win.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.Identity
import com.junbingao.remotecontrol.win.strings.S

/**
 * `IdentityHeader.tsx`: who is signed in and where, as the screen's first element and on the
 * canvas rather than on a surface (`docs/DESIGN.md` § "The Settings screen"). It replaces the rows
 * Signed in as, Connection, Gateway and Gateway version. The host is the one the topbar prints, by
 * the same rule, so the two never disagree.
 */
@Composable
fun IdentityHeader() {
    val model = LocalAppModel.current
    val connection = model.connection
    val name = connection.username.ifEmpty { "—" }
    val word = IdentityDot.word(connection.phase)
    val meta = css(FontSize.fs13, lineHeight = 1.4f)
    HStack(Modifier.fillMaxWidth().padding(bottom = Space.sp6), spacing = Space.sp4) {
        // The topbar's circle at the size a header takes.
        Box(Modifier.size(44.dp).background(Palette.ink, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(Identity.initials(name), css(FontSize.fs16, weight = FontWeight.SemiBold, tracking = 0.02f), color = Palette.inkInverse, softWrap = false)
        }
        VStack(spacing = 0.dp, alignment = Alignment.Start) {
            Text(name, css(FontSize.fs17, weight = FontWeight.SemiBold, lineHeight = 1.35f, tracking = -0.01f), Modifier.semantics { heading() })
            WithForeground(Palette.inkSecondary) {
                SettingsShrinkRow(spacing = Space.sp2, Modifier.padding(top = 1.dp)) {
                    Text(S.roleLabel(connection.user.role.rawValue), meta)
                    Text("·", meta, Modifier.clearAndSetSemantics {})
                    Help(word) {
                        Dot(DotStyle.Tone(IdentityDot.tone(connection.phase)), modifier = Modifier.semantics { contentDescription = word })
                    }
                    MiddleTruncatedHost(Identity.gatewayHost(model.origin), Modifier.givesWay())
                }
            }
        }
    }
}
