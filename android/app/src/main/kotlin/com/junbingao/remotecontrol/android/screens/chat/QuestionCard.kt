package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.GrowingTextField
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.TextField
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.QuestionDraft
import com.junbingao.remotecontrol.core.state.allowsAnswers

/**
 * One or more questions from the agent. Nothing is sent until Submit, a multi-select needs an
 * explicit choice, and a secret answer uses a secure field and never enters the ordinary draft.
 *
 * Amendment A20: on an attached session the terminal shows its own dialog at the same moment and
 * whichever is answered first wins, so the card is live here too and a resolved one says where the
 * answer came from. What is chosen on the card lives in the store rather than in this view, because
 * the composer submits the same answers with the draft added to them.
 */
@Composable
internal fun QuestionCard(payload: QuestionPayload, chat: ChatStore) {
    val model = LocalAppModel.current
    var isSending by remember { mutableStateOf(false) }
    val actionable = payload.status.isActionable
    val isActive = actionable && chat.allowsAnswers
    val draft = chat.draft(payload)
    Column(Modifier.cardEdge(stroke = Theme.border, width = 0.5.dp), verticalArrangement = Arrangement.spacedBy(Theme.Space.medium)) {
        Foreground(if (actionable) Theme.attention else Theme.inkSecondary) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sf.questionmarkCircle, font = SystemFont.footnote)
                Text(
                    L10n.string(if (actionable) "The agent has a question" else "Answered"),
                    Modifier.weight(1f).testTag("chat.question"),
                    style = SystemFont.footnote.weight(FontWeight.Medium),
                )
            }
        }
        for (question in payload.questions) {
            key(question.id) { Question(question, payload, chat, draft, isActive) }
        }
        when {
            actionable -> Button(
                onClick = {
                    if (!isSending) {
                        isSending = true
                        model.perform {
                            chat.submitAnswer(payload)
                            isSending = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("question.submit"),
                enabled = !isSending && isActive && draft.answersEveryQuestion(payload.questions),
                style = PrimaryButtonStyle(),
            ) { Text(L10n.string("Submit")) }
            payload.status == RequestStatus.expired ->
                Text(L10n.string("This question expired before it was answered."), style = SystemFont.footnote, color = Theme.inkSecondary)
            else -> payload.by?.let { by ->
                Text(QuestionWords.source(by), Modifier.testTag("question.resolution"), style = SystemFont.footnote, color = Theme.inkSecondary)
            }
        }
    }
}

@Composable
private fun Question(question: QuestionItem, payload: QuestionPayload, chat: ChatStore, draft: QuestionDraft, isActive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Text(question.prompt, style = SystemFont.subheadline, color = Theme.ink)
        for (option in question.options) {
            key(option.id) {
                val chosen = draft.isChosen(option.id, question.id)
                Button(
                    onClick = { chat.choose(option.id, of = question, question = payload) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Theme.Touch.minimum).disabledLook(isActive),
                    enabled = isActive,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                        Icon(QuestionWords.symbol(question, chosen), tint = if (chosen) Theme.ink else Theme.resting)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(option.label, color = Theme.ink)
                            option.description?.let { Text(it, style = SystemFont.caption, color = Theme.inkSecondary) }
                        }
                    }
                }
            }
        }
        if (question.allowText) {
            val text = draft.text(question.id)
            // `.frame(minHeight:)`: a target a thumb can hit, with the line centred in it as
            // SwiftUI centres what a frame holds.
            Box(Modifier.heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.CenterStart) {
                if (question.secret) {
                    TextField(
                        L10n.string("Your answer"),
                        text,
                        { chat.write(it, itemID = question.id, question = payload) },
                        secure = true,
                        enabled = isActive,
                    )
                } else {
                    GrowingTextField(
                        L10n.string("Your answer"),
                        text,
                        { chat.write(it, itemID = question.id, question = payload) },
                        enabled = isActive,
                    )
                }
            }
        }
    }
}

internal object QuestionWords {
    /** Amendment A20: the same wording the approval card uses, because it is the same fact — somebody else got there first. */
    fun source(by: EventSource): String = when (by) {
        EventSource.terminal -> L10n.string("answered in the terminal")
        else -> L10n.string("answered here")
    }

    fun symbol(question: QuestionItem, chosen: Boolean): SfSymbol = when {
        question.multi -> if (chosen) Sf.checkmarkSquareFill else Sf.square
        else -> if (chosen) Sf.largecircleFillCircle else Sf.circle
    }
}
