package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.system.BarMetrics
import com.junbingao.remotecontrol.android.system.SearchMetrics

/**
 * The search field as it waits in the drawer under the large title: a filled capsule, not the
 * bar's glass, with the magnifying glass and the prompt where the bar's field puts them, drawn out
 * as far as [shown] reaches, on whatever stands behind the navigation bar ([modifier] draws it).
 * A tap starts the search, which moves the field into the bar.
 */
@Composable
internal fun DrawerSearchField(text: String, prompt: String, shown: Dp, onActivate: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(shown)
            .clipToBounds(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .padding(start = Theme.Space.medium, top = SearchDrawerMetrics.top, end = Theme.Space.medium)
                .fillMaxWidth()
                .height(BarMetrics.buttonHeight)
                .clip(CapsuleShape)
                .background(SystemColor.tertiarySystemFill)
                .testTag("search.field")
                .clickable(role = Role.Button, onClick = onActivate)
                .semantics { contentDescription = prompt }
                .padding(start = SearchMetrics.leading, end = SearchMetrics.trailing),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sf.magnifyingglass, font = SystemFont.body.weight(FontWeight.Medium), tint = Theme.ink)
            Text(text.ifEmpty { prompt }, style = SystemFont.body, color = if (text.isEmpty()) SystemColor.secondaryLabel else Theme.ink, lineLimit = 1)
        }
    }
}
