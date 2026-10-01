package com.junbingao.remotecontrol.android.icons

/**
 * The few drawings lucide has no glyph for, on its 24-unit grid: the filled forms SF Symbols
 * carries (the tab bar's two bubbles, a radio's dot, a half-filled circle) and the app's own mark.
 * Each is measured from the iPhone's reference screenshots or, for the mark, from the app icon.
 */
internal object Glyphs {
    /**
     * `bubble.left.and.text.bubble.right.fill`: a bubble at the back with its tail down on the
     * left, and a larger one in front with its tail down on the right and two lines of text. The
     * glyph is wider than tall, as the iPhone's is (32 by 26 points in the tab bar).
     */
    val backBubble = listOf(
        "M2.5 2.45H15.85A2.5 2.5 0 0 1 18.35 4.95V14.07A2.5 2.5 0 0 1 15.85 16.57H8.2L4.9 19.9" +
            "L4.3 16.57H2.5A2.5 2.5 0 0 1 0 14.07V4.95A2.5 2.5 0 0 1 2.5 2.45Z",
    )
    val frontBubble = listOf(
        "M10.99 6.69H21.6A2.3 2.3 0 0 1 23.9 8.99V16.2A2.3 2.3 0 0 1 21.6 18.5H20L19.4 21.3" +
            "L15.7 18.5H10.99A2.3 2.3 0 0 1 8.69 16.2V8.99A2.3 2.3 0 0 1 10.99 6.69Z",
    )
    val frontBubbleText = listOf("M12.8 12.56H20.2", "M12.8 15.8H18.6")

    /**
     * `checkmark`, in SF's proportions rather than lucide's flatter tick: a short arm falling to
     * the vertex a third of the way across, and a long, steep arm rising to the top right. Measured
     * on the iPhone 17's picker menus (the chosen language), where it is 10.7 by 10 points.
     */
    val check = listOf("M5.43 13.16L10.19 18.06L18.57 5.94")

    /**
     * `desktopcomputer.fill`, the Devices tab: a black body with a thin bezel round the screen and
     * a deep chin under it, on a short neck and a rounded foot. Measured on the iPhone 17's tab bar
     * at the box the tab bar draws it in (22 points at the symbol's scale of 1.15).
     */
    val desktopBody = listOf(
        "M2.09 2.29H21.91A1.43 1.43 0 0 1 23.34 3.72V16.67A1.43 1.43 0 0 1 21.91 18.1H2.09" +
            "A1.43 1.43 0 0 1 0.66 16.67V3.72A1.43 1.43 0 0 1 2.09 2.29Z",
        "M8.92 17.9H15.08V20.1H8.92Z",
        "M8.92 19.93H15.08A0.84 0.84 0 0 1 15.08 21.61H8.92A0.84 0.84 0 0 1 8.92 19.93Z",
    )
    val desktopScreen = listOf(
        "M2.76 3.83H21.24A0.42 0.42 0 0 1 21.66 4.25V13.49A0.42 0.42 0 0 1 21.24 13.91H2.76" +
            "A0.42 0.42 0 0 1 2.34 13.49V4.25A0.42 0.42 0 0 1 2.76 3.83Z",
    )

    /**
     * `photo`, which lucide draws as lines but SF Symbols as a picture: a framed landscape whose
     * small hill and tall mountain are filled down to the frame, under a filled sun. Measured on
     * the iPhone 17's attach menu at the body size and the symbol's scale of 1.05; the scene is
     * drawn inset by half a stroke, which its own outline then gives back with round corners.
     */
    val photoFrame = listOf(
        "M2.6 3.87H21.4A1.3 1.3 0 0 1 22.7 5.17V18.83A1.3 1.3 0 0 1 21.4 20.13H2.6A1.3 1.3 0 0 1 1.3 18.83" +
            "V5.17A1.3 1.3 0 0 1 2.6 3.87Z",
    )
    val photoScene = listOf(
        "M1.3 17.4L4.86 14.6L7.44 16.8L14.78 12.2L22.7 17.9V20.13H1.3Z",
        "M4.35 9.62a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z",
    )

