package com.junbingao.remotecontrol.android.icons

/**
 * Every SF Symbol the iPhone app draws, named in the iPhone's own words — `Sf.gearshape` for
 * `Image(systemName: "gearshape")` — each drawn as the nearest lucide glyph, the set the web and
 * Mac apps already carry (`docs/DESIGN.md` § "The Android app"). The `.fill` forms SF Symbols
 * supplies are built from the same glyphs, filled, with the inner marks cut out of them.
 *
 * A symbol the iPhone chooses by name at run time (a row action, an empty state) is found with
 * [named], which answers the iPhone's spelling.
 */
object Sf {
    val arrowClockwise = SfSymbol("arrow.clockwise", listOf(stroke(Lucide.rotateCw)))
    val arrowDown = SfSymbol("arrow.down", listOf(stroke(Lucide.arrowDown)))
    val arrowLeftAndRight = SfSymbol("arrow.left.and.right", listOf(stroke(Lucide.moveHorizontal)))
    val arrowUp = SfSymbol("arrow.up", listOf(stroke(Lucide.arrowUp)))
    val arrowUpCircle = SfSymbol("arrow.up.circle", listOf(stroke(Lucide.circleArrowUp)))
    val arrowUpLeft = SfSymbol("arrow.up.left", listOf(stroke(Glyphs.arrowUpLeft)))
    val arrowUpLeftAndArrowDownRight =
        SfSymbol("arrow.up.left.and.arrow.down.right", listOf(stroke(Lucide.maximize2)))
    val bolt = SfSymbol("bolt", listOf(stroke(Lucide.zap)))
    val boltFill = SfSymbol("bolt.fill", listOf(fillAndStroke(Lucide.zap)))
    val bubbleLeftAndExclamationmarkBubbleRight =
        SfSymbol("bubble.left.and.exclamationmark.bubble.right", listOf(stroke(Lucide.messageSquareWarning)))
    val bubbleLeftAndTextBubbleRight =
        SfSymbol("bubble.left.and.text.bubble.right", listOf(stroke(Lucide.messagesSquare)))

    /** The Sessions tab: the tab bar draws the filled form, as the iPhone's does. */
    val bubbleLeftAndTextBubbleRightFill = SfSymbol(
        "bubble.left.and.text.bubble.right.fill",
        listOf(
            fill(Glyphs.backBubble),
            cutStroke(Glyphs.frontBubble, weight = 1.19f),
            fill(Glyphs.frontBubble),
            cutStroke(Glyphs.frontBubbleText, weight = 0.85f),
        ),
        scale = 1.3f,
    )
    val camera = SfSymbol("camera", listOf(stroke(Lucide.camera)))
    val chartXyaxisLine = SfSymbol("chart.xyaxis.line", listOf(stroke(Lucide.chartLine)))
    val checklist = SfSymbol("checklist", listOf(stroke(Lucide.listChecks)))
    val checkmark = SfSymbol("checkmark", listOf(stroke(Glyphs.check, weight = 1.2f)))
    val checkmarkCircleFill = SfSymbol(
        "checkmark.circle.fill",
        listOf(fill(Lucide.circleCheck.take(1)), cutStroke(Lucide.circleCheck.drop(1), weight = 1.3f)),
    )
    val checkmarkSquareFill = SfSymbol(
        "checkmark.square.fill",
        listOf(fillAndStroke(Lucide.squareCheck.take(1)), cutStroke(Lucide.squareCheck.drop(1), weight = 1.3f)),
    )

    /** The navigation bar's back button, which iOS draws larger and heavier than a text-sized chevron. */
    val chevronBackward = SfSymbol("chevron.backward", listOf(stroke(Lucide.chevronLeft)), scale = 1.7f)
    /**
     * The chevrons, a third larger than lucide's at the same size and in frames that hug them, as
     * SF's do: 6.7 by 11.3 points at the end of a Settings row, 9.7 by 5.7 on a device's group.
     */
    val chevronDown = SfSymbol("chevron.down", listOf(stroke(Lucide.chevronDown)), scale = 1.33f, aspect = 0.66f)
    val chevronLeft = SfSymbol("chevron.left", listOf(stroke(Lucide.chevronLeft)))
    val chevronLeftForwardslashChevronRight =
        SfSymbol("chevron.left.forwardslash.chevron.right", listOf(stroke(Lucide.codeXml)))
    val chevronRight = SfSymbol("chevron.right", listOf(stroke(Lucide.chevronRight)), scale = 1.3f, aspect = 0.4f)
    val chevronUp = SfSymbol("chevron.up", listOf(stroke(Lucide.chevronUp)), scale = 1.33f, aspect = 0.66f)
    val chevronUpChevronDown = SfSymbol("chevron.up.chevron.down", listOf(stroke(Lucide.chevronsUpDown)))
    val circle = SfSymbol("circle", listOf(stroke(Lucide.circle)))
    val circleLefthalfFilled =
        SfSymbol("circle.lefthalf.filled", listOf(stroke(Lucide.circle), fill(Glyphs.leftHalf)))
    val clock = SfSymbol("clock", listOf(stroke(Lucide.clock)))
    val desktopcomputer = SfSymbol("desktopcomputer", listOf(stroke(Lucide.monitor)), scale = 1.15f)

