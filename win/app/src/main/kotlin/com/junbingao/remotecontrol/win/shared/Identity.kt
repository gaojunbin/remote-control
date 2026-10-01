package com.junbingao.remotecontrol.win.shared

/**
 * `web/src/lib/identity.ts`: who is signed in and where, in the two forms the app draws them — the
 * initials in a circle and the gateway's host. Both are pure, and the top bar and the Settings
 * header read the same rule, so the two never disagree (`docs/DESIGN.md` § "The Settings screen").
 */
object Identity {
    /**
     * One or two characters for the circle: the first letter of each of the first two parts, or
     * the first two letters of a single part. A script without letter case — Chinese above all —
     * reads as one character rather than two. The parts are what the gateway lets a username be
     * split by: `.`, `_`, `-` and whitespace.
     */
    fun initials(username: String): String {
        val parts = username.split { it == '.' || it == '_' || it == '-' || it.isWhitespace() }
        val first = parts.firstOrNull() ?: return "?"
        if (parts.size > 1) {
            val second = parts[1].firstGrapheme()
            val head = first.firstGrapheme()
            if (second != null && head != null) return (head + second).uppercase()
        }
        val scalar = first.codePointAt(0)
        if (scalar >= 0x80 || !Character.isLetterOrDigit(scalar)) return first.firstGrapheme() ?: first
        return first.take(2).uppercase()
    }

    /** The gateway origin without its scheme and without any path: `host[:port]`. */
    fun gatewayHost(origin: String): String {
        var text = origin
        Regex("^[A-Za-z][A-Za-z0-9+.-]*://").find(text)?.let { text = text.removeRange(it.range) }
        val slash = text.indexOf('/')
        return if (slash >= 0) text.substring(0, slash) else text
    }

    /** Swift's `split`, which drops the empty parts between separators. */
    private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                if (current.isNotEmpty()) parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** The first character as a person reads it: a whole grapheme, so an emoji or a pair stays whole. */
    private fun String.firstGrapheme(): String? {
        if (isEmpty()) return null
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(this)
        return substring(0, breaks.next())
    }
}
