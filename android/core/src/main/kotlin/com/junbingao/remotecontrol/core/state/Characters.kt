package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.isSwiftNumber
import com.junbingao.remotecontrol.core.isSwiftWhitespace
import java.text.BreakIterator
import java.util.Locale

// Swift's `Character` is one extended grapheme cluster, and the few rules here that count or
// classify characters — initials, a command draft, a transcript's shape — count them as Swift
// does rather than by UTF-16 unit or by scalar.

/** The string's characters, as Swift iterates them: one extended grapheme cluster each. */
internal fun String.characters(): List<String> {
    if (isEmpty()) return emptyList()
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundaries.setText(this)
    val characters = ArrayList<String>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        characters.add(substring(start, end))
        start = end
        end = boundaries.next()
    }
    return characters
}

/** `Character.isLetter`: the Alphabetic property of the character's first scalar. */
internal fun isLetter(character: String): Boolean = character.isNotEmpty() && Character.isAlphabetic(character.codePointAt(0))

/** `Character.isNumber`: the first scalar has a numeric type. */
internal fun isNumber(character: String): Boolean = character.isNotEmpty() && isSwiftNumber(character.codePointAt(0))

/** `Character.isWhitespace`: the first scalar is Unicode White_Space. */
internal fun isWhitespace(character: String): Boolean =
    character.isNotEmpty() && isSwiftWhitespace(character.codePointAt(0))
