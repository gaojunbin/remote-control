package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange

// From RCCore's `ChatStore.swift`, split for the store's length: what the composer may do, read from
// the session, the agent and the store's own state. Each rule is a pure read of what the store
// publishes, so a screen and a test ask it the same question.

/**
 * Stopping a turn the terminal owns is not ours to do. A channel cannot interrupt a running turn
 * either, so an attached session offers Stop only when the agent lists `interrupt` *and* the device
 * reports that the attachment itself can interrupt.
 */
val ChatStore.canStop: Boolean
    get() {
        if (!isRunning || isReadOnly) return false
        if (!isAttached) return true
        val agent = agent ?: return false
        return agent.supports(AgentCapability.interrupt) && agent.sharedInterrupt
    }

/** Amendment A10: takeover is a `terminal` affordance, and only when the agent advertises the capability. */
val ChatStore.canTakeover: Boolean get() = isReadOnly && agent?.supports(AgentCapability.takeover) == true

/**
 * `docs/DESIGN.md` § "The composer": the status line of a session a terminal holds. The way out is
 * named only where the agent has one — Codex and Grok Build advertise no `takeover`, so nothing on
 * their terminal-held sessions invites a tap that would be refused.
 *
 * The clause belongs to this line alone. The disabled field says the short sentence whatever the
 * agent is, because a placeholder that repeats the line above it word for word, and then truncates,
 * says less than half of it would.
 */
val ChatStore.terminalControlNotice: String
    get() = L10n.string(if (canTakeover) "Controlled by the terminal · take over to send" else "Controlled by the terminal")

/**
 * A terminal session takes no input from here at all. Amendment A10: a relay cannot hand bytes to a
 * live CLI either. Amendment A11: an attachment that does carry them says so with
 * `shared_attachments`.
 */
val ChatStore.allowsAttachments: Boolean
    get() {
        if (isReadOnly) return false
        if (!isAttached) return true
        return agent?.sharedAttachments == true
    }

/**
 * Amendment A40: whether one setting of this session is the app's to change. A session a terminal
 * holds outright gives up all four; a session this app drives keeps all four; an attached one keeps
 * exactly what its agent says `session.set` reaches (A11, A17).
 */
fun ChatStore.allowsSettingsChanges(setting: SharedSetting): Boolean {
    if (isReadOnly) return false
    if (!isAttached) return true
    return agent?.shares(setting) == true
}

/**
 * Amendment A21: model, effort and speed are one control, so the card is live only where every
 * setting it carries is the app's to change. A setting the agent does not have — Claude names no
 * speed tier — is not on the card and does not decide anything.
 */
val ChatStore.allowsModelCardChanges: Boolean
    get() {
        val carried = mutableListOf(SharedSetting.model)
        if (agent?.efforts?.isEmpty() == false) carried.add(SharedSetting.effort)
        if (agent?.speeds?.isEmpty() == false) carried.add(SharedSetting.speed)
        return carried.all { allowsSettingsChanges(it) }
    }

/**
 * Amendment A21: where one tap on the speed control moves this session — standard, then each tier
 * the agent lists, then standard again. Null when the agent lists no tier, which is when the control
 * is not drawn at all.
 */
val ChatStore.nextSpeed: SpeedChange?
    get() {
        val tiers = agent?.speeds?.map { it.id } ?: emptyList()
        if (tiers.isEmpty()) return null
        val index = session.speed?.let { tiers.indexOf(it) } ?: -1
        if (index < 0) return SpeedChange(id = tiers.first())
        return if (index + 1 < tiers.size) SpeedChange.Tier(tiers[index + 1]) else SpeedChange.Standard
    }

/**
 * Amendment A17: what the terminal chose, for the composer to show where it cannot offer. Amendment
 * A40: one setting at a time — a shared Claude session is typed into for the model and the effort,
 * so its card is a control and only the permission mode is left standing as a value.
 */
