package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.QuestionDraft
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.LocalChatButtonHovered
import com.junbingao.remotecontrol.win.chat.support.chatBare
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.shared.Answering
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `web/src/features/chat/blocks/QuestionCard.tsx`: one card for one or several questions —
 * options, free text, secret fields — answerable wherever the composer is (A20). What the card
 * holds lives in the conversation's `QuestionDraft`, so the composer's Answer submits exactly what
 * is on screen.
 */
@Composable
fun QuestionCard(
    question: QuestionPayload,
    chat: ChatStore,
    onAnswer: suspend (requestID: String, answers: Map<String, QuestionAnswer>) -> Boolean,
) {
    val model = LocalAppModel.current
    var busy by remember { mutableStateOf(false) }
    val pending = question.status == RequestStatus.pending
    val draft = chat.draft(question)
    VStack(Modifier.cardFrame(resolved = !pending), spacing = Space.sp3, alignment = Alignment.Start) {
        for (item in question.questions) QuestionItemView(item, question, chat, draft, pending)
        if (pending) {
            CardActions {
                Disabled(!Answering.cardComplete(question.questions, draft) || busy) {
                    // A failed answer keeps everything that was filled in: the page's banner says
                    // what went wrong and the card is ready to be submitted again.
                    Button(
                        {
                            busy = true
                            model.tasks.launch {
                                onAnswer(question.requestID, Answering.cardAnswers(question.questions, draft))
                                busy = false
                            }
                        },
                        style = btn(ButtonVariant.primary, ButtonSize.small),
                    ) { Text(S.chat.submitAnswer, softWrap = false) }
                }
            }
        } else {
            Text(CardRules.questionResult(question), css(FontSize.fs12), color = Palette.inkSecondary)
        }
    }
    // A question that stops being pending takes its working state with it, however it ended —
    // answered here, in the terminal, or expired. A secret field said the value is not stored, so
    // nothing may be left holding one.
    LaunchedEffect(pending) {
        if (pending) return@LaunchedEffect
        for (item in question.questions) {
            if (chat.draft(question).text(item.id).isNotEmpty()) chat.write("", item.id, question)
        }
    }
}

/** `.question-item`: the prompt, its options and its free-text field. */
@Composable
private fun QuestionItemView(item: QuestionItem, question: QuestionPayload, chat: ChatStore, draft: QuestionDraft, pending: Boolean) {
    VStack(Modifier.fillMaxWidth(), spacing = Space.sp2, alignment = Alignment.Start) {
        ChatText(item.prompt, css(FontSize.fs14))
        if (item.options.isNotEmpty()) {
            VStack(Modifier.fillMaxWidth(), spacing = 6.dp, alignment = Alignment.Start) {
                for (option in item.options) {
                    val selected = if (pending) draft.isChosen(option.id, item.id) else CardRules.answerIncludes(question.answers?.get(item.id), option.id)
                    QuestionOptionButton(option, selected, pending) {
                        // The web picks an option of a single-choice question and keeps it picked
                        // on a second press; only a `multi` question takes it back.
                        if (item.multi || !draft.isChosen(option.id, item.id)) chat.choose(option.id, item, question)
                    }
                }
            }
        }
        if (item.allowText) QuestionField(item, question, chat, draft, pending)
    }
}

/** `.question-option`: a bordered choice, its label and an optional hint. */
@Composable
private fun QuestionOptionButton(option: QuestionOption, selected: Boolean, pending: Boolean, action: () -> Unit) {
    Disabled(!pending) {
        Button(action, Modifier.fillMaxWidth().semantics { this.selected = selected }, style = chatBare) {
            val hovered = LocalChatButtonHovered.current
            val edge = if (selected) Palette.ink else if (hovered && pending) Palette.lineStrong else Palette.line
            VStack(
                Modifier
                    .fillMaxWidth()
                    .chatBorder(ChatBorder(width = 1.dp, radius = Radius.sm), edge)
                    .background(if (selected) Palette.surfaceMuted else Palette.surface, RoundedCornerShape(Radius.sm))
                    .padding(1.dp)
                    .padding(vertical = Space.sp2, horizontal = 10.dp),
                spacing = 2.dp,
                alignment = Alignment.Start,
            ) {
                Text(option.label, css(FontSize.fs13))
                option.description?.let { Text(it, css(FontSize.fs12), color = Palette.inkSecondary) }
            }
        }
    }
}

/**
 * The question's own free-text field: a `.field`, masked for a secret, and showing what was
 * answered once the question is resolved — never a secret.
 */
@Composable
private fun QuestionField(item: QuestionItem, question: QuestionPayload, chat: ChatStore, draft: QuestionDraft, pending: Boolean) {
    val text = if (pending) draft.text(item.id) else CardRules.freeText(question.answers?.get(item.id), secret = item.secret)
    // A resolved card's field takes no keys and no focus, as a disabled field does.
    Box(Modifier.fillMaxWidth().focusProperties { canFocus = pending }.semantics { contentDescription = item.prompt }) {
        WebField(
            text = text,
            onTextChange = { if (pending) chat.write(it, item.id, question) },
            placeholder = if (item.secret) S.chat.secretPlaceholder else S.chat.freeTextPlaceholder,
            secure = item.secret,
        )
    }
}
