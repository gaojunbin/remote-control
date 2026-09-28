// Generated from lucide-react 1.43.0 (ISC, © Lucide Icons and Contributors; the
// icons lucide derived from Feather are MIT, © Cole Bemis). The license text is
// LICENSE-lucide.txt beside this file. Regenerate rather than edit: the set is every
// icon web/src imports from lucide-react, with lucide's own node data, so the Mac
// app draws the web's icons and not look-alikes.

/// Every lucide icon the web app draws, named as the web imports it.
public enum LucideIcon: String, CaseIterable, Sendable {
    case alertTriangle = "triangle-alert"
    case arrowDown = "arrow-down"
    case arrowLeft = "arrow-left"
    case arrowUp = "arrow-up"
    case bot = "bot"
    case check = "check"
    case checkSquare = "square-check-big"
    case chevronDown = "chevron-down"
    case chevronLeft = "chevron-left"
    case chevronRight = "chevron-right"
    case chevronUp = "chevron-up"
    case circleAlert = "circle-alert"
    case circleX = "circle-x"
    case clock = "clock"
    case copy = "copy"
    case fileEdit = "file-pen"
    case filePlus = "file-plus"
    case fileText = "file-text"
    case folder = "folder"
    case folderPlus = "folder-plus"
    case gitBranch = "git-branch"
    case globe = "globe"
    case info = "info"
    case laptopMinimal = "laptop-minimal"
    case listChecks = "list-checks"
    case mic = "mic"
    case moreHorizontal = "ellipsis"
    case paperclip = "paperclip"
    case pencil = "pencil"
    case plug = "plug"
    case plus = "plus"
    case refreshCw = "refresh-cw"
    case search = "search"
    case square = "square"
    case terminal = "terminal"
    case wrench = "wrench"
    case x = "x"
    case zap = "zap"
}

extension LucideIcon {
    /// The icon's elements on lucide's 24-unit grid, in lucide's order.
    var nodes: [IconNode] {
        switch self {
        case .alertTriangle:
            [.path("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"),
             .path("M12 9v4"),
             .path("M12 17h.01")]
        case .arrowDown:
            [.path("M12 5v14"),
             .path("m19 12-7 7-7-7")]
        case .arrowLeft:
            [.path("m12 19-7-7 7-7"),
             .path("M19 12H5")]
        case .arrowUp:
            [.path("m5 12 7-7 7 7"),
             .path("M12 19V5")]
        case .bot:
            [.path("M12 8V4H8"),
             .rect(x: 4, y: 8, width: 16, height: 12, rx: 2, ry: 2),
             .path("M2 14h2"),
             .path("M20 14h2"),
             .path("M15 13v2"),
             .path("M9 13v2")]
        case .check:
            [.path("M20 6 9 17l-5-5")]
        case .checkSquare:
            [.path("M21 10.656V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12.344"),
             .path("m9 11 3 3L22 4")]
        case .chevronDown:
            [.path("m6 9 6 6 6-6")]
        case .chevronLeft:
            [.path("m15 18-6-6 6-6")]
        case .chevronRight:
            [.path("m9 18 6-6-6-6")]
        case .chevronUp:
            [.path("m18 15-6-6-6 6")]
        case .circleAlert:
            [.circle(cx: 12, cy: 12, r: 10),
             .line(x1: 12, y1: 8, x2: 12, y2: 12),
             .line(x1: 12, y1: 16, x2: 12.01, y2: 16)]
        case .circleX:
            [.circle(cx: 12, cy: 12, r: 10),
             .path("m15 9-6 6"),
             .path("m9 9 6 6")]
        case .clock:
            [.circle(cx: 12, cy: 12, r: 10),
             .path("M12 6v6l4 2")]
        case .copy:
            [.rect(x: 8, y: 8, width: 14, height: 14, rx: 2, ry: 2),
             .path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")]
        case .fileEdit:
            [.path("M12.659 22H18a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v9.34"),
             .path("M14 2v5a1 1 0 0 0 1 1h5"),
             .path("M10.378 12.622a1 1 0 0 1 3 3.003L8.36 20.637a2 2 0 0 1-.854.506l-2.867.837a.5.5 0 0 1-.62-.62l.836-2.869a2 2 0 0 1 .506-.853z")]
        case .filePlus:
            [.path("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"),
             .path("M14 2v5a1 1 0 0 0 1 1h5"),
             .path("M9 15h6"),
             .path("M12 18v-6")]
        case .fileText:
            [.path("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"),
             .path("M14 2v5a1 1 0 0 0 1 1h5"),
             .path("M10 9H8"),
             .path("M16 13H8"),
             .path("M16 17H8")]
        case .folder:
            [.path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")]
        case .folderPlus:
            [.path("M12 10v6"),
             .path("M9 13h6"),
             .path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")]
        case .gitBranch:
            [.path("M15 6a9 9 0 0 0-9 9V3"),
             .circle(cx: 18, cy: 6, r: 3),
             .circle(cx: 6, cy: 18, r: 3)]
        case .globe:
            [.circle(cx: 12, cy: 12, r: 10),
             .path("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"),
             .path("M2 12h20")]
        case .info:
            [.circle(cx: 12, cy: 12, r: 10),
             .path("M12 16v-4"),
             .path("M12 8h.01")]
        case .laptopMinimal:
            [.rect(x: 3, y: 4, width: 18, height: 12, rx: 2, ry: 2),
             .line(x1: 2, y1: 20, x2: 22, y2: 20)]
        case .listChecks:
            [.path("M13 5h8"),
             .path("M13 12h8"),
             .path("M13 19h8"),
             .path("m3 17 2 2 4-4"),
             .path("m3 7 2 2 4-4")]
        case .mic:
            [.path("M12 19v3"),
             .path("M19 10v2a7 7 0 0 1-14 0v-2"),
             .rect(x: 9, y: 2, width: 6, height: 13, rx: 3, ry: 3)]
        case .moreHorizontal:
            [.circle(cx: 12, cy: 12, r: 1),
             .circle(cx: 19, cy: 12, r: 1),
             .circle(cx: 5, cy: 12, r: 1)]
        case .paperclip:
            [.path("m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551")]
        case .pencil:
            [.path("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"),
             .path("m15 5 4 4")]
        case .plug:
            [.path("M12 22v-5"),
             .path("M15 8V2"),
             .path("M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1z"),
             .path("M9 8V2")]
        case .plus:
            [.path("M5 12h14"),
             .path("M12 5v14")]
        case .refreshCw:
            [.path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"),
             .path("M21 3v5h-5"),
             .path("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"),
             .path("M8 16H3v5")]
        case .search:
            [.path("m21 21-4.34-4.34"),
             .circle(cx: 11, cy: 11, r: 8)]
        case .square:
            [.rect(x: 3, y: 3, width: 18, height: 18, rx: 2, ry: 2)]
        case .terminal:
            [.path("M12 19h8"),
             .path("m4 17 6-6-6-6")]
        case .wrench:
            [.path("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z")]
        case .x:
            [.path("M18 6 6 18"),
             .path("m6 6 12 12")]
        case .zap:
            [.path("M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z")]
        }
    }
}
