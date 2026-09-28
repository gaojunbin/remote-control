import SwiftUI

/// `web/src/layout/AppLayout.tsx`: the signed-in shell — the topbar as the
/// window's top strip, and the page scrolling under it at the web's content
/// width (1080, padding included) with its paddings: 32 · 24 · 40, and
/// 20 · 16 · 32 at 760 and narrower. The page fills the window at least, on
/// the canvas.
struct AppLayout<Page: View>: View {
    let page: Page
    @Environment(\.layoutClass) private var layout

    init(@ViewBuilder page: () -> Page) { self.page = page() }

    var body: some View {
        let compact = layout.maxWidth760
        ZStack(alignment: .top) {
            ScrollView {
                page
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(.top, compact ? Space.sp5 : Space.sp8)
                    .padding(.horizontal, compact ? Space.sp4 : Space.sp6)
                    .padding(.bottom, compact ? Space.sp8 : Space.sp10)
                    .frame(maxWidth: LayoutSize.contentMax)
                    .frame(maxWidth: .infinity, minHeight: layout.height - Topbar.height, alignment: .top)
                    .padding(.top, Topbar.height)
            }
            .scrollIndicators(.automatic)
            Topbar()
        }
        .background(Palette.canvas)
    }
}

/// `.boot`: the canvas and nothing else, while the app is deciding what to show.
struct BootView: View {
    var body: some View {
        Palette.canvas.accessibilityLabel(S.common.loading)
    }
}
