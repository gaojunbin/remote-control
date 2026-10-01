package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.weight

/**
 * `.confirmationDialog(_:isPresented:titleVisibility:actions:message:)` on an iPhone running
 * iOS 26: an action sheet of glass over the foot of the dimmed screen, the message centred above
 * its buttons, and no Cancel — a tap outside or Back is the way out, which is why the iPhone's
 * dialog "draws the destructive button alone" (`docs/IOS.md` § "The session list").
 */
@Composable
fun ConfirmationDialog(
    isPresented: Boolean,
    title: String,
    onDismiss: () -> Unit,
    titleVisible: Boolean = false,
    message: String? = null,
    actions: List<AlertAction>,
) {
    Present(PresentationKind.confirmationDialog, isPresented, onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassPanel(ContinuousShape(AlertMetrics.corner), Glass.alert)
                .padding(top = 20.dp, bottom = AlertMetrics.buttonInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.padding(horizontal = AlertMetrics.textInset),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (titleVisible) {
                    Text(title, style = SystemFont.footnote.weight(FontWeight.SemiBold), color = AlertMetrics.messageColor, alignment = TextAlign.Center)
                }
                if (message != null) {
                    Text(message, style = SystemFont.footnote, color = AlertMetrics.messageColor, alignment = TextAlign.Center)
                }
            }
            Box(Modifier.padding(top = if (titleVisible || message != null) 16.dp else 0.dp)) {
                AlertButtons(
                    actions.filter { it.role != ActionRole.cancel },
                    onDismiss,
                    Modifier.padding(horizontal = AlertMetrics.buttonInset),
                )
            }
        }
    }
}

@Composable
internal fun DialogLayer(layer: Presentation, transition: Transition<Boolean>, body: @Composable () -> Unit) {
    val progress = transition.progress(IosDurations.present, IosDurations.dismiss)
    val safe = safeArea()
    Box(Modifier.fillMaxSize()) {
        Scrim(progress, if (layer.options.dismissible) layer.onDismiss else null)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 8.dp, end = 8.dp, bottom = maxOf(safe.bottom, 8.dp))
                .graphicsLayer { translationY = (1 - progress) * (size.height + 40.dp.toPx()) },
        ) { body() }
    }
}
