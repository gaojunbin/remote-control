package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.GrowingTextField
import com.junbingao.remotecontrol.android.design.StopSlider
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.SearchField
import com.junbingao.remotecontrol.android.system.Segment
import com.junbingao.remotecontrol.android.system.SegmentedControl
import com.junbingao.remotecontrol.android.system.Switch

/** The fields and controls a screen takes input with. */
@Composable
internal fun InputsGallery() {
    var draft by remember { mutableStateOf("one\ntwo\nthree") }
    var effort by remember { mutableIntStateOf(1) }
    var on by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }
    var language by remember { mutableIntStateOf(0) }
    var agent by remember { mutableIntStateOf(1) }
    var query by remember { mutableStateOf("OTLP") }
    var searching by remember { mutableStateOf(false) }
    GalleryScaffold(GalleryPages.inputs.title) {
        Specimen("Growing field: one line to eight, then it scrolls") {
            Box(
                Modifier
                    .background(Theme.surface, ContinuousShape(Theme.Radius.control))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                GrowingTextField(L10n.string("Message"), draft, { draft = it }, identifier = "gallery.field")
            }
        }
        Specimen("Stop slider: four levels") {
            Text(listOf("Low", "Medium", "High", "Max")[effort], style = Theme.Text.meta, color = Theme.inkSecondary)
            StopSlider(stops = 4, index = effort, value = "$effort", onIndex = { effort = it }, onCommit = { effort = it })
        }
        Specimen("Switch, on and off, and disabled") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(on, { on = it })
                Switch(off, { off = it })
                Switch(isOn = false, onChange = {}, enabled = false)
            }
        }
        Specimen("Segmented control, by label and by logo") {
            SegmentedControl(listOf(Segment("English"), Segment("中文")), language, { language = it })
            SegmentedControl(
                AgentLogo.known.map { Segment(it, image = AgentLogo.vector(it)) },
                agent,
                { agent = it },
                fill = true,
            )
        }
        Specimen("Search field: a tap starts the search, the close button ends it") {
            SearchField(
                query,
                { query = it },
                prompt = L10n.string("Search sessions"),
                isActive = searching,
                onActiveChange = { searching = it },
                onCancel = {
                    query = ""
                    searching = false
                },
            )
        }
    }
}
