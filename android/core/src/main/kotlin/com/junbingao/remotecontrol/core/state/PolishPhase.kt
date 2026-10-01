package com.junbingao.remotecontrol.core.state

// From RCCore's `ChatStore.swift`, split for the store's length.

/**
 * Amendment A29: where the polish of the words just dictated has got to.
 *
 * The words themselves are in the field the instant dictation ends, whatever this says; the phase is
 * only about the request that may replace them.
 */
sealed interface PolishPhase {
    data object Idle : PolishPhase
    data object Polishing : PolishPhase

    /** The answer is in the field. The span and the text that went into it are kept so Undo can put the dictated words back. */
    data class Polished(val span: DictationSpan, val text: String) : PolishPhase
    data object Failed : PolishPhase
}
