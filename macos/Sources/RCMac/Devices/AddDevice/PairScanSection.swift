import RCCore
import SwiftUI

/// `.pair-scan` (A23): the second way in, under the code flow rather than
/// beside it — the bare installer to run on the host, and what to do with the
/// QR code it prints. The Mac has no pairing-link screen, so the sentence sends
/// the link to a browser (`docs/DESIGN.md` § "The Mac app").
struct PairScanSection: View {
    let pairing: AddDevicePairing
    let command: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Rectangle().fill(Palette.line).frame(height: 1)
            Text(S.pairing.scanTitle)
                .css(FontSize.fs13, weight: .semibold)
                .padding(.top, Space.sp4)
                .padding(.bottom, Space.sp3)
            HStack(alignment: .top, spacing: Space.sp3) {
                PairCommandText(command)
                Btn(pairing.copied == .scan ? S.common.copied : S.common.copy, size: .small) {
                    pairing.copy(command, as: .scan)
                }
            }
            .padding(.vertical, Space.sp3)
            .padding(.horizontal, Space.sp4)
            .pairingBox(fill: Palette.surfaceSunken)
            Hint(S.mac.pairingScanBody)
                .frame(maxWidth: TextMeasure.ch(FontSize.fs13) * 52, alignment: .leading)
                .padding(.top, Space.sp3)
        }
    }
}

/// "No curl on the host?" and the steps it reveals: install the client with
/// pip, then pair it with the code on screen.
struct ManualInstall: View {
    let pairing: AddDevicePairing
    let origin: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text("\(S.pairing.noCurl) ").css(FontSize.fs13).foregroundStyle(Palette.inkSecondary)
                PairLink(S.pairing.manualInstall, size: FontSize.fs13) { pairing.manual.toggle() }
            }
            if pairing.manual {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(S.pairing.manualSteps.enumerated()), id: \.offset) { index, line in
                        ManualStep(number: index + 1) {
                            Text(line).css(FontSize.fs13).foregroundStyle(Palette.inkSecondary)
                        }
                    }
                    ManualStep(number: S.pairing.manualSteps.count + 1) {
                        Text(TextMeasure.breakAll(S.pairing.manualPairCommand(origin, pairing.grant?.code ?? "RC-XXXX-XXXX")))
                            .css(FontSize.fs12, mono: true)
                            .textSelection(.enabled)
                            .padding(.vertical, 2)
                            .padding(.horizontal, 6)
                            .background(RoundedRectangle(cornerRadius: 6, style: .circular).fill(Palette.surfaceMuted))
                    }
                }
                .padding(.top, Space.sp3)
            }
        }
    }
}

/// One item of `ol.pair-manual-steps`: its number hung in the list's 20-point
/// gutter, as a browser draws an outside marker, and the step after it.
private struct ManualStep<Content: View>: View {
    let number: Int
    @ViewBuilder let content: Content

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text("\(number). ")
                .css(FontSize.fs13)
                .foregroundStyle(Palette.inkSecondary)
                .frame(width: Space.sp5, alignment: .trailing)
            content.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
