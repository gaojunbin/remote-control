package com.junbingao.remotecontrol.android.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/**
 * The one haptic the iPhone app plays: `.sensoryFeedback(.selection, trigger:)`, the tick of a
 * picker wheel, which `StopSlider` plays for every stop the thumb crosses and `CommandPanel` for
 * a row taken. Android's segment tick is the same gesture's feedback; phones older than Android 14
 * get the clock tick, its predecessor. Both honour the system's touch-feedback setting.
 */
object Haptics {
    fun selection(view: View) {
        val tick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(tick)
    }
}

/**
 * One selection tick each time [trigger] changes, and none when it first appears — as SwiftUI's
 * modifier fires on a change of its trigger.
 */
fun Modifier.selectionFeedback(trigger: Any?): Modifier = composed {
    val view = LocalView.current
    var last by remember { mutableStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger != last) {
            last = trigger
            Haptics.selection(view)
        }
    }
    this
}
