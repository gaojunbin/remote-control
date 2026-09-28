import RCCore
import SwiftUI

/// `QuotaMeter.tsx`: one rate-limit window — its name, the percentage and when
/// it resets on one line, and under both a 4-point meter filled to the used
/// share (`docs/DESIGN.md` § "Quota is a meter, drawn for accounts only").
struct QuotaMeter: View {
    let limit: AgentLimit

    var body: some View {
        let name = AccountWords.windowName(limit)
        let used = AccountWords.usedPercent(limit)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: Space.sp2) {
                Text(name).css(FontSize.fs13).foregroundStyle(Palette.ink)
                Spacer(minLength: 0)
                Text(S.devicePage.percent(used))
                    .monospacedDigit()
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkSecondary)
                if let resetsAt = limit.resetsAt {
                    HStack(spacing: Space.sp2) {
                        Text("·").foregroundStyle(Palette.lineStrong)
                        Text(AccountWords.resetsText(resetsAt)).foregroundStyle(Palette.inkTertiary)
                    }
                    .css(FontSize.fs12)
                }
            }
            MeterTrack(fraction: Double(used) / 100, tone: AccountWords.meterTone(limit.usedPercent))
                .accessibilityElement()
                .accessibilityLabel(S.devicePage.usage(name))
                .accessibilityValue(S.devicePage.percent(used))
        }
    }
}

/// `.device-page-meter-track`: the ink colour until the window is nearly spent;
/// no other colour appears on the page.
private struct MeterTrack: View {
    let fraction: Double
    let tone: AccountWords.MeterTone
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.surfaceMuted)
                Capsule().fill(fill).frame(width: proxy.size.width * fraction)
            }
        }
        .frame(height: 4)
        .clipShape(Capsule())
        .animation(Motion.ease(Motion.dur, reduceMotion: reduceMotion), value: fraction)
    }

    private var fill: Color {
        switch tone {
        case .ink: Palette.ink
        case .warn: Palette.attention
        case .danger: Palette.danger
        }
    }
}