    /** The Devices tab: a black body round a screen of the ink at a quarter, as the iPhone's is. */
    val desktopcomputerFill = SfSymbol(
        "desktopcomputer.fill",
        listOf(fill(Glyphs.desktopBody), cutFill(Glyphs.desktopScreen), fill(Glyphs.desktopScreen, alpha = 0.25f)),
        scale = 1.15f,
    )
    val docOnDoc = SfSymbol("doc.on.doc", listOf(stroke(Lucide.copy)))
    val docText = SfSymbol("doc.text", listOf(stroke(Lucide.fileText)))
    val folder = SfSymbol("folder", listOf(stroke(Lucide.folder)))
    val folderBadgePlus = SfSymbol(
        "folder.badge.plus",
        listOf(stroke(Glyphs.badgedFolder, weight = 0.77f), fill(Glyphs.folderBadge), cutStroke(Glyphs.folderBadgeCross, weight = 0.58f)),
        scale = 1.54f,
    )
    val gaugeWithDotsNeedle33percent = SfSymbol(
        "gauge.with.dots.needle.33percent",
        listOf(stroke(Glyphs.gaugeRing, weight = 0.83f), fill(Glyphs.gaugeDots), fill(Glyphs.gaugeNeedle)),
        scale = 1.125f,
    )
    val gearshape = SfSymbol("gearshape", listOf(stroke(Lucide.settings)), scale = 1.07f)

    /** The Settings tab: SF's eight-toothed gear, filled, its centre cut out. */
    val gearshapeFill = SfSymbol(
        "gearshape.fill",
        listOf(fillAndStroke(Glyphs.gear, weight = 0.85f), cutFill(Glyphs.gearHole)),
        scale = 1.07f,
    )
    val handRaised = SfSymbol("hand.raised", listOf(stroke(Lucide.hand)))
    val key = SfSymbol("key", listOf(stroke(Glyphs.keyOutline), stroke(Glyphs.keyRing)), scale = 1.24f)
    val keyFill = SfSymbol("key.fill", listOf(fill(Glyphs.keyOutline), cutFill(Glyphs.keyHole)), scale = 1.24f)
    val largecircleFillCircle =
        SfSymbol("largecircle.fill.circle", listOf(stroke(Lucide.circle), fill(Glyphs.radioDot)))
    val line3HorizontalDecrease =
        SfSymbol("line.3.horizontal.decrease", listOf(stroke(Glyphs.decreasingLines, weight = 0.815f)), scale = 1.15f)
    val lockOpen = SfSymbol("lock.open", listOf(stroke(Lucide.lockOpen)))
    val magnifyingglass = SfSymbol("magnifyingglass", listOf(stroke(Lucide.search)))
    val mic = SfSymbol("mic", listOf(stroke(Lucide.mic)))
    val noteText = SfSymbol("note.text", listOf(stroke(Lucide.notepadText)))
    val paperclip = SfSymbol("paperclip", listOf(stroke(Lucide.paperclip)))
    val pauseCircle = SfSymbol("pause.circle", listOf(stroke(Lucide.circlePause)), scale = 0.95f)
    val pauseCircleFill = SfSymbol("pause.circle.fill", listOf(fill(Lucide.circlePause.take(1)), cutFill(Glyphs.pauseBars)), scale = 1.125f)
    val pencil = SfSymbol("pencil", listOf(stroke(Lucide.pencil)))
    val photo = SfSymbol("photo", listOf(stroke(Glyphs.photoFrame, weight = 0.91f), fillAndStroke(Glyphs.photoScene)), scale = 1.05f)
    val playCircle = SfSymbol("play.circle", listOf(stroke(Lucide.circlePlay)), scale = 0.95f)
    val playCircleFill = SfSymbol("play.circle.fill", listOf(fill(Lucide.circlePlay.drop(1)), cutFill(Lucide.circlePlay.take(1))), scale = 1.125f)
    val plus = SfSymbol("plus", listOf(stroke(Lucide.plus)), scale = 1.07f)

