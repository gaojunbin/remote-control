package com.junbingao.remotecontrol.win.chat.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.PendingSend
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.ChatFitWidth
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.hex
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.action-error`: a failed action above the composer, in the danger tint, with a way to put it
 * away. 800 points at most, centred, 8 above the next.
 */
@Composable
fun ActionErrorBanner(text: String, onDismiss: () -> Unit) {
    BannerFrame {
        WithForeground(Color.hex(0x9C2C21)) {
            HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
                ChatText(text, css(FontSize.fs13), Modifier.weight(1f))
                Button(onDismiss, style = btn(ButtonVariant.ghost, ButtonSize.small)) { Text(S.common.dismiss, softWrap = false) }
            }
        }
    }
}

/**
 * `.unconfirmed`: sends the device never confirmed, with Retry under their own request ids and
 * Dismiss. Nothing is ever resent automatically.
 */
@Composable
fun UnconfirmedBanner(pending: List<PendingSend>, onRetry: () -> Unit, onDismiss: () -> Unit) {
    BannerFrame {
        WithForeground(Palette.ink) {
            HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(S.composer.deliveryUnconfirmed) }
                    append(" ")
                    append(S.composer.deliveryUnconfirmedBody)
                }
                Text(text, css(FontSize.fs13), Modifier.weight(1f))
                Button(onRetry, style = btn(ButtonVariant.standard, ButtonSize.small)) { Text(S.common.retry, softWrap = false) }
                Button(onDismiss, style = btn(ButtonVariant.ghost, ButtonSize.small)) { Text(S.common.dismiss, softWrap = false) }
            }
        }
    }
}

/**
 * The two bars' shared box: 8 by 16 points of padding inside a one-point `#eccdc9` edge on the
 * danger tint, a 12-point radius, 800 points at most and centred, 8 points above what follows.
 */
@Composable
private fun BannerFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = Space.sp2), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = 800.dp)
                .fillMaxWidth()
                .chatBorder(ChatBorder(width = 1.dp, radius = Radius.md), Color.hex(0xECCDC9))
                .background(Palette.dangerSoft, RoundedCornerShape(Radius.md))
                .padding(1.dp)
                .padding(vertical = Space.sp2, horizontal = Space.sp4),
        ) { content() }
    }
}

/** `.empty.card.chat-missing`: the session the route names is not on the gateway any more. */
@Composable
fun ChatMissing() {
    val model = LocalAppModel.current
    // `margin: auto` in the column: as wide as what it holds, 420 at most.
    Box(Modifier.fillMaxSize().padding(vertical = Space.sp10), contentAlignment = Alignment.TopCenter) {
        ChatFitWidth(maximum = 420.dp) {
            VStack(
                Modifier
                    .background(Palette.surfaceMuted, RoundedCornerShape(Radius.lg))
                    .padding(vertical = Space.sp10, horizontal = Space.sp4),
                spacing = Space.sp3,
            ) {
                Text(
                    S.errors.sessionMissing,
                    css(FontSize.fs14, weight = FontWeight.Medium),
                    Modifier.padding(bottom = Space.sp1),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                Button({ model.router.go(Route.Sessions) }, style = btn(ButtonVariant.standard, ButtonSize.small)) {
                    Text(S.nav.backToSessions, softWrap = false)
                }
            }
        }
    }
}
