import SwiftUI

/// `StatusLine` in `TerminalPage.tsx`: Connecting · Connected · Disconnected
/// with a Reconnect, and the shell's own end with a New shell — one thin line
/// holding the state, why it is that state, and the one action it earns. A gap
/// in `seq` is said rather than guessed at: bytes were lost, and the screen
/// below is missing them.
struct TerminalStatusLine: View {
    let status: TerminalScreen.Status
    let exitCode: Int?
    let reason: String?
    let missedOutput: Bool
    let onReconnect: () -> Void
    let onRestart: () -> Void

    var body: some View {
        SettingsFlexWrap(spacing: Space.sp2, lineSpacing: 0) {
            Text(word).css(FontSize.fs12, lineHeight: 1.45).foregroundStyle(tint)
            if let reason { TerminalReason(text: reason) }
            if missedOutput { TerminalReason(text: S.terminal.gap) }
            if status == .disconnected { TerminalAction(title: S.terminal.reconnect, action: onReconnect) }
            if status == .exited { TerminalAction(title: S.terminal.newShell, action: onRestart) }
        }
        .foregroundStyle(Palette.inkSecondary)
        .accessibilityElement(children: .contain)
    }

    private var word: String {
        switch status {
        case .exited: exitCode.map { S.terminal.exitedCode($0) } ?? S.terminal.exited
        case .connected: S.terminal.connected
        case .disconnected: S.terminal.disconnected
        case .connecting: S.terminal.connecting
        }
    }

    private var tint: Color {
        switch status {
        case .connected: Palette.running
        case .disconnected: Palette.attention
        case .connecting, .exited: Palette.inkSecondary
        }
    }
}

/// `.terminal-reason`: what the state has to say, after a faint dot.
private struct TerminalReason: View {
    let text: String

    var body: some View {
        (Text("· ").foregroundStyle(Palette.lineStrong) + Text(text))
            .css(FontSize.fs12, lineHeight: 1.45)
    }
}

/// `.link-btn.terminal-action`: an underlined word in the ink, the underline
/// two points below the baseline as `text-underline-offset: 2px` puts it.
private struct TerminalAction: View {
    let title: String
    let action: () -> Void

    private static let style = TextStyle(size: FontSize.fs12, lineHeight: 1.45)

    var body: some View {
        Button(action: action) {
            Text(title)
                .textStyle(Self.style)
                .foregroundStyle(Palette.ink)
                .overlay(alignment: .topLeading) {
                    Rectangle()
                        .fill(Palette.ink)
                        .frame(height: 1)
                        .offset(y: Self.style.baseline + 2)
                }
        }
        .buttonStyle(.plain)
        .pointerStyle(.link)
    }
}
