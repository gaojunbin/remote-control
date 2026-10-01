package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.ui.input.key.Key

/** The keys the composer's field answers itself. Everything else is the text field's. */
enum class ComposerKey {
    up, down, escape, tab, enter;

    companion object {
        /** Return and the keypad's Enter are both Enter, as they are to a browser. */
        operator fun invoke(key: Key): ComposerKey? = when (key) {
            Key.DirectionUp -> up
            Key.DirectionDown -> down
            Key.Escape -> escape
            Key.Tab -> tab
            Key.Enter, Key.NumPadEnter -> enter
            else -> null
        }
    }
}

/** What one key press does in the field. */
sealed interface ComposerKeyAction {
    /** Not the composer's: the text field does what it always does with it. */
    data object Pass : ComposerKeyAction

    /** A27: the panel's highlight moves to this row. */
    data class Highlight(val index: Int) : ComposerKeyAction

    /** A27: Esc puts the panel away until the draft changes again. */
    data object DismissPanel : ComposerKeyAction

    /** A27: the highlighted row goes into the field. */
    data object TakeRow : ComposerKeyAction

    /** The primary slot's action — Send, Queue, Answer — if the slot holds it. */
    data object Submit : ComposerKeyAction
}

/**
 * `onKeyDown` and `onCommandKey` in `Composer.tsx`.
 *
 * Enter sends and Shift+Enter breaks the line. **The Enter that confirms an input method's
 * composition never sends** (`docs/DESIGN.md` § "The composer"): under a Chinese or Japanese input
 * method the letters are composed first, and Enter only puts them in the field. The web has to infer
 * that from browser events (`useImeGuard.ts`); the Mac's text view says so directly, and Compose's
 * does too — the field's value holds a composition — so the press is the input method's and nothing
 * here looks at it.
 */
object ComposerKeys {
    /**
     * The panel as the key finds it: how many rows it shows, which one is highlighted, and whether
     * taking that row would change the field.
     */
    data class Panel(val rows: Int, val highlight: Int, val takingChangesField: Boolean)

    fun action(key: ComposerKey, shift: Boolean, hasMarkedText: Boolean, panel: Panel?): ComposerKeyAction {
        if (hasMarkedText) return ComposerKeyAction.Pass
        if (panel != null && panel.rows > 0) {
            val at = minOf(panel.highlight, panel.rows - 1)
            return when (key) {
                ComposerKey.down -> ComposerKeyAction.Highlight((at + 1) % panel.rows)
                ComposerKey.up -> ComposerKeyAction.Highlight((at + panel.rows - 1) % panel.rows)
                ComposerKey.escape -> ComposerKeyAction.DismissPanel
                ComposerKey.tab -> if (shift) ComposerKeyAction.Pass else ComposerKeyAction.TakeRow
                // The terminal's second Enter: a row already in the field runs.
                ComposerKey.enter -> when {
                    shift -> ComposerKeyAction.Pass
                    panel.takingChangesField -> ComposerKeyAction.TakeRow
                    else -> ComposerKeyAction.Submit
                }
            }
        }
        return if (key == ComposerKey.enter && !shift) ComposerKeyAction.Submit else ComposerKeyAction.Pass
    }
}
