package com.junbingao.remotecontrol.core

import kotlin.coroutines.cancellation.CancellationException

// The parts of Foundation's text handling RCCore leans on, spelled out, so the Kotlin core trims,
// classifies and parses exactly the characters the Swift one does rather than Java's neighbours.

/** `CharacterSet.whitespaces`: Unicode Zs, U+0009 and U+200B, as Foundation defines it. */
internal fun isFoundationWhitespace(codePoint: Int): Boolean =
    codePoint == 0x09 || codePoint == 0x20 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200B || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000

/** `CharacterSet.whitespacesAndNewlines`: the set above plus U+000A–U+000D, U+0085, U+2028, U+2029. */
internal fun isFoundationWhitespaceOrNewline(codePoint: Int): Boolean =
    isFoundationWhitespace(codePoint) || codePoint in 0x0A..0x0D || codePoint == 0x85 ||
        codePoint == 0x2028 || codePoint == 0x2029

/** `Character.isWhitespace`: the Unicode White_Space property. */
internal fun isSwiftWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 ||
        codePoint == 0x1680 || codePoint in 0x2000..0x200A || codePoint == 0x2028 ||
        codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000

/**
 * `Character.isNumber`: any numeric type, which is Unicode's N categories and the Han ideographs
 * Unihan gives a numeric value (一, 十, 萬 …) — Java's categories alone miss the second half.
 */
internal fun isSwiftNumber(codePoint: Int): Boolean {
    when (Character.getType(codePoint)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> return true
    }
    return codePoint in hanNumerals
}

/** `CharacterSet.alphanumerics`: Unicode L*, M* and N*. */
internal fun isFoundationAlphanumeric(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.NON_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

private val hanNumerals: Set<Int> = intArrayOf(
    0x3405, 0x3483, 0x382A, 0x3B4D, 0x4E00, 0x4E03, 0x4E07, 0x4E09, 0x4E24, 0x4E5D, 0x4E8C, 0x4E94,
    0x4E96, 0x4EAC, 0x4EBF, 0x4EC0, 0x4EDF, 0x4EE8, 0x4F0D, 0x4F70, 0x4FE9, 0x5006, 0x5104, 0x5146,
    0x5169, 0x516B, 0x516D, 0x5341, 0x5343, 0x5344, 0x5345, 0x534C, 0x53C1, 0x53C2, 0x53C3, 0x53C4,
    0x56DB, 0x58F1, 0x58F9, 0x5E7A, 0x5EFE, 0x5EFF, 0x5F0C, 0x5F0D, 0x5F0E, 0x5F10, 0x62D0, 0x62FE,
    0x634C, 0x67D2, 0x6D1E, 0x6F06, 0x7396, 0x767E, 0x7695, 0x79ED, 0x8086, 0x842C, 0x8CAE, 0x8CB3,
    0x8D30, 0x920E, 0x94A9, 0x9621, 0x9646, 0x964C, 0x9678, 0x96F6, 0xF96B, 0xF973, 0xF978, 0xF9B2,
    0xF9D1, 0xF9D3, 0xF9FD, 0x12038, 0x12039, 0x12079, 0x12226, 0x1222B, 0x1230B, 0x1230D, 0x12399,
    0x20001, 0x20064, 0x200E2, 0x20121, 0x2092A, 0x20983, 0x2098C, 0x2099C, 0x20AEA, 0x20AFD, 0x20B19,
    0x22390, 0x22998, 0x23B1B, 0x2626D, 0x2F890,
).toSet()

private inline fun String.trimmingCodePoints(trim: (Int) -> Boolean): String {
    var start = 0
    var end = length
    while (start < end) {
        val codePoint = codePointAt(start)
        if (!trim(codePoint)) break
        start += Character.charCount(codePoint)
    }
    while (end > start) {
        val codePoint = codePointBefore(end)
        if (!trim(codePoint)) break
        end -= Character.charCount(codePoint)
    }
    return substring(start, end)
}

/** `trimmingCharacters(in: .whitespaces)`. */
internal fun String.trimmingWhitespaces(): String = trimmingCodePoints(::isFoundationWhitespace)

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimmingWhitespacesAndNewlines(): String = trimmingCodePoints(::isFoundationWhitespaceOrNewline)

/** The string's Unicode scalars, which is what the Swift parsers index where they index characters. */
internal fun String.scalars(): IntArray = codePoints().toArray()

/** A run of scalars back into a string. */
internal fun IntArray.text(from: Int = 0, to: Int = size): String = String(this, from, to - from)

/**
 * `Int(_: String)`: an optional sign and ASCII digits, nothing else — no surrounding space and
 * none of the other scripts' digits Kotlin's own parser accepts.
 */
internal fun String.swiftInt(): Int? {
    if (isEmpty()) return null
    val digits = if (this[0] == '+' || this[0] == '-') substring(1) else this
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return toIntOrNull()
}

/** `Double.rounded()`: to the nearest, a tie away from zero. */
internal fun Double.swiftRounded(): Long = if (this < 0) -Math.round(-this) else Math.round(this)

/**
 * `try?`: the value, or null where the block threw. Cancellation is never swallowed, because a
 * coroutine that is told to stop has to stop.
 */
internal inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
