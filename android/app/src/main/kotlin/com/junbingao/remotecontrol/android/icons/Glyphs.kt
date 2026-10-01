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
