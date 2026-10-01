package com.junbingao.remotecontrol.win.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.win.design.ArchiveGroupHeader
import com.junbingao.remotecontrol.win.design.DeviceGroupHeader
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.EmptyState
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FieldText
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.GroupTitle
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.OnlineDot
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.RowHeight
import com.junbingao.remotecontrol.win.design.SearchField
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.WithFont
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.fieldChrome
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.surface
import com.junbingao.remotecontrol.win.strings.S

@Composable
internal fun FieldSamples() {
    var empty by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf("admin") }
    var mono by remember { mutableStateOf("http://127.0.0.1:8787") }
    var search by remember { mutableStateOf("") }
    HStack(spacing = Space.sp6, alignment = Alignment.Top) {
        VStack(Modifier.width(320.dp), spacing = 0.dp, alignment = Alignment.Start) {
            FieldLabel("Username")
            WebField(empty, { empty = it }, placeholder = "Username", modifier = Modifier.padding(bottom = Space.sp4))
            FieldLabel("Filled")
            WebField(filled, { filled = it }, placeholder = "Username", modifier = Modifier.padding(bottom = Space.sp4))
            FieldLabel("Mono")
            WebField(mono, { mono = it }, placeholder = "Address", mono = true, modifier = Modifier.padding(bottom = Space.sp4))
            FormError("Wrong username or password.")
        }
        VStack(spacing = Space.sp3, alignment = Alignment.Start) {
            SearchField(search, { search = it }, placeholder = "Search sessions")
            Box(Modifier.width(320.dp).fieldChrome(focused = true), contentAlignment = Alignment.CenterStart) {
                FieldText("Focused", {}, modifier = Modifier.fillMaxWidth())
            }
            Hint("A hint: secondary text at 13 points.")
        }
    }
}

@Composable
internal fun SurfaceSamples() {
    HStack(spacing = Space.sp6, alignment = Alignment.Top) {
        VStack(Modifier.width(320.dp), spacing = 0.dp, alignment = Alignment.Start) {
            GroupTitle("Group title")
            VStack(Modifier.fillMaxWidth().surface(), spacing = 0.dp) {
                for (title in listOf("First row", "Second row", "Third row")) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight.rowH).padding(horizontal = Space.sp4),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(title) }
                }
            }
        }
        VStack(Modifier.width(220.dp), spacing = 0.dp, alignment = Alignment.Start) {
            GroupTitle("scroll-thin")
            ThinScrollView(modifier = Modifier.fillMaxWidth().height(300.dp).surface()) {
                VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
                    for (index in 0 until 30) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = Space.sp4),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text("Row ${index + 1}", css(FontSize.fs14)) }
                    }
                }
            }
        }
        VStack(spacing = Space.sp2, alignment = Alignment.Start) {
            DeviceGroupHeader("mac-studio-office", online = true, expanded = true) {}
            DeviceGroupHeader("ci-runner-01", online = false, expanded = false) {}
            ArchiveGroupHeader(count = 3, expanded = false) {}
            ArchiveGroupHeader(count = 3, expanded = true) {}
            EmptyState(
                "Add the machine where your coding agents are installed.",
                title = "No devices yet.",
                modifier = Modifier.width(360.dp).card(),
            )
        }
    }
}

@Composable
internal fun StatusSamples() {
    WithFont(FontSize.fs13) {
        WithForeground(Palette.inkSecondary) {
            HStack(spacing = Space.sp5) {
                for (tone in DotTone.entries) {
                    HStack(spacing = 6.dp) {
                        Dot(DotStyle.Tone(tone))
                        Text(S.dotToneLabel(tone.rawValue))
                    }
                }
                HStack(spacing = 6.dp) { OnlineDot(online = true); Text("online") }
                HStack(spacing = 6.dp) { OnlineDot(online = false); Text("offline") }
                HStack(spacing = 6.dp) { OnlineDot(online = true, pulses = true); Text("updating") }
            }
        }
    }
}

@Composable
internal fun TypeSamples() {
    VStack(spacing = Space.sp2, alignment = Alignment.Start) {
        Text("Page title 30 · 600", css(FontSize.fs30, weight = FontWeight.SemiBold, tracking = -0.02f))
        Text("Modal title 22 · 600", css(FontSize.fs22, weight = FontWeight.SemiBold, tracking = -0.01f))
        Text("Row title 15 · 600", css(FontSize.fs15, weight = FontWeight.SemiBold))
        Text("Body 14 — Sign in to reach your devices. 登录后即可访问你的设备。", css(FontSize.fs14))
        Text("Meta 13 in the secondary ink", css(FontSize.fs13), color = Palette.inkSecondary)
        Text("Caption 12 in the tertiary ink", css(FontSize.fs12), color = Palette.inkTertiary)
        Text("~/github/remote-control · main · 127.0.0.1:5173", css(FontSize.fs13, mono = true))
    }
}

private val swatches: List<Pair<String, Color>> = listOf(
    "canvas" to Palette.canvas, "surface" to Palette.surface, "surface-sunken" to Palette.surfaceSunken,
    "surface-muted" to Palette.surfaceMuted, "surface-active" to Palette.surfaceActive,
    "line" to Palette.line, "line-strong" to Palette.lineStrong, "hairline" to Palette.hairline,
    "hover" to Palette.hover, "hover-selected" to Palette.hoverSelected, "ink" to Palette.ink,
    "ink-secondary" to Palette.inkSecondary, "ink-tertiary" to Palette.inkTertiary,
    "running" to Palette.running, "running-soft" to Palette.runningSoft, "attention" to Palette.attention,
    "attention-soft" to Palette.attentionSoft, "idle" to Palette.idle, "danger" to Palette.danger,
    "danger-soft" to Palette.dangerSoft, "diff-add" to Palette.diffAdd, "diff-add-bg" to Palette.diffAddBg,
    "diff-del" to Palette.diffDel, "diff-del-bg" to Palette.diffDelBg, "overlay" to Palette.overlay,
)

@Composable
internal fun PaletteSamples() {
    // A grid of fixed columns, as the Mac's `LazyVGrid` lays it out: rows of cells, each row as
    // tall as its tallest and its cells centred in it.
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        for (row in swatches.chunked(8)) {
            HStack(spacing = Space.sp3) {
                for ((name, color) in row) {
                    VStack(Modifier.width(120.dp), spacing = 4.dp, alignment = Alignment.Start) {
                        val shape = RoundedCornerShape(Radius.sm)
                        Box(Modifier.fillMaxWidth().height(36.dp).background(color, shape).border(1.dp, Palette.hairline, shape))
                        Text(name, css(FontSize.fs11, mono = true), color = Palette.inkSecondary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun IconSamples() {
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        for (row in LucideIcon.entries.chunked(10)) {
            HStack(spacing = Space.sp2) {
                for (icon in row) {
                    VStack(Modifier.width(104.dp), spacing = 6.dp) {
                        Icon(icon, size = 24.dp)
                        HStack(spacing = 6.dp) {
                            Icon(icon, size = 16.dp)
                            Icon(icon, size = 13.dp)
                        }
                        Text(icon.id, css(FontSize.fs11, mono = true), color = Palette.inkSecondary, lineLimit = 1)
                    }
                }
            }
        }
    }
}
