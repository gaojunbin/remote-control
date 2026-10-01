package com.junbingao.remotecontrol.android.screens.users

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.strings.L10n

/** The Settings stack's route to the accounts screen, which the iPhone pushes with `navigationDestination(isPresented:)`. */
data object UsersRoute

/** Placeholder for `android-settings`, which ports the iPhone's `UsersView` here. */
@Composable
fun UsersView() {
    NavigationScreen(L10n.string("Users")) { }
}
