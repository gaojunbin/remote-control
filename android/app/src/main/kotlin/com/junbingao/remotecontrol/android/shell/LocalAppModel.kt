package com.junbingao.remotecontrol.android.shell

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The model every screen reads, as `@Environment(AppModel.self)` hands the iPhone's down: the root
 * provides it, and a screen reads `LocalAppModel.current`.
 */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("A screen is drawn outside RootView, which provides the model.") }
