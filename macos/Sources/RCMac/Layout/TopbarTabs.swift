import SwiftUI

/// `.tabs`: Devices, Sessions and Settings, 2 points apart. Built on every
/// draw, so the tabs follow the interface language.
struct TopbarTabs: View {
    let compact: Bool
    let tight: Bool
    @Environment(MacAppModel.self) private var model

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Self.tabs(), id: \.route) { tab in
                TopbarTab(title: tab.title, active: model.router.route.tab == tab.route,
                          compact: compact, tight: tight) {
                    model.router.go(tab.route)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(S.nav.primary)
    }

    static func tabs() -> [(route: Route, title: String)] {
        [(.devices, S.nav.devices), (.sessions, S.nav.sessions), (.settings, S.nav.settings)]
    }
}

/// `.tab`: a 30-point pill in the secondary ink; the pointer darkens it onto
/// `--surface-hover`, and the current one is ink on `--surface-muted` at 500.
/// 13 points with less padding at 760 and narrower, less again at 420.
struct TopbarTab: View {
    let title: String
    let active: Bool
    let compact: Bool
    let tight: Bool
    let action: () -> Void
    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            Text(title)
                .lineLimit(1)
                .css(compact ? FontSize.fs13 : FontSize.fs14, weight: active ? .medium : .regular)
                .fixedSize()
                .padding(.horizontal, tight ? 8 : (compact ? 10 : Space.sp3))
                .frame(height: 30)
                .foregroundStyle(active || isHovered ? Palette.ink : Palette.inkSecondary)
                .background(Capsule().fill(active ? Palette.surfaceMuted
                                           : (isHovered ? Palette.surfaceHover : Color.clear)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .pointerStyle(.link)
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}
