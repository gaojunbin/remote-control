package com.junbingao.remotecontrol.win.devices

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.SystemFace
import org.jetbrains.skia.Font

/** Two CSS text rules the lists lean on that Compose has no word for. */
object TextMeasure {
    /**
     * `ch`: the advance of "0" in the system face at `size`, which the web's `max-width: 46ch`
     * measures a paragraph in — with the face's own tracking, as the Mac's text system sets it.
     */
    fun ch(size: Float): Dp {
        val face = SystemFace.face(size, FontWeight.Normal, mono = false)
        return (Font(face.typeface, size).measureTextWidth("0") + face.tracking).dp
    }

    /**
     * `word-break: break-all`: a one-liner may break between any two letters or digits, as a long
     * URL in a terminal would, rather than only where a word ends; punctuation keeps its own rules,
     * so `--` stays whole. The text drawn carries a zero-width space between each such pair; the
     * text copied is the original.
     */
    fun breakAll(text: String): String {
        val result = StringBuilder()
        var previous: Int? = null
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (previous != null && isLetterOrDigit(previous) && isLetterOrDigit(point)) result.append('​')
            result.appendCodePoint(point)
            previous = point
            index += Character.charCount(point)
        }
        return result.toString()
    }

    /** Swift's `isLetter || isNumber`: a number is any of Unicode's three kinds, not only a digit. */
    private fun isLetterOrDigit(point: Int): Boolean = Character.isLetter(point) || when (Character.getType(point).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}
