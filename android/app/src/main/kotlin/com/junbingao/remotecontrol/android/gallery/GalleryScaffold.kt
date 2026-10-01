package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.softSurface
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode

/** A gallery page: an inline title and one scrolling column of captioned specimens. */
@Composable
internal fun GalleryScaffold(title: String, content: @Composable ColumnScope.() -> Unit) {
    NavigationScreen(title, displayMode = TitleDisplayMode.inline) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets.padding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** A captioned specimen on a soft surface. */
@Composable
internal fun Specimen(caption: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, style = Theme.Text.caption, color = SystemColor.secondaryLabel)
        Column(
            Modifier
                .fillMaxWidth()
                .softSurface(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