val ChatStore.terminalSettings: List<TerminalSetting>
    get() = TerminalSetting.all(session, agent).filter { setting ->
        when (setting.field) {
            TerminalSetting.Field.modelCard -> !allowsModelCardChanges
            TerminalSetting.Field.permissionMode -> !allowsSettingsChanges(SharedSetting.permissionMode)
        }
    }

/**
 * The value standing in for one control, or null where that control is live. The composer reads it
 * slot by slot so the two keep the order the design fixes — the model card, then the permission
 * picker — whichever of them the terminal still owns.
 */
fun ChatStore.terminalSetting(field: TerminalSetting.Field): TerminalSetting? = terminalSettings.firstOrNull { it.field == field }

/**
 * Amendment A20: a question is answered where you are. The device raises the block from the hook
 * Claude Code runs beside its own dialog and takes whichever answer arrives first, so an attached
 * session's card is live here exactly as it is in the terminal. Only a session the terminal holds
 * outright takes nothing from this app.
 */
val ChatStore.allowsAnswers: Boolean get() = !isReadOnly

val ChatStore.attachHint: ChatStore.AttachHint?
    get() {
        if (!isReadOnly) return null
        val agent = agent ?: return null
        val attach = agent.attach ?: return null
        if (agent.attachReady) return ChatStore.AttachHint.restartSession
        return when (attach) {
            AgentAttach.daemon -> ChatStore.AttachHint.startDaemon
            AgentAttach.extension -> ChatStore.AttachHint.installExtension
            AgentAttach.leader -> ChatStore.AttachHint.enableLeader
            else -> ChatStore.AttachHint.installShim
        }
    }

/**
 * Section 5: `auto` means "send now if idle, otherwise steer or queue". An agent that lists `steer`
 * joins the running turn instead of waiting behind it, so the composer and the status line say so.
 * Amendment A43: an edited queued message goes back into the line instead, so it never steers.
 */
val ChatStore.steersRunningTurn: Boolean
    get() = isRunning && queuedEdit == null && agent?.supports(AgentCapability.steer) == true

/** Why the composer cannot send right now, in the words the user sees. Reconnecting is not among them: that request waits for the socket. */
val ChatStore.sendBlockReason: String?
    get() = when {
        isReadOnly -> L10n.string("Controlled by the terminal")
        !deviceOnline -> L10n.string("That device is offline")
        !canReachGateway -> L10n.string("Offline · your draft is saved")
        unconfirmedSend != null -> L10n.string("Delivery unconfirmed · retry or dismiss first")
        else -> null
    }

/**
 * The one line between the transcript and the message field.
 *
 * It is drawn only when it says something the header above the transcript does not. The header
 * already carries the dot and the state word, and on an attached session it reads
 * `terminal · attached`, so repeating either costs the transcript a row and tells the reader nothing.
 * What is left is what the header cannot say: that this machine cannot be reached, that typing here
 * needs a takeover, what will become of a message typed into a running turn, and what the agent said
 * when it failed.
 */
val ChatStore.statusLine: String?
    get() {
        // Amendment A29: the model is working on the words that just landed in the field. That is
        // the news, and it is over in a second or two.
        if (polishPhase == PolishPhase.Polishing) return L10n.string("Polishing…")
        if (!deviceOnline) return L10n.string("Device offline")
        if (isReadOnly) return terminalControlNotice
        // Amendment A20: a question outranks the turn it interrupted. Nothing is queued behind it, so
        // what a message would become is not the news.
        if (pendingQuestion != null) return L10n.string("Waiting for your answer")
        return when (session.state) {
            SessionState.running -> {
                val queued = session.queued
                if (queued > 0) {
                    L10n.string(if (queued == 1) "Working · %lld message queued" else "Working · %lld messages queued", queued)
                } else {
                    L10n.string(if (steersRunningTurn) "Working · your message will steer the turn"
                                else "Working · your message will be queued")
                }
            }
            // The word "error" is in the header; what the agent said about it is not.
            SessionState.error -> session.stateDetail
            else -> null
        }
    }