    /**
     * `line.3.horizontal.decrease`, the filter: three centred lines, each shorter than the one
     * above by the same step, as SF draws them (21.7, 17 and 12.3 points beside the body size on
     * the iPhone 17's navigation bar) rather than lucide's steeper funnel.
     */
    val decreasingLines = listOf("M1.05 6.02H22.95", "M3.58 12H20.42", "M6.12 17.98H17.88")

    /**
     * `arrow.up.left`, Up one level: lucide's arrow drawn out to SF's extent, 15.7 points across
     * beside the body on the iPhone 17's directory picker.
     */
    val arrowUpLeft = listOf("M3.08 20.92V3.08H20.92", "M20.92 20.92L3.08 3.08")

    /**
     * `folder.badge.plus`, New folder: a wide folder whose top right gives way to a filled badge
     * holding a plus, the folder's lines stopping short of it. Measured on the directory picker's
     * bar (29 by 20 points at the body size and the symbol's scale of 1.54).
     */
    val badgedFolder = listOf(
        "M13.6 6.4H7.9L6.2 4.78H2.52A1.6 1.6 0 0 0 0.92 6.38V17.78A1.6 1.6 0 0 0 2.52 19.38H17.64" +
            "A1.6 1.6 0 0 0 19.24 17.78V14.1",
        "M3.2 9H13.4",
    )
    val folderBadge = listOf("M14.78 8.1a4.38 4.38 0 1 0 8.76 0a4.38 4.38 0 1 0 -8.76 0z")
    val folderBadgeCross = listOf("M19.16 5.6V10.6", "M16.66 8.1H21.66")

    /**
     * `gauge.with.dots.needle.33percent`, Show quota: a ring with six dots inside it and a needle
     * from the middle pointing up to the left, where lucide draws an open dial. Measured on the
     * device row's swipe (18 points at the body size and the symbol's scale of 1.125).
     */
    val gaugeRing = listOf("M2.93 12a9.07 9.07 0 1 0 18.14 0a9.07 9.07 0 1 0 -18.14 0z")
    val gaugeDots = listOf(
        "M11.19 6.44a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
        "M15.12 8.07a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
        "M16.75 12a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
        "M15.12 15.93a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
        "M7.26 15.93a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
        "M5.63 12a0.81 0.81 0 1 0 1.62 0a0.81 0.81 0 1 0 -1.62 0z",
    )
    val gaugeNeedle = listOf("M7.76 7.9L13.6 11.7A1.48 1.48 0 1 1 11.5 13.78Z")

    /**
     * `key` and `key.fill`, Reset password: SF's key stands upright — a round bow with a hole near
     * its top, the blade under it with two teeth on the right and a pointed tip — where lucide's
     * lies at an angle. Measured on the Users screen's swipe (11.3 by 21.7 points at the body size
     * and the symbol's scale of 1.24).
     */
    val keyOutline = listOf(
        "M9.71 11.7A5.65 5.65 0 1 1 13.51 11.98V12.16L15.7 15.19L13.51 17.54L15.2 19.72L12 22.92L9.71 20.9Z",
    )
    val keyHole = listOf("M10.49 4.77a1.51 1.51 0 1 0 3.02 0a1.51 1.51 0 1 0 -3.02 0z")
    val keyRing = listOf("M11 4.77a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")

    /** `pause.circle.fill`'s two bars, cut out of the disc: Disable on the Users screen's swipe. */
    val pauseBars = listOf("M8.3 7.74H10.89V16.07H8.3Z", "M13.11 7.74H15.7V16.07H13.11Z")

    /**
     * `translate`, the composer's dictation language: a bubble outlined round an A, and over its
     * corner a filled bubble with 文 cut out of it, kept apart by a thin gap. Measured on the
     * iPhone 17's composer (26.3 by 19 points at the body size and the symbol's scale of 1.43).
     */
    val translateBack = listOf(
        "M4.51 13.39V15.95L7.39 13.39H11.63A1.46 1.46 0 0 0 13.09 11.93V5.82A1.46 1.46 0 0 0 11.63 4.36" +
            "H2.62A1.46 1.46 0 0 0 1.16 5.82V11.93A1.46 1.46 0 0 0 2.62 13.39Z",
        "M5.16 11.06L7.13 6.25L9.22 11.06",
        "M5.99 9.31H8.26",
    )
    val translateFront = listOf(
        "M12.8 7.33H20.81A2.62 2.62 0 0 1 23.43 9.95V14.99A2.62 2.62 0 0 1 20.81 17.61H20.05L19.6 20.23" +
            "L16.3 17.61H12.8A2.62 2.62 0 0 1 10.18 14.99V9.95A2.62 2.62 0 0 1 12.8 7.33Z",
    )
    val translateCharacter = listOf("M16.82 9.3V10.3", "M14.65 11.34H19.1", "M15.3 12.45L18.9 15.15", "M18.35 12.45L14.75 15.15")

