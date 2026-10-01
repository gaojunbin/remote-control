package com.junbingao.remotecontrol.core.state

/**
 * What the dot colours mean, said once on the Sessions screen and nowhere else (`docs/DESIGN.md`
 * § "A legend, once, and quiet").
 *
 * Four entries, not five: the pulsing amber of a question still to answer and the solid amber of a
 * turn already finished are one colour to the eye, so one word covers both — there is something
 * here for you. The amber entry is drawn from `live` rather than `waiting` for the same reason the
 * legend carries no title: a key to the colours has no state of its own to announce, and nothing
 * in it should move.
 */
object DotLegend {
    /** One colour and the word for it. */
    data class Entry(val tone: DotTone, val text: String) {
        val id: DotTone get() = tone
    }

    /** The four entries in the order they are read: what is under way, what wants the person, what is not running, and what went wrong. */
    val entries: List<Entry>
        get() = listOf(
            Entry(tone = DotTone.working, text = L10n.string("Working")),
            Entry(tone = DotTone.live, text = L10n.string("For you")),
            Entry(tone = DotTone.off, text = L10n.string("Not running")),
            Entry(tone = DotTone.failed, text = L10n.string("Error")),
        )
}
