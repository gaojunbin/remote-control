package com.junbingao.remotecontrol.core.state

/**
 * The one or two characters in the circle at the top of Settings.
 *
 * A username is not a person's name, so there is nothing clever to do with it: two words give a
 * letter each (`j.gao` → `JG`), one word gives its first two letters (`admin` → `AD`), and a script
 * whose characters are words of their own gives one (`李雷` → `李`).
 */
object Initials {
    private val separators = setOf(".", "-", "_")

    fun of(username: String): String {
        val words = words(username)
        val first = words.firstOrNull() ?: return ""
        val head = first.first()
        if (words.size > 1) return (head + words[1].first()).uppercase()
        if (isWordOnItsOwn(head)) return head
        return first.take(2).joinToString("").uppercase()
    }

    /** The username's words, each a run of characters between separators; none is ever empty. */
    private fun words(username: String): List<List<String>> {
        val words = mutableListOf<List<String>>()
        var word = mutableListOf<String>()
        for (character in username.characters()) {
            if (character in separators || isWhitespace(character)) {
                if (word.isNotEmpty()) words.add(word)
                word = mutableListOf()
            } else {
                word.add(character)
            }
        }
        if (word.isNotEmpty()) words.add(word)
        return words
    }

    /**
     * A character that is already a word: CJK ideographs, kana and Hangul. Two of them in a circle
     * read as a name cut in half rather than as initials.
     */
    private fun isWordOnItsOwn(character: String): Boolean {
        if (character.isEmpty()) return false
        return when (character.codePointAt(0)) {
            in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF, in 0xF900..0xFAFF -> true
            else -> false
        }
    }
}