    /** The app mark's symbol: the app icon's three nodes and the bars that join them. */
    val point3ConnectedTrianglepathDotted = SfSymbol(
        "point.3.connected.trianglepath.dotted",
        listOf(stroke(Glyphs.markRings, weight = 0.943f), fill(Glyphs.markDots)),
    )
    val qrcodeViewfinder = SfSymbol("qrcode.viewfinder", listOf(stroke(Lucide.scanQrCode)))
    val questionmarkCircle = SfSymbol("questionmark.circle", listOf(stroke(Lucide.circleQuestionMark)))
    val shield = SfSymbol("shield", listOf(stroke(Lucide.shield)), scale = 0.97f)
    val shippingbox = SfSymbol("shippingbox", listOf(stroke(Lucide.`package`)))
    val square = SfSymbol("square", listOf(stroke(Lucide.square)))
    val squareAndArrowUp = SfSymbol("square.and.arrow.up", listOf(stroke(Lucide.share)))
    val stopCircle = SfSymbol(
        "stop.circle",
        listOf(stroke(Lucide.circleStop.take(1)), fillAndStroke(Lucide.circleStop.drop(1))),
        scale = 0.95f,
    )
    val textLineFirstAndArrowtriangleForward =
        SfSymbol("text.line.first.and.arrowtriangle.forward", listOf(stroke(Lucide.listStart)))
    val translate = SfSymbol(
        "translate",
        listOf(
            stroke(Glyphs.translateBack, weight = 0.66f),
            cutStroke(Glyphs.translateFront, weight = 0.45f),
            fill(Glyphs.translateFront),
            cutStroke(Glyphs.translateCharacter, weight = 0.55f),
        ),
        scale = 1.43f,
    )
    val trash = SfSymbol("trash", listOf(stroke(Lucide.trash)))
    val trashFill = SfSymbol(
        "trash.fill",
        listOf(fill(Glyphs.trashCan), stroke(Glyphs.trashLid, weight = 0.67f), cutStroke(Glyphs.trashSlits, weight = 0.67f)),
        scale = 1.24f,
    )
    val xmark = SfSymbol("xmark", listOf(stroke(Lucide.x)))
    val xmarkCircle = SfSymbol("xmark.circle", listOf(stroke(Lucide.circleX)))
    val xmarkCircleFill = SfSymbol(
        "xmark.circle.fill",
        listOf(fill(Lucide.circleX.take(1)), cutStroke(Lucide.circleX.drop(1), weight = 1.3f)),
    )

    /** Every symbol, for [named] and for the checks that hold the table to the iPhone's list. */
    val all: List<SfSymbol> = listOf(
        arrowClockwise, arrowDown, arrowLeftAndRight, arrowUp, arrowUpCircle, arrowUpLeft,
        arrowUpLeftAndArrowDownRight, bolt, boltFill, bubbleLeftAndExclamationmarkBubbleRight,
        bubbleLeftAndTextBubbleRight, bubbleLeftAndTextBubbleRightFill, camera, chartXyaxisLine,
        checklist, checkmark, checkmarkCircleFill, checkmarkSquareFill, chevronBackward, chevronDown,
        chevronLeft, chevronLeftForwardslashChevronRight, chevronRight, chevronUp, chevronUpChevronDown,
        circle, circleLefthalfFilled, clock, desktopcomputer, desktopcomputerFill, docOnDoc, docText,
        folder, folderBadgePlus, gaugeWithDotsNeedle33percent, gearshape, gearshapeFill, handRaised,
        key, keyFill, largecircleFillCircle, line3HorizontalDecrease, lockOpen, magnifyingglass, mic,
        noteText, paperclip, pauseCircle, pauseCircleFill, pencil, photo, playCircle, playCircleFill,
        plus, point3ConnectedTrianglepathDotted, qrcodeViewfinder, questionmarkCircle, shield,
        shippingbox, square, squareAndArrowUp, stopCircle, textLineFirstAndArrowtriangleForward,
        translate, trash, trashFill, xmark, xmarkCircle, xmarkCircleFill,
    )

    private val byName: Map<String, SfSymbol> = all.associateBy { it.name }

    /** The symbol the iPhone calls [name], or null for one this table does not draw. */
    fun named(name: String): SfSymbol? = byName[name]

    /**
     * The form SwiftUI draws in place of [symbol] where it fills symbols itself — a swipe button —
     * which is its `.fill` variant where SF Symbols has one, and the symbol itself elsewhere.
     */
    fun filled(symbol: SfSymbol): SfSymbol = byName["${symbol.name}.fill"] ?: symbol
}
