package com.junbingao.remotecontrol.android.design

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How far a `GrowingTextField` is scrolled, for a UI test to read.
 *
 * A test cannot ask a text field where it is scrolled to, so behind `--field-scroll-probe` the
 * field carries one more element, `<identifier>.scroll`, whose text is "<offset>/<end>" in whole
 * points — how far the text has moved inside the field, and how far it could move. Equal numbers
 * mean the last line is the one in view. Only a debug build honours the argument, which the
 * launch options switch on here.
 */
object FieldScrollProbe {
    var isOn: Boolean = false
        internal set

    /** Called once at launch with what the launch arguments asked for. */
    fun enable(on: Boolean) {
        isOn = on
    }

    fun report(offset: Float, end: Float): String = "${offset.roundToInt()}/${max(0f, end).roundToInt()}"
}
