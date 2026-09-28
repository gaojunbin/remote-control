import RCCore
import RCMac
import SwiftUI

/// Every primitive of `Design/`, in each of the states a render can show: the
/// reference the feature agents build their screens from, and the page the
/// foundation's fidelity is checked on.
struct Gallery: View {
    var body: some View {
        GalleryPage(title: "Gallery", hint: "Every primitive of Design/, in the states a render can show.") {
            GallerySection("Buttons") { ButtonSamples() }
            GallerySection("Pills, badges, chips") { ChipSamples() }
            GallerySection("Controls") { ControlSamples() }
            GallerySection("Fields") { FieldSamples() }
            GallerySection("Surface, captions, group headers") { SurfaceSamples() }
            GallerySection("Status") { StatusSamples() }
        }
    }
}

/// The tokens and the icons: every colour of `tokens.css`, the type scale, and
/// every lucide icon the web imports at the sizes the web draws them.
struct TokenGallery: View {
    var body: some View {
        GalleryPage(title: "Tokens and icons", hint: "tokens.css, and lucide as the web imports it.") {
            GallerySection("Type") { TypeSamples() }
            GallerySection("Palette") { PaletteSamples() }
            GallerySection("Icons") { IconSamples() }
        }
    }
}

struct GalleryPage<Content: View>: View {
    let title: String
    let hint: String
    let content: Content

    init(title: String, hint: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.hint = hint
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.sp8) {
                PageHead(title, hint: hint)
                content
            }
            .padding(Space.sp8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Palette.canvas)
    }
}

struct GallerySection<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp3) {
            GroupTitle(title)
            content
        }
    }
}

private struct ButtonSamples: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp3) {
            HStack(spacing: Space.sp3) {
                Btn("Default") {}
                Btn("Add device", icon: .plus, variant: .primary) {}
                Btn("Revoke", variant: .danger) {}
                Btn("Ghost", variant: .ghost) {}
                Btn("Saving", variant: .primary, busy: true) {}
                Btn("Disabled") {}.disabled(true)
                Btn("Primary disabled", variant: .primary) {}.disabled(true)
            }
            HStack(spacing: Space.sp3) {
                Btn("Small", size: .small) {}
                Btn("Small primary", variant: .primary, size: .small) {}
                Btn("Small danger", variant: .danger, size: .small) {}
                IconBtn(.x, label: "Close") {}
                IconBtn(.moreHorizontal, label: "Open menu") {}
                Button { } label: { Icon(.moreHorizontal, size: 16) }.buttonStyle(MenuTriggerStyle())
            }
            Btn("Sign in", variant: .primary, size: .block) {}.frame(width: 316)
        }
    }
}

private struct ChipSamples: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp3) {
            HStack(spacing: Space.sp3) {
                Button("Pill") {}.buttonStyle(.pill)
                Button { } label: {
                    HStack(spacing: 6) { Icon(.checkSquare, size: 13); Text("Todos 2/5") }
                }.buttonStyle(.pill)
                Button("Disabled") {}.buttonStyle(.pill).disabled(true)
                Button("Quiet · 48.2k · 1m 12s") {}.buttonStyle(.quietPill)
                Badge("Badge")
                Badge("Warn", tone: .warn)
                Badge("Error", tone: .error)
            }
            HStack(spacing: Space.sp3) {
                ForEach(["claude", "codex", "grok", "pi", "cursor"], id: \.self) { AgentChip(agent: $0) }
            }
            HStack(spacing: Space.sp4) {
                ForEach(["claude", "codex", "grok", "pi", "cursor"], id: \.self) { agent in
                    HStack(spacing: 6) {
                        AgentLogo(agent: agent, size: 14)
                        Text(S.agentLabel(agent))
                    }
                    .foregroundStyle(Palette.inkSecondary)
                }
                Mark(size: 20)
                Mark(size: 26)
                Mark(size: 40)
            }
        }
    }
}

private struct ControlSamples: View {
    @State private var detail = TimelineDetail.simple
    @State private var strength = "moderate"

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp3) {
            HStack(spacing: Space.sp4) {
                Switch(isOn: true, label: "On") { _ in }
                Switch(isOn: false, label: "Off") { _ in }
                Switch(isOn: true, label: "Disabled") { _ in }.disabled(true)
                Spinner()
                Segmented(value: detail, options: [
                    SegmentOption(value: TimelineDetail.simple, label: S.timelineDetailLabel(.simple)),
                    SegmentOption(value: TimelineDetail.detailed, label: S.timelineDetailLabel(.detailed))
                ], ariaLabel: "Detail") { detail = $0 }
                .frame(width: 240)
                Segmented(value: strength, options: [
                    SegmentOption(value: "moderate", label: "Moderate"),
                    SegmentOption(value: "strong", label: "Strong", disabled: true)
                ], ariaLabel: "Strength") { strength = $0 }
                .frame(width: 240)
            }
            MenuList {
                MenuItemRow("Rename") {}
                MenuItemRow("Selected option", description: "With a description under it", selected: true) {}
                MenuItemRow("Disabled") {}.disabled(true)
                MenuItemRow("Revoke", danger: true) {}
            }
            .padding(Space.sp1)
            .frame(width: 240)
            .card(cornerRadius: Radius.md, shadow: Shadow.pop)
        }
    }
}
