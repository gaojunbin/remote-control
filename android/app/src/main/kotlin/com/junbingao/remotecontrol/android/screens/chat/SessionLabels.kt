package com.junbingao.remotecontrol.android.screens.chat

import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState

// The two words the iPhone's `Controls.swift` keeps beside `StatusLabel`, which the chat header
// reads: the state as the header says it, and the attached terminal's own name for it.

/** The words shown beside the dot. */
val SessionState.label: String
    get() = when (this) {
        SessionState.starting -> L10n.string("starting")
        SessionState.running -> L10n.string("running")
        SessionState.needsApproval -> L10n.string("needs approval")
        SessionState.needsInput -> L10n.string("needs input")
        SessionState.error -> L10n.string("error")
        SessionState.stopped -> L10n.string("stopped")
        SessionState.readonly -> L10n.string("terminal")
        SessionState.idle -> L10n.string("done")
        else -> rawValue
    }

/**
 * What the chat header says beside the dot. Amendment A10: an attached session names the terminal
 * that owns it.
 */
val Session.statusLabel: String
    get() = if (isAttached) L10n.string("terminal · attached") else state.label
