package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.abs

/**
 * The field's own history, as the Mac's text view keeps it (its own `UndoManager`), so a write
 * that is not a keystroke — a dictation, a polished answer, a command row, an edit coming in — can
 * forget it without touching anything else: undo reaches back to the words the person typed since,
 * never past them into a draft that was replaced.
 *
 * Typing groups as a text view groups it: one character after another at the caret, in the same
 * direction and without a pause, is one step back; a paste, a cut or a new run is a step of its
 * own, and an input method's composition is one step with the words it commits.
 */
class ComposerUndo(private val now: () -> Long = System::currentTimeMillis) {
    private enum class Kind { insert, delete }

    private data class Run(val kind: Kind, val caret: Int, val at: Long)

    private val undos = ArrayDeque<TextFieldValue>()
    private val redos = ArrayDeque<TextFieldValue>()
    private var run: Run? = null

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** The person replaced `before` with `after`. */
    fun record(before: TextFieldValue, after: TextFieldValue) {
        redos.clear()
        // A composition still being typed is part of the step that began it.
        if (before.composition != null) return
        val kind = if (after.text.length > before.text.length) Kind.insert else Kind.delete
        val single = abs(after.text.length - before.text.length) == 1 && before.selection.collapsed
        val time = now()
        val last = run
        if (single && last != null && last.kind == kind && last.caret == before.selection.start && time - last.at < pause) {
            run = last.copy(caret = after.selection.start, at = time)
            return
        }
        undos.addLast(before.copy(composition = null))
        while (undos.size > limit) undos.removeFirst()
        run = if (single) Run(kind, after.selection.start, time) else null
    }

    /** The words before the last step, or null when there is none. */
    fun undo(current: TextFieldValue): TextFieldValue? {
        val previous = undos.removeLastOrNull() ?: return null
        redos.addLast(current)
        run = null
        return previous
    }

    /** The step undone last, again. */
    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = redos.removeLastOrNull() ?: return null
        undos.addLast(current)
        run = null
        return next
    }

    fun clear() {
        undos.clear()
        redos.clear()
        run = null
    }

    private companion object {
        /** A pause this long ends a run of typing. */
        const val pause = 2_000L
        const val limit = 100
    }
}
