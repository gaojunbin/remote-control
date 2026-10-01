package com.junbingao.remotecontrol.android.screens.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.core.protocol.UserRecord

/**
 * One account: the username, what it is and how it is doing, and when it was last here. The same
 * four facts the web row carries, in the phone's shape.
 */
@Composable
fun UserRow(record: UserRecord) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
    ) {
        Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(record.username, style = Theme.Text.label, color = Theme.ink, lineLimit = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                Text(record.roleAndState, style = Theme.Text.meta, color = if (record.isActive) Theme.inkSecondary else Theme.attention)
                if (record.deviceSummary.isNotEmpty()) {
                    Text("·", style = Theme.Text.meta, color = Theme.inkSecondary)
                    Text(record.deviceSummary, style = Theme.Text.meta, color = Theme.inkSecondary)
                }
            }
        }
        // `Spacer(minLength: small)`: the row's own spacing either side of it, and its ten points.
        Spacer(Modifier.width(Theme.Space.small))
        Text(record.lastLoginSummary(), Modifier.alignByBaseline(), style = Theme.Text.meta, color = Theme.inkSecondary, lineLimit = 1)
    }
}
