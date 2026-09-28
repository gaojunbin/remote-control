import RCCore
import RCMac
import SwiftUI

struct FieldSamples: View {
    @State private var empty = ""
    @State private var filled = "admin"
    @State private var mono = "http://127.0.0.1:8787"
    @State private var search = ""

    var body: some View {
        HStack(alignment: .top, spacing: Space.sp6) {
            VStack(alignment: .leading, spacing: 0) {
                FieldLabel("Username")
                WebField(text: $empty, placeholder: "Username").padding(.bottom, Space.sp4)
                FieldLabel("Filled")
                WebField(text: $filled, placeholder: "Username").padding(.bottom, Space.sp4)
                FieldLabel("Mono")
                WebField(text: $mono, placeholder: "Address", mono: true).padding(.bottom, Space.sp4)
                FormError("Wrong username or password.")
            }
            .frame(width: 320)
            VStack(alignment: .leading, spacing: Space.sp3) {
                SearchField(text: $search, placeholder: "Search sessions")
                TextField("", text: .constant("Focused"))
                    .fieldChrome(focused: true)
                    .frame(width: 320)
                Hint("A hint: secondary text at 13 points.")
            }
        }
    }
}

struct SurfaceSamples: View {
    var body: some View {
        HStack(alignment: .top, spacing: Space.sp6) {
            VStack(alignment: .leading, spacing: 0) {
                GroupTitle("Group title")
                VStack(spacing: 0) {
                    ForEach(["First row", "Second row", "Third row"], id: \.self) { title in
                        Text(title)
                            .frame(maxWidth: .infinity, minHeight: RowHeight.rowH, alignment: .leading)
                            .padding(.horizontal, Space.sp4)
                    }
                }
                .surface()
            }
            .frame(width: 320)
            VStack(alignment: .leading, spacing: 0) {
                GroupTitle("scroll-thin")
                ThinScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(0..<30, id: \.self) { index in
                            Text("Row \(index + 1)").css(FontSize.fs14)
                                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                                .padding(.horizontal, Space.sp4)
                        }
                    }
                }
                .frame(height: 300)
                .surface()
            }
            .frame(width: 220)
            VStack(alignment: .leading, spacing: Space.sp2) {
                DeviceGroupHeader(name: "mac-studio-office", online: true, expanded: true) {}
                DeviceGroupHeader(name: "ci-runner-01", online: false, expanded: false) {}
                ArchiveGroupHeader(count: 3, expanded: false) {}
                ArchiveGroupHeader(count: 3, expanded: true) {}
                EmptyState(title: "No devices yet.", "Add the machine where your coding agents are installed.")
                    .frame(width: 360)
                    .card()
            }
        }
    }
}

struct StatusSamples: View {
    var body: some View {
        HStack(spacing: Space.sp5) {
            ForEach(DotTone.allCases, id: \.self) { tone in
                HStack(spacing: 6) { Dot(.tone(tone)); Text(S.dotToneLabel(tone.rawValue)) }
            }
            HStack(spacing: 6) { OnlineDot(online: true); Text("online") }
            HStack(spacing: 6) { OnlineDot(online: false); Text("offline") }
            HStack(spacing: 6) { OnlineDot(online: true, pulses: true); Text("updating") }
        }
        .font(.system(size: FontSize.fs13))
        .foregroundStyle(Palette.inkSecondary)
    }
}

struct TypeSamples: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp2) {
            Text("Page title 30 · 600").css(FontSize.fs30, weight: .semibold, tracking: -0.02)
            Text("Modal title 22 · 600").css(FontSize.fs22, weight: .semibold, tracking: -0.01)
            Text("Row title 15 · 600").css(FontSize.fs15, weight: .semibold)
            Text("Body 14 — Sign in to reach your devices. 登录后即可访问你的设备。").css(FontSize.fs14)
            Text("Meta 13 in the secondary ink").css(FontSize.fs13).foregroundStyle(Palette.inkSecondary)
            Text("Caption 12 in the tertiary ink").css(FontSize.fs12).foregroundStyle(Palette.inkTertiary)
            Text("~/github/remote-control · main · 127.0.0.1:5173").css(FontSize.fs13, mono: true)
        }
    }
}

struct PaletteSamples: View {
    private let swatches: [(String, Color)] = [
        ("canvas", Palette.canvas), ("surface", Palette.surface), ("surface-sunken", Palette.surfaceSunken),
        ("surface-muted", Palette.surfaceMuted), ("surface-active", Palette.surfaceActive),
        ("line", Palette.line), ("line-strong", Palette.lineStrong), ("hairline", Palette.hairline),
        ("hover", Palette.hover), ("hover-selected", Palette.hoverSelected), ("ink", Palette.ink),
        ("ink-secondary", Palette.inkSecondary), ("ink-tertiary", Palette.inkTertiary),
        ("running", Palette.running), ("running-soft", Palette.runningSoft), ("attention", Palette.attention),
        ("attention-soft", Palette.attentionSoft), ("idle", Palette.idle), ("danger", Palette.danger),
        ("danger-soft", Palette.dangerSoft), ("diff-add", Palette.diffAdd), ("diff-add-bg", Palette.diffAddBg),
        ("diff-del", Palette.diffDel), ("diff-del-bg", Palette.diffDelBg), ("overlay", Palette.overlay)
    ]

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(120), spacing: Space.sp3), count: 8),
                  alignment: .leading, spacing: Space.sp3) {
            ForEach(swatches, id: \.0) { name, color in
                VStack(alignment: .leading, spacing: 4) {
                    RoundedRectangle(cornerRadius: Radius.sm).fill(color).frame(height: 36)
                        .overlay(RoundedRectangle(cornerRadius: Radius.sm).strokeBorder(Palette.hairline))
                    Text(name).css(FontSize.fs11, mono: true).foregroundStyle(Palette.inkSecondary)
                }
            }
        }
    }
}

struct IconSamples: View {
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(104), spacing: Space.sp2), count: 10),
                  alignment: .leading, spacing: Space.sp3) {
            ForEach(LucideIcon.allCases, id: \.self) { icon in
                VStack(spacing: 6) {
                    Icon(icon, size: 24)
                    HStack(spacing: 6) { Icon(icon, size: 16); Icon(icon, size: 13) }
                    Text(icon.rawValue).css(FontSize.fs11, mono: true).foregroundStyle(Palette.inkSecondary)
                        .lineLimit(1)
                }
                .frame(width: 104)
            }
        }
    }
}
