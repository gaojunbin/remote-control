import RCMac
import SwiftUI

/// `.scroll-thin` in the three shapes the app uses it: a list taller than its
/// pane, one that fits, and a line wider than its box. A render shows the
/// overlay case — no bar and no room taken — whatever the Mac's setting;
/// `-AppleShowScrollBars Always` on the command line shows the small legacy
/// scrollers, each in the room its content leaves beside or under it.
struct ScrollThinGallery: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp4) {
            PageHead("Thin scroll bars") {}
            HStack(alignment: .top, spacing: Space.sp5) {
                pane(rows: 30)
                pane(rows: 3)
                ThinScrollView(.horizontal) {
                    Text(String(repeating: "a line wider than its box ", count: 6))
                        .css(FontSize.fs13, mono: true)
                        .fixedSize()
                        .padding(Space.sp3)
                        .background(Palette.surfaceMuted)
                }
                .frame(width: 300)
                .card()
            }
            Spacer()
        }
        .padding(Space.sp8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.canvas)
    }

    private func pane(rows: Int) -> some View {
        ThinScrollView {
            VStack(spacing: 0) {
                ForEach(0..<rows, id: \.self) { index in
                    Text("Row \(index + 1)")
                        .css(FontSize.fs14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, Space.sp3)
                        .frame(height: 32)
                        .background(index.isMultiple(of: 2) ? Palette.surfaceMuted : Palette.surface)
                }
            }
        }
        .frame(width: 260, height: 240)
        .card()
    }
}
