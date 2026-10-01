package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One run of an action at a time, and a way for its control to say so.
 *
 * Retry on the "Delivery unconfirmed" banner reuses the original request id (`docs/DESIGN.md`
 * § "The composer"), so two overlapping retries would put two requests under one id. The button
 * is disabled while [isBusy], and a tap that beats the redraw starts nothing either. Used from the
 * main thread, as the iPhone's is from the main actor.
 */
class OneAtATime {
    var isBusy by mutableStateOf(false)
        private set

    suspend fun run(work: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        try {
            work()
        } finally {
            isBusy = false
        }
    }
}
