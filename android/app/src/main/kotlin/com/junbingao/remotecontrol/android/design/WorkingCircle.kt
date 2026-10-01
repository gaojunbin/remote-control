package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.junbingao.remotecontrol.android.system.ActivityIndicator

/**
 * The composer's primary slot while the app, and not the person, has the next move: Send's
 * circle with a spinner in it.
 *
 * `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner becomes Send**.
 * Deliberately not a button, and not a disabled one either — a control that looks live and does
 * nothing is the one thing the row must never show. It carries Send's colour and Send's size, and
 * it says in words what it is waiting for, because a spinner alone says only that something is
 * happening.
 */
@Composable
fun WorkingCircle(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Theme.Touch.primary)
            .background(Theme.accent, CircleShape)
            .clearAndSetSemantics { contentDescription = label }
            .testTag("composer.working"),
        contentAlignment = Alignment.Center,
    ) {
        ActivityIndicator(tint = Theme.onAccent)
    }
}
