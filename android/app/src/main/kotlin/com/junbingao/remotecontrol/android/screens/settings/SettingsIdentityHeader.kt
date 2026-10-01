package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.StatusDot
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.Truncation
import com.junbingao.remotecontrol.android.design.scaledMetric
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.ConnectionTone
import com.junbingao.remotecontrol.core.state.IdentityLine
import com.junbingao.remotecontrol.core.state.Initials
import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * Who is signed in, and where.
 *
 * `docs/DESIGN.md` § "The Settings screen": the screen opens with this on the canvas rather than
 * in a card — a circle of initials, the username beside it, and `role · host` under them with the
 * connection's dot before the host. It replaced four rows that each said a quarter of it: Gateway,
 * Signed in as, Connection and Gateway version.
 *
 * The word for the dot is the accessibility label and is never printed: the colour says it on
 * screen, as it does on every other row in the app.
 */
@Composable
fun SettingsIdentityHeader(
    user: UserIdentity,
    host: String,
    phase: ConnectionPhase,
    /**
     * Held so a change of interface language rebuilds the role and the dot's word where they
     * stand: the core builds them, and its words are not watched (`SettingsLabel`).
     */
    language: InterfaceLanguage,
) {
    val diameter = scaledMetric(44.dp, relativeTo = SystemFont.title2)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Theme.Space.small)
            // The tag before the clearing: it clears everything after it, a tag included.
            .testTag("settings.identity")
            .clearAndSetSemantics { contentDescription = IdentityLine.label(user = user, host = host, phase = phase) },
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(diameter).background(Theme.ink, CircleShape), contentAlignment = Alignment.Center) {
            Text(Initials.of(user.username), style = SystemFont.headline, color = Theme.onAccent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(user.username, style = Theme.Text.title, color = Theme.ink, lineLimit = 1, truncation = Truncation.middle)
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Text(user.role.title, style = Theme.Text.meta, color = Theme.inkSecondary)
                Text("·", style = Theme.Text.meta, color = Theme.inkSecondary)
                StatusDot(ConnectionTone.dot(phase))
                // The head of an origin is the same on every gateway a person has; the tail is
                // which one this is.
                Text(host, style = Theme.Text.meta, color = Theme.inkSecondary, lineLimit = 1, truncation = Truncation.middle)
            }
        }
    }
}
