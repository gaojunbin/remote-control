package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The identifier the primary slot's spinner answers to. The circle sets it on itself inside the
 * semantics it clears for its label, which wipes it; given from outside, it is the node's own.
 */
internal object WorkingSlot {
    val identified: Modifier = Modifier.testTag("composer.working")
}
