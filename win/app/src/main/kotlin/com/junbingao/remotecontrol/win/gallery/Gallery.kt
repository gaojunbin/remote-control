package com.junbingao.remotecontrol.win.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.AgentChip
import com.junbingao.remotecontrol.win.design.AgentLogo
import com.junbingao.remotecontrol.win.design.Badge
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.GroupTitle
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.Mark
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.MenuTriggerStyle
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.SegmentOption
import com.junbingao.remotecontrol.win.design.Segmented
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Spinner
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.VStackScope
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.pill
import com.junbingao.remotecontrol.win.design.quietPill
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.standin.TimelineDetail
import com.junbingao.remotecontrol.win.strings.S

/**
 * Every primitive of `design/`, in each of the states a render can show: the reference the
 * feature agents build their screens from, and the page the foundation's fidelity is checked on
 * beside the Mac renderer's `gallery`. The window shows it until the screens arrive.
 */
@Composable
fun Gallery() {
    GalleryPage("Gallery", "Every primitive of Design/, in the states a render can show.") {
        GallerySection("Buttons") { ButtonSamples() }
        GallerySection("Pills, badges, chips") { ChipSamples() }
        GallerySection("Controls") { ControlSamples() }
        GallerySection("Fields") { FieldSamples() }
        GallerySection("Surface, captions, group headers") { SurfaceSamples() }
        GallerySection("Status") { StatusSamples() }
    }
}

/**
 * The tokens and the icons: every colour of `tokens.css`, the type scale, and every lucide icon
 * the web imports at the sizes the web draws them.
 */
@Composable
fun TokenGallery() {
    GalleryPage("Tokens and icons", "tokens.css, and lucide as the web imports it.") {
        GallerySection("Type") { TypeSamples() }
        GallerySection("Palette") { PaletteSamples() }
        GallerySection("Icons") { IconSamples() }
    }
}

@Composable
fun GalleryPage(title: String, hint: String, content: @Composable VStackScope.() -> Unit) {
    ThinScrollView(modifier = Modifier.fillMaxSize().background(Palette.canvas)) {
        VStack(Modifier.fillMaxWidth().padding(Space.sp8), spacing = Space.sp8, alignment = Alignment.Start) {
            PageHead(title, hint)
            content()
        }
    }
}

@Composable
fun GallerySection(title: String, content: @Composable VStackScope.() -> Unit) {
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        GroupTitle(title)
        content()
    }
}

@Composable
private fun ButtonSamples() {
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        HStack(spacing = Space.sp3) {
            Btn("Default") {}
            Btn("Add device", icon = LucideIcon.plus, variant = ButtonVariant.primary) {}
            Btn("Revoke", variant = ButtonVariant.danger) {}
            Btn("Ghost", variant = ButtonVariant.ghost) {}
            Btn("Saving", variant = ButtonVariant.primary, busy = true) {}
            Disabled { Btn("Disabled") {} }
            Disabled { Btn("Primary disabled", variant = ButtonVariant.primary) {} }
        }
        HStack(spacing = Space.sp3) {
            Btn("Small", size = ButtonSize.small) {}
            Btn("Small primary", variant = ButtonVariant.primary, size = ButtonSize.small) {}
            Btn("Small danger", variant = ButtonVariant.danger, size = ButtonSize.small) {}
            IconBtn(LucideIcon.x, label = "Close") {}
            IconBtn(LucideIcon.moreHorizontal, label = "Open menu") {}
            Button({}, style = MenuTriggerStyle()) { Icon(LucideIcon.moreHorizontal, size = 16.dp) }
        }
        Btn("Sign in", variant = ButtonVariant.primary, size = ButtonSize.block, modifier = Modifier.width(316.dp)) {}
    }
}

private val sampleAgents = listOf("claude", "codex", "grok", "pi", "cursor")

@Composable
private fun ChipSamples() {
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        HStack(spacing = Space.sp3) {
            Button({}, style = pill) { Text("Pill") }
            Button({}, style = pill) {
                HStack(spacing = 6.dp) {
                    Icon(LucideIcon.checkSquare, size = 13.dp)
                    Text("Todos 2/5")
                }
            }
            Disabled { Button({}, style = pill) { Text("Disabled") } }
            Button({}, style = quietPill) { Text("Quiet · 48.2k · 1m 12s") }
            Badge("Badge")
            Badge("Warn", tone = Badge.Tone.warn)
            Badge("Error", tone = Badge.Tone.error)
        }
        HStack(spacing = Space.sp3) {
            for (agent in sampleAgents) AgentChip(agent)
        }
        HStack(spacing = Space.sp4) {
            for (agent in sampleAgents) {
                WithForeground(Palette.inkSecondary) {
                    HStack(spacing = 6.dp) {
                        AgentLogo(agent, size = 14f)
                        Text(S.agentLabel(agent))
                    }
                }
            }
            Mark(size = 20.dp)
            Mark(size = 26.dp)
            Mark(size = 40.dp)
        }
    }
}

@Composable
private fun ControlSamples() {
    var detail by remember { mutableStateOf(TimelineDetail.simple) }
    var strength by remember { mutableStateOf("moderate") }
    VStack(spacing = Space.sp3, alignment = Alignment.Start) {
        HStack(spacing = Space.sp4) {
            Switch(isOn = true, label = "On") {}
            Switch(isOn = false, label = "Off") {}
            Disabled { Switch(isOn = true, label = "Disabled") {} }
            Spinner()
            Segmented(
                value = detail,
                options = listOf(
                    SegmentOption(TimelineDetail.simple, S.timelineDetailLabel(TimelineDetail.simple)),
                    SegmentOption(TimelineDetail.detailed, S.timelineDetailLabel(TimelineDetail.detailed)),
                ),
                ariaLabel = "Detail",
                modifier = Modifier.width(240.dp),
            ) { detail = it }
            Segmented(
                value = strength,
                options = listOf(SegmentOption("moderate", "Moderate"), SegmentOption("strong", "Strong", disabled = true)),
                ariaLabel = "Strength",
                modifier = Modifier.width(240.dp),
            ) { strength = it }
        }
        // Sized by their labels, as the rows of Settings draw them.
        HStack(spacing = Space.sp4) {
            Segmented("moderate", listOf(SegmentOption("moderate", "Moderate"), SegmentOption("strong", "Strong")), "Strength") {}
            Segmented("en", listOf(SegmentOption("en", "English"), SegmentOption("zh-Hans", "中文")), "Language") {}
            Segmented(
                TimelineDetail.simple,
                listOf(
                    SegmentOption(TimelineDetail.simple, S.timelineDetailLabel(TimelineDetail.simple)),
                    SegmentOption(TimelineDetail.detailed, S.timelineDetailLabel(TimelineDetail.detailed)),
                ),
                "Detail",
            ) {}
        }
        MenuList(Modifier.width(240.dp).card(cornerRadius = Radius.md, shadow = Shadow.pop).padding(Space.sp1)) {
            MenuItemRow("Rename") {}
            MenuItemRow("Selected option", description = "With a description under it", selected = true) {}
            Disabled { MenuItemRow("Disabled") {} }
            MenuItemRow("Revoke", danger = true) {}
        }
    }
}
