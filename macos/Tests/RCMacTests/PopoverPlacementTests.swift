import CoreGraphics
import Testing
@testable import RCMac

/// `web/tests/popoverPlacement.test.ts`, case for case.
@Suite("Popover placement")
struct PopoverPlacementTests {
    private let viewport = CGSize(width: 1280, height: 800)
    private let panel = CGSize(width: 200, height: 120)

    private func trigger(_ left: CGFloat, _ top: CGFloat) -> CGRect { CGRect(x: left, y: top, width: 28, height: 28) }

    @Test func hangsThePanelUnderTheTriggerWithAGap() {
        let at = PopoverPlacement.place(trigger: trigger(400, 100), panel: panel, viewport: viewport,
                                        align: .start, side: .bottom)
        #expect(at == .init(left: 400, top: 134, bottom: nil))
    }

    @Test func linesThePanelUpWithTheRightEdgeOfTheTrigger() {
        let at = PopoverPlacement.place(trigger: trigger(400, 100), panel: panel, viewport: viewport,
                                        align: .end, side: .bottom)
        #expect(at.left == 228)
    }

    @Test func flipsAboveWhenThePanelDoesNotFitBelow() {
        let at = PopoverPlacement.place(trigger: trigger(400, 720), panel: panel, viewport: viewport,
                                        align: .start, side: .bottom)
        #expect(at.top == nil)
        #expect(at.bottom == 86)
    }

    @Test func flipsBelowWhenTheSideAskedForHasNoRoom() {
        let at = PopoverPlacement.place(trigger: trigger(400, 20), panel: panel, viewport: viewport,
                                        align: .start, side: .top)
        #expect(at.bottom == nil)
        #expect(at.top == 54)
    }

    @Test func staysOnTheSideWithMoreRoomWhenNeitherFits() {
        let at = PopoverPlacement.place(trigger: trigger(400, 300), panel: panel,
                                        viewport: CGSize(width: 1280, height: 420), align: .start, side: .bottom)
        #expect(at.top == nil)
        #expect(at.bottom == 126)
    }

    @Test func keepsThePanelInsideTheWindowOnBothEdges() {
        let right = PopoverPlacement.place(trigger: trigger(1240, 100), panel: panel, viewport: viewport,
                                           align: .start, side: .bottom)
        #expect(right.left == 1072)
        let left = PopoverPlacement.place(trigger: trigger(4, 100), panel: panel, viewport: viewport,
                                          align: .end, side: .bottom)
        #expect(left.left == 8)
    }

    @Test func aPanelAboveIsPlacedByItsTopEdgeToo() {
        let at = PopoverPlacement.place(trigger: trigger(400, 720), panel: panel, viewport: viewport,
                                        align: .start, side: .bottom)
        #expect(PopoverPlacement.top(of: at, panelHeight: 120, viewportHeight: 800) == 594)
    }
}
