import CoreText
import RCCore
import SwiftUI
import Testing
@testable import RCMac

@Suite("Design")
struct DesignTests {
    /// Chrome's baselines for the web's type, measured with a zero-height
    /// inline-block on the baseline of each line (`--sp`-free, 1× CSS px).
    @Test func baselinesAreTheBrowsers() {
        let measured: [(CGFloat, CGFloat, CGFloat)] = [
            (14, 1.5, 16), (13, 1.5, 14), (12, 1.5, 13), (11, 1.5, 12), (15, 1.5, 17), (17, 1.5, 18),
            (22, 1.5, 24), (30, 1.5, 34), (12, 1.4, 12), (13, 1.4, 14), (14, 1.4, 15), (14, 1, 12), (30, 1, 26)
        ]
        for (size, lineHeight, baseline) in measured {
            #expect(TextStyle(size: size, lineHeight: lineHeight).baseline == baseline, "\(size)px × \(lineHeight)")
        }
    }

    /// Chrome's widths for a middle dot line, the same under `lang="en"` and
    /// `lang="zh-Hans"`: the dot is the system face's own, never the CJK
    /// symbols font a Chinese-first Mac would otherwise hand it to.
    @Test func aMiddleDotMeasuresWhatTheBrowsersDoes() {
        let measured: [(String, CGFloat, NSFont.Weight, CGFloat)] = [
            ("Up next · 3", 12, .medium, 62.70), ("web · 13m", 13, .regular, 61.22)
        ]
        for (text, size, weight, chrome) in measured {
            let font = SystemFace.font(size: size, weight: weight)
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [.font: font]))
            #expect(abs(CTLineGetTypographicBounds(line, nil, nil, nil) - chrome) < 0.2, "\(text)")
            #expect((CTLineGetGlyphRuns(line) as? [CTRun])?.count == 1, "\(text) is drawn in one face")
        }
    }

    @Test func theTokensAreTheStylesheets() {
        #expect(LayoutSize.contentMax == 1080 && LayoutSize.headerH == 60 && LayoutSize.sidebarW == 264)
        #expect(RowHeight.rowH == 64 && Radius.lg == 16 && Space.sp6 == 24)
        let canvas = NSColor(Palette.canvas).usingColorSpace(.sRGB)!
        #expect(Int((canvas.redComponent * 255).rounded()) == 0xF5)
        #expect(Int((canvas.blueComponent * 255).rounded()) == 0xF4)
        #expect(Shadow.soft.layers.count == 2)
    }

    @Test func theBreakpointsHoldAtTheirOwnWidth() {
        #expect(LayoutClass(width: 760, height: 800).maxWidth760)
        #expect(!LayoutClass(width: 761, height: 800).maxWidth760)
        #expect(LayoutClass(width: 1023, height: 800).maxWidth1023)
        #expect(!LayoutClass(width: 1024, height: 800).maxWidth1023)
    }

    @Test func svgPathDataIsReadWithItsShorthand() {
        // Numbers run together, relative commands, and arc flags with no separator.
        let path = SVGPath.parse("M2 2h20v20H2zm4.5-1.5.5.5a1 1 0 011 1")
        let box = path.boundingRect
        #expect(abs(box.minX - 2) < 0.01 && abs(box.maxX - 22) < 0.01)
        #expect(abs(box.minY - 0.5) < 0.01 && abs(box.maxY - 22) < 0.01)
        #expect(SVGPath.points("3 4, 5 6 7,8").count == 3)
    }

    @Test func everyIconHasADrawing() {
        for icon in LucideIcon.allCases {
            #expect(!icon.path.isEmpty, "\(icon.rawValue)")
            let box = icon.path.boundingRect
            #expect(box.minX >= 0 && box.maxX <= 24 && box.minY >= 0 && box.maxY <= 24, "\(icon.rawValue)")
        }
        #expect(LucideIcon.allCases.count == 38)
    }

    @Test func everyAgentTheWebDrawsHasItsLogo() {
        for agent in ["claude", "codex", "grok", "pi"] {
            let logo = try! #require(AgentLogoArt.logos[agent])
            #expect(logo.paths.allSatisfy { !$0.path.isEmpty }, "\(agent)")
        }
    }
}

extension LanguageSensitive {
    /// The Chrome widths below were taken on macOS 27. macOS 26 still gives PingFang
    /// SC its size-specific tracking at optical size 0, so Chinese sets 2 % wider
    /// there (a CI runner on 26.6 measures 544 where Chrome and macOS 27 give 533):
    /// the exact Chinese widths are checked on the release they were measured on.
    static let chineseWidthsApply = ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 27

    /// Chinese as Chrome sets it: `lang` the interface language, the web's
    /// font stack, 13 px / 1.45 (the Settings row sentence). The sizes are
    /// Chrome's; a window with no screen rounds a view up to a whole point.
    @Suite("Chinese type") @MainActor
    struct ChineseTypeTests {
        private let polish = "开启后，会把你的听写内容和最近几条消息发给此网关配置的模型；关闭时不发送任何内容。"
        private let style = TextStyle(size: FontSize.fs13, lineHeight: 1.45)

        init() { InterfaceLanguageSource.shared.current = .en }

        private func size(_ text: String, width: CGFloat? = nil) -> CGSize {
            NSHostingView(rootView: Text(text).textStyle(style).frame(width: width)
                .fixedSize(horizontal: width == nil, vertical: true)).fittingSize
        }

