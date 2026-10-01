package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.ChatWrapRow
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.BoxShadow
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.boxShadow
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import kotlinx.coroutines.launch

/**
 * `web/src/features/chat/blocks/ApprovalCard.tsx`: a bordered card with the agent's own options
 * as buttons, accept first and reject last (`docs/DESIGN.md` § "Approvals and questions"). Once
 * resolved or expired it goes quiet and says who decided and what.
 */
@Composable
fun ApprovalCard(approval: ApprovalPayload, onDecide: suspend (requestID: String, optionID: String) -> Unit) {
    val model = LocalAppModel.current
    var busy by remember { mutableStateOf<String?>(null) }
    val pending = approval.status == RequestStatus.pending
    VStack(Modifier.cardFrame(resolved = !pending), spacing = Space.sp3, alignment = Alignment.Start) {
        ApprovalHead(approval)
        approval.diff?.takeIf { !it.patch.isNullOrEmpty() }?.let { DiffView(it) }
        approval.input?.let { OutputBox(JSONText.stringify(it)) }
        if (pending) {
            CardActions {
                for (option in CardRules.ordered(approval.options)) {
                    Disabled(busy != null) {
                        Button(
                            {
                                busy = option.id
                                model.tasks.launch {
                                    onDecide(approval.requestID, option.id)
                                    busy = null
                                }
                            },
                            style = btn(variant(option), ButtonSize.small),
                        ) { Text(option.label, softWrap = false) }
                    }
                }
            }
        } else {
            Text(CardRules.approvalResult(approval), css(FontSize.fs12), color = Palette.inkSecondary)
        }
    }
}

/** `.approval-head`: the tool's icon, its name and the monospace title. */
@Composable
private fun ApprovalHead(approval: ApprovalPayload) {
    WithForeground(Palette.inkTertiary) {
        HStack(spacing = Space.sp2) {
            Icon(ToolIcon.icon(approval.kind), size = 14.dp)
            Text(approval.tool, css(FontSize.fs13, weight = FontWeight.Medium), color = Palette.ink, softWrap = false)
            ChatText(approval.title, css(FontSize.fs12, mono = true), color = Palette.inkSecondary, maxLines = 1)
        }
    }
}

private fun variant(option: ApprovalOption): ButtonVariant = when (option.style) {
    OptionStyle.primary -> ButtonVariant.primary
    OptionStyle.danger -> ButtonVariant.danger
    else -> ButtonVariant.standard
}

/** `.approval-actions`: the buttons in a row that wraps, 8 points apart. */
@Composable
fun CardActions(content: @Composable () -> Unit) {
    ChatWrapRow(spacing = Space.sp2, content = content)
}

/**
 * `.approval` and `.question`: a 12-point card with a strong edge and a soft lift while it waits,
 * a plain edge and three-quarter ink once it has been answered. The 16 points of padding sit
 * inside the one-point edge.
 */
fun Modifier.cardFrame(resolved: Boolean): Modifier {
    val shape = RoundedCornerShape(Radius.md)
    return this
        .then(if (resolved) Modifier.alpha(0.75f) else Modifier)
        .boxShadow(if (resolved) BoxShadow(emptyList()) else Shadow.one, shape)
        .chatBorder(ChatBorder(width = 1.dp, radius = Radius.md), if (resolved) Palette.line else Palette.lineStrong)
        .background(Palette.surface, shape)
        .fillMaxWidth()
        .padding(Space.sp4 + 1.dp)
}
