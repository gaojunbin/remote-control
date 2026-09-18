import Testing
import Foundation
@testable import RCCore

/// Amendment A38: the phone's key bar, checked as a table rather than through a
/// view. Every cap sends the byte sequence a terminal expects, and the sticky
/// Ctrl is spent by exactly one key.
@Suite("Amendment A38, the terminal key bar")
struct TerminalKeyTests {
    @Test("The bar is the order the design lists, and nothing else")
    func order() {
        #expect(TerminalKey.bar == [
            .escape, .tab, .control, .up, .down, .left, .right,
            .controlC, .controlD, .controlZ, .controlR, .controlL,
            .pipe, .slash, .dash, .tilde, .paste
        ])
        #expect(Set(TerminalKey.bar) == Set(TerminalKey.allCases))
        #expect(TerminalKey.bar.map(\.cap).first == "Esc")
    }

    @Test("Each key sends what a terminal expects")
    func bytes() {
        #expect(TerminalKey.escape.bytes == [0x1b])
        #expect(TerminalKey.tab.bytes == [0x09])
        #expect(TerminalKey.up.bytes == [0x1b, 0x5b, 0x41])
        #expect(TerminalKey.down.bytes == [0x1b, 0x5b, 0x42])
        #expect(TerminalKey.right.bytes == [0x1b, 0x5b, 0x43])
        #expect(TerminalKey.left.bytes == [0x1b, 0x5b, 0x44])
        #expect(TerminalKey.controlC.bytes == [0x03])
        #expect(TerminalKey.controlD.bytes == [0x04])
        #expect(TerminalKey.controlZ.bytes == [0x1a])
        #expect(TerminalKey.controlR.bytes == [0x12])
        #expect(TerminalKey.controlL.bytes == [0x0c])
        #expect(TerminalKey.pipe.bytes == [0x7c])
        #expect(TerminalKey.slash.bytes == [0x2f])
        #expect(TerminalKey.dash.bytes == [0x2d])
        #expect(TerminalKey.tilde.bytes == [0x7e])
    }

    @Test("Ctrl and Paste act rather than type")
    func actions() {
        #expect(TerminalKey.control.bytes == nil)
        #expect(TerminalKey.paste.bytes == nil)
    }

    @Test("A letter held with Ctrl is that letter with its top bits cleared")
    func controlBytes() {
        #expect(TerminalControlBytes.forCharacter("c") == [0x03])
        #expect(TerminalControlBytes.forCharacter("C") == [0x03])
        #expect(TerminalControlBytes.forCharacter("d") == [0x04])
        #expect(TerminalControlBytes.forCharacter("[") == [0x1b])
        // The two outside the band every terminal still maps.
        #expect(TerminalControlBytes.forCharacter(" ") == [0x00])
        #expect(TerminalControlBytes.forCharacter("?") == [0x7f])
        // Nothing is invented for a character no terminal has a control for.
        #expect(TerminalControlBytes.forCharacter("1") == nil)
        #expect(TerminalControlBytes.forCharacter("中") == nil)
    }

    @Test("The sticky Ctrl changes one key and then releases")
    func latchSpentByOneKey() {
        var latch = ControlLatch()
        #expect(!latch.isArmed)
        // Not armed: bytes go as they are.
        #expect(latch.apply([0x63]) == [0x63])

        latch.toggle()
        #expect(latch.isArmed)
        #expect(latch.apply([0x63]) == [0x03], "c becomes Ctrl-C")
        #expect(!latch.isArmed, "and the latch is spent")
        #expect(latch.apply([0x63]) == [0x63], "so the next c is a c")
    }

    @Test("A second tap on Ctrl disarms it, so an armed bar is never a trap")
    func latchToggles() {
        var latch = ControlLatch()
        latch.toggle()
        latch.toggle()
        #expect(!latch.isArmed)
        #expect(latch.apply([0x63]) == [0x63])
    }

    @Test("Anything that is not one character spends the latch unchanged")
    func latchPassesSequencesThrough() {
        var latch = ControlLatch()
        latch.toggle()
        #expect(latch.apply([0x1b, 0x5b, 0x41]) == [0x1b, 0x5b, 0x41], "an arrow is an arrow")
        #expect(!latch.isArmed)

        latch.toggle()
        #expect(latch.apply([0x31]) == [0x31], "a digit has no control byte")
        #expect(!latch.isArmed)
    }

    @Test("The type size is remembered inside the bounds a phone can show")
    func typeSize() {
        #expect(TerminalTypeSize.clamp(4) == TerminalTypeSize.minimum)
        #expect(TerminalTypeSize.clamp(99) == TerminalTypeSize.maximum)
        #expect(TerminalTypeSize.clamp(.nan) == TerminalTypeSize.standard)
        #expect(TerminalTypeSize.scaled(12, by: 1.5) == 18)
        #expect(TerminalTypeSize.scaled(12, by: 0.25) == TerminalTypeSize.minimum)
        #expect(TerminalTypeSize.scaled(12, by: 0) == 12)
    }
}
