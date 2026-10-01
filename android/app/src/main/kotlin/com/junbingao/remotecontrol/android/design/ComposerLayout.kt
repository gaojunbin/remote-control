package com.junbingao.remotecontrol.android.design

/**
 * How far the message field is allowed to grow.
 *
 * One rule, shared by every place the app takes a message: the composer and the field an agent's
 * question offers for a written answer. The field grows with the wrapped text within this range;
 * past the top of it the field stops growing and scrolls inside itself, so a dictated paragraph
 * never pushes the conversation off the screen.
 */
object ComposerLayout {
    /** An empty or short draft is a single quiet row. */
    const val minimumLines = 1

    /** Eight lines is where growing stops and scrolling starts. */
    const val maximumLines = 8

    val growth: IntRange = minimumLines..maximumLines

    /**
     * The lines a draft occupies counting only its own line breaks, clamped to the range above.
     * Soft wrapping can only add to this, never subtract, so a draft this call reports as capped
     * is certainly capped on screen.
     */
    fun lines(draft: String): Int = breaks(draft).coerceIn(minimumLines, maximumLines)

    /** Whether the field has stopped growing and the text moves inside it. */
    fun scrolls(draft: String): Boolean = breaks(draft) > maximumLines

    // Swift's `Character.isNewline` covers \n, \r\n (one character), \r, and the Unicode line
    // and paragraph separators, so a CRLF pair counts once here too.
    private fun breaks(draft: String): Int {
        var count = 1
        var index = 0
        while (index < draft.length) {
            val character = draft[index]
            if (character == '\r' && draft.getOrNull(index + 1) == '\n') {
                count += 1
                index += 2
                continue
            }
            if (character == '\n' || character == '\r' || character == '\u000B' || character == '\u000C' ||
                character == '\u0085' || character == '\u2028' || character == '\u2029'
            ) {
                count += 1
            }
            index += 1
        }
        return count
    }
}
