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