        @Test(.enabled(if: LanguageSensitive.chineseWidthsApply))
        func aLineMeasuresWhatChromesDoesUnderZhHans() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            let measured: [(String, CGFloat)] = [
                (polish, 533.00), ("“适度”只做清理；“加强”还会重组语句并明确指代。", 296.56),
                ("简约只显示写给你的内容。详细会加上思考、工具调用和任务清单。", 390.00),
                ("缓存的会话和草稿将从此设备移除，你的机器不受影响。", 325.00)
            ]
            for (text, chrome) in measured {
                let width = size(text).width
                #expect(width >= chrome && width < chrome + 1, "\(text.prefix(6)): \(width) against \(chrome)")
            }
        }

        /// Chrome breaks after 30 characters at 400 px, and puts the last two
        /// characters of the note on a line of their own at 520 and 530 px, where
        /// the full stop may not start a line.
        @Test func wrappedLinesAreChromesUnderZhHans() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            let measured: [(String, CGFloat, CGFloat)] = [
                (polish + polish + polish, 400, 94.22), (polish, 520, 37.69), (polish, 530, 37.69)
            ]
            for (text, width, chrome) in measured {
                let height = size(text, width: width).height
                #expect(height >= chrome && height < chrome + 1, "\(width) px: \(height) against \(chrome)")
            }
        }

        /// Under `en` Chrome draws Han in the system face's own cascade, as CoreText does.
        @Test func chineseInAnEnglishInterfaceIsTheSystemFacesCascade() {
            let width = NSHostingView(rootView: Text("中文").textStyle(TextStyle(size: FontSize.fs14)).fixedSize())
                .fittingSize.width
            #expect(width >= 27.80 && width < 28.80)
        }

        /// Settings' Segmented controls, sized by their labels: Chrome's widths
        /// of the whole control, 3 px inset and between, each segment its label
        /// and the button's 6 px on either side.
        @Test func aSegmentedSizedByItsLabelsIsChromes() {
            let measured: [(InterfaceLanguage, String, String, CGFloat)] = [
                (.en, "Moderate", "Strong", 138.16), (.en, "English", "中文", 107.36),
                (.en, "Simple", "Detailed", 130.13), (.zhHans, "适度", "加强", 89.00),
                (.zhHans, "English", "中文", 107.56), (.zhHans, "简约", "详细", 89.00)
            ]
            defer { InterfaceLanguageSource.shared.current = .en }
            for (language, first, second, chrome) in measured
            where language == .en || LanguageSensitive.chineseWidthsApply {
                InterfaceLanguageSource.shared.current = language
                let control = Segmented(value: first, options: [
                    SegmentOption(value: first, label: first), SegmentOption(value: second, label: second)
                ], ariaLabel: "") { _ in }
                let width = NSHostingView(rootView: control.fixedSize()).fittingSize.width
                #expect(abs(width - chrome) < 1.5, "\(first) | \(second): \(width) against \(chrome)")
            }
        }
    }
}

/// `.scroll-thin` as the browsers draw it: the system's thin scroll bar, which
/// takes no room as an overlay and the small legacy scroller's width otherwise.
@Suite("Thin scroll bars") @MainActor
struct ThinScrollTests {
    @Test func anAxisReadsAsTheScrollerDoes() {
        let long = ScrollAxis(content: 720, visible: 200, offset: 130)
        #expect(long.overflows && long.maxOffset == 520)
        #expect(abs(long.knobProportion - 200.0 / 720) < 0.0001)
        #expect(abs(long.value - 0.25) < 0.0001)
        #expect(long.offset(for: 0.5) == 260 && long.offset(for: 1.5) == 520 && long.offset(for: -1) == 0)
        // WebKit's page: seven eighths of what shows, or all of it but 40 points.
        #expect(long.page == 175)
        #expect(ScrollAxis(content: 1000, visible: 600, offset: 0).page == 560)
        let short = ScrollAxis(content: 200, visible: 200, offset: 0)
        #expect(!short.overflows && short.knobProportion == 1 && short.value == 0)
    }

    /// Whatever this Mac's setting, the content and the bar share the pane: the
    /// content is as wide as the pane less the room the bar takes, which is
    /// none as an overlay — and, as the small legacy scroller, that scroller
    /// stands exactly where the content ends.
    @Test func theContentEndsWhereTheBarBegins() async {
        let width = WidthBox()
        let pane = ThinScrollView {
            VStack(spacing: 0) {
                ForEach(0..<40, id: \.self) { Text("Row \($0)").frame(maxWidth: .infinity).frame(height: 24) }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width.value = $0 }
        }
        .frame(width: 300, height: 200)
        let window = NSWindow(contentRect: NSRect(x: -30000, y: -30000, width: 300, height: 200),
                              styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: pane)
        window.orderFrontRegardless()
        defer { window.close() }
        for _ in 0..<40 where abs(width.value - (300 - ScrollThin.gutter)) > 0.5 {
            try? await Task.sleep(for: .milliseconds(25))
        }
        #expect(abs(width.value - (300 - ScrollThin.gutter)) < 0.5, "\(width.value)")
        let scrollers = Self.views(of: SmallLegacyScroller.self, in: window.contentView)
        if ScrollThin.gutter > 0 {
            #expect(scrollers.count == 1 && abs((scrollers.first?.frame.minX ?? 0) - width.value) < 0.5)
        } else {
            #expect(scrollers.isEmpty)
        }
    }

    private static func views<T: NSView>(of type: T.Type, in view: NSView?) -> [T] {
        guard let view else { return [] }
        return view.subviews.flatMap { ($0 as? T).map { [$0] } ?? views(of: type, in: $0) }
    }
}

@MainActor
private final class WidthBox {
    var value: CGFloat = 0
}
