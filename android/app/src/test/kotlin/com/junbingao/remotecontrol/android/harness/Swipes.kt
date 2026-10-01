package com.junbingao.remotecontrol.android.harness

import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.swipeLeft

/**
 * XCUITest's `swipeLeft()` on a row with several buttons: the buttons uncovered, short of the full
 * swipe that would run the one nearest the edge — two thirds of the row, slowly enough to be a
 * drag rather than a fling, which opens three or four buttons in either language.
 */
fun TouchInjectionScope.swipeOpen() = swipeLeft(startX = width * 0.95f, endX = width * 0.3f, durationMillis = 400)
