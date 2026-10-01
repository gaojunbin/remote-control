package com.junbingao.remotecontrol.win.devices

import java.text.Normalizer

/**
 * Names in the order the web's `localeCompare` and the Mac's `localizedCompare` give them — the
 * Unicode root collation both read through ICU: spaces and punctuation before digits, digits
 * before letters, so `mac-studio-office` comes before `macbook-air`; letters by their base letter
 * first, then by their accents, then by case with the lower case first. Java's `Collator` ignores
 * the hyphen and puts those two the other way round.
 */
object NameOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = Key.of(a)
        val right = Key.of(b)
        return compareLists(left.primary, right.primary).takeIf { it != 0 }
            ?: compareLists(left.accents, right.accents).takeIf { it != 0 }
            ?: compareLists(left.cases, right.cases).takeIf { it != 0 }
            ?: a.compareTo(b)
    }

    /** One name's weights at the collation's three levels. */
    private class Key(val primary: List<Long>, val accents: List<Long>, val cases: List<Long>) {
        companion object {
            fun of(name: String): Key {
                val primary = mutableListOf<Long>()
                val accents = mutableListOf<Long>()
                val cases = mutableListOf<Long>()
                val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
                var index = 0
                while (index < decomposed.length) {
                    val point = decomposed.codePointAt(index)
                    index += Character.charCount(point)
                    if (Character.getType(point) == Character.NON_SPACING_MARK.toInt()) {
                        accents += point.toLong()
                        continue
                    }
                    primary += weight(point)
                    accents += 0
                    cases += if (Character.isUpperCase(point)) 1 else 0
                }
                return Key(primary, accents, cases)
            }

            /** The group a character sorts in, then its place there. */
            private fun weight(point: Int): Long {
                val group = when {
                    Character.isWhitespace(point) -> 0L
                    Character.isLetter(point) -> if (point < 0x2E80) 3L else 4L
                    Character.isDigit(point) -> 2L
                    else -> 1L
                }
                val place = when (group) {
                    2L -> Character.digit(point, 10).toLong()
                    3L -> Character.toLowerCase(point).toLong()
                    else -> point.toLong()
                }
                return (group shl 32) or place
            }
        }
    }

    private fun compareLists(a: List<Long>, b: List<Long>): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val order = a[index].compareTo(b[index])
            if (order != 0) return order
        }
        return a.size.compareTo(b.size)
    }
}
