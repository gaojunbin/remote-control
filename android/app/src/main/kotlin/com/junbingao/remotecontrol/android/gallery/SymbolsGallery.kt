package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf

/** Every SF Symbol the iPhone draws, at the body size, each under its iPhone name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SymbolsGallery() {
    GalleryScaffold(GalleryPages.symbols.title) {
        Specimen("${Sf.all.size} symbols") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (symbol in Sf.all) {
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(symbol, font = SystemFont.body, tint = Theme.ink)
                        Text(
                            symbol.name,
                            style = SystemFont.system(6.5f),
                            color = Theme.inkSecondary,
                            alignment = TextAlign.Center,
                            lineLimit = 3,
                        )
                    }
                }
            }
        }
    }
}