    /**
     * `trash.fill`, Delete and Revoke on a swipe: a filled can a little narrower at its foot, under
     * a lid and its handle, with three slits cut out. Measured on the Users screen's swipe (17 by
     * 20 points at the body size and the symbol's scale of 1.24).
     */
    val trashCan = listOf("M5.1 6.2H18.9L18.12 20.38A1.75 1.75 0 0 1 16.37 22H7.63A1.75 1.75 0 0 1 5.88 20.38Z")
    val trashLid = listOf("M4.1 6.2H19.9", "M8.81 6.2V4.18A1.5 1.5 0 0 1 10.31 2.68H13.7A1.5 1.5 0 0 1 15.2 4.18V6.2")
    val trashSlits = listOf("M9.15 9.06V18.6", "M12 9.06V18.6", "M14.86 9.06V18.6")

    /**
     * `gearshape.fill`, the Settings tab: SF's gear has eight teeth, a little narrower at the tip
     * than at the root and rounded off, round a hole, where lucide's has six petals. Measured on
     * the iPhone 17's tab bar (23.8 points across at the bar's 22 points and the symbol's scale of
     * 1.07); its outline is stroked as it is filled, which rounds the corners.
     */
    val gear = listOf(
        "M9.80 5.20L10.55 2.11L13.45 2.11L14.20 5.20A7.15 7.15 0 0 1 15.25 5.63L17.97 3.98L20.02 6.03" +
            "L18.37 8.75A7.15 7.15 0 0 1 18.80 9.80L21.89 10.55L21.89 13.45L18.80 14.20A7.15 7.15 0 0 1 18.37 15.25" +
            "L20.02 17.97L17.97 20.02L15.25 18.37A7.15 7.15 0 0 1 14.20 18.80L13.45 21.89L10.55 21.89L9.80 18.80" +
            "A7.15 7.15 0 0 1 8.75 18.37L6.03 20.02L3.98 17.97L5.63 15.25A7.15 7.15 0 0 1 5.20 14.20L2.11 13.45" +
            "L2.11 10.55L5.20 9.80A7.15 7.15 0 0 1 5.63 8.75L3.98 6.03L6.03 3.98L8.75 5.63A7.15 7.15 0 0 1 9.80 5.20Z",
    )
    val gearHole = listOf("M8.39 12a3.61 3.61 0 1 0 7.22 0a3.61 3.61 0 1 0 -7.22 0z")

    /** `largecircle.fill.circle`'s centre: the chosen radio. */
    val radioDot = listOf("M6.5 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0z")

    /** `circle.lefthalf.filled`'s left half: a to-do in progress. */
    val leftHalf = listOf("M12 2a10 10 0 0 0 0 20z")

    /**
     * `point.3.connected.trianglepath.dotted`, which the iPhone's `AppMark` draws: three ringed
     * nodes, the two above joined by three dots and each joined to the one below by two. Measured
     * on the iPhone 17's sign-in form, where the mark is 52 points and the symbol half of it.
     */
    val markRings = listOf(
        "M1.516 6.555a2.59 2.59 0 1 0 5.18 0a2.59 2.59 0 1 0 -5.18 0z",
        "M17.165 6.555a2.59 2.59 0 1 0 5.18 0a2.59 2.59 0 1 0 -5.18 0z",
        "M9.410 17.980a2.59 2.59 0 1 0 5.18 0a2.59 2.59 0 1 0 -5.18 0z",
    )
    val markDots = listOf(
        "M8.370 6.420a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M10.950 6.420a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M13.680 6.420a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M5.920 11.180a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M7.820 13.360a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M16.130 11.320a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
        "M14.220 13.360a0.91 0.91 0 1 0 1.82 0a0.91 0.91 0 1 0 -1.82 0z",
    )
}
