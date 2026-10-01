package com.junbingao.remotecontrol.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.navigation.Navigator
import com.junbingao.remotecontrol.android.system.LocalPresenter

/**
 * The one answer to the system's back gesture and button (`docs/DESIGN.md` § "The Android app"):
 * close a menu, a sheet or a dialog first, then leave the screen, as the iPhone's back button and
 * edge swipe do. At a stack's root it lets the system have Back, which leaves the app.
 */
@Composable
fun BackRouter(navigator: Navigator?) {
    val presenter = LocalPresenter.current
    BackHandler(enabled = presenter.isPresenting || navigator?.canPop == true) {
        if (!presenter.dismissTop()) navigator?.pop()
    }
}
