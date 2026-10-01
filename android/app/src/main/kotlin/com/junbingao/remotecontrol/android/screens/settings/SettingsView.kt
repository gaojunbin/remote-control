package com.junbingao.remotecontrol.android.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.BuildConfig
import com.junbingao.remotecontrol.android.gallery.GalleryRoute
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.push.rememberNotificationPermissionRequest
import com.junbingao.remotecontrol.android.screens.users.UsersRoute
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.android.security.currentSceneState
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.ConfirmationDialog
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.GatewayHost
import com.junbingao.remotecontrol.core.state.InstalledApp
import kotlinx.coroutines.flow.drop

/**
 * Who you are, what happens while you are away, and how the app reads.
 *
 * `docs/DESIGN.md` § "The Settings screen" (owner's ruling, 2026-09-18): a header on the canvas,
 * four groups named for the question they answer — Account, While you're away, Voice, Reading —
 * then Security, which is the phone's own, and the versions to close it. Every row is a title and
 * one sentence with its control at the trailing edge; nothing is a footnote, and no rule is drawn
 * between two rows.
 */
@Composable
fun SettingsView() {
    val model = LocalAppModel.current
    val navigator = LocalNavigator.current
    val settings = model.settings
    var showsDiagnostics by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showsPassword by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // Android asks for the notification permission from the activity on screen, so the screen
    // whose switch asks holds the request.
    val notifications = rememberNotificationPermissionRequest()

    // Read once and handed down: a sentence a store built with `L10n.string` keeps the language it
    // was built in, so the groups are rebuilt when the language changes rather than when they are
    // left and re-entered.
    val language = settings.language
    NavigationScreen(L10n.string("Settings"), listState = list) { insets ->
        InsetGroupedList(Modifier.fillMaxSize().testTag("settings.list"), list, insets.padding()) {
            section(key = "identity") {
                row(key = "identity.header", style = canvasRow) {
                    SettingsIdentityHeader(user = model.connection.user, host = host(model), phase = model.connection.phase,
                                           language = language)
                }
            }
            SettingsAccountGroup(
                language,
                // The Users row is a button like the rows beside it, so the screen it opens is
                // pushed from here rather than by a link inside the group.
                users = { navigator?.push(UsersRoute) },
                changePassword = { showsPassword = true },
                signOut = { confirmSignOut = true },
            )
            SettingsAwayGroup(language)
            SettingsVoiceGroup(language)
            SettingsReadingGroup(language)
            SettingsSecurityGroup(language)
            section(key = "versions") {
                row(key = "versions.row", style = canvasRow) {
                    SettingsVersionsRow(gatewayVersion = model.connection.gatewayVersion, language = language,
                                        diagnostics = { showsDiagnostics = true })
                }
            }
        }
    }

    // As the iPhone's `onAppear`, and again whenever the app comes back to the front, which is when
    // a permission changed in Android Settings is first seen.
    val foreground = SceneRule.isForeground(currentSceneState())
    LaunchedEffect(foreground) { if (foreground) model.attachPush() }
    // The switch's own change, and only that: the value it starts with is already reconciled.
    LaunchedEffect(settings) {
        snapshotFlow { settings.notificationsEnabled }.drop(1).collect { enabled ->
            model.push.setEnabled(enabled)
            if (enabled) model.push.requestAuthorizationIfNeeded { notifications.request() }
        }
    }

    Sheet(showsDiagnostics, onDismiss = { showsDiagnostics = false }) {
        DiagnosticsView(
            report(model),
            dismiss = { showsDiagnostics = false },
            openGallery = if (BuildConfig.DEBUG) {
                {
                    showsDiagnostics = false
                    navigator?.push(GalleryRoute)
                }
            } else {
                null
            },
        )
    }
    Sheet(showsPassword, onDismiss = { showsPassword = false }) {
        PasswordSheet { showsPassword = false }
    }
    ConfirmationDialog(
        confirmSignOut,
        L10n.string("Sign out of this gateway?"),
        onDismiss = { confirmSignOut = false },
        titleVisible = true,
        // What the row promised is what the dialog explains.
        message = signOutSentence,
        actions = listOf(
            AlertAction(L10n.string("Sign out"), ActionRole.destructive, tag = "settings.signOut.confirm") { model.perform { signOut() } },
            AlertAction(L10n.string("Cancel"), ActionRole.cancel),
        ),
    )
}

/**
 * A row that is not in a group: the header and the versions line sit on the canvas, at the margin
 * the group surfaces are drawn to.
 */
private val canvasRow = RowStyle(insets = PaddingValues(0.dp), background = Color.Transparent, separator = false)

/**
 * The gateway origin without its scheme. The demo reaches nothing, so it says what it is instead
 * of naming a host nobody can visit.
 */
private fun host(model: AppModel): String {
    val origin = model.connection.endpoint?.origin
    if (model.isDemo || origin == null) return L10n.string("Demo")
    return GatewayHost.of(origin)
}

private fun report(model: AppModel): String = model.settings.diagnosticReport(
    app = InstalledApp.android,
    appVersion = AppBuild.version,
    platform = "Android",
    osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    phase = model.connection.phase,
    deviceCount = model.connection.devices.size,
    sessionCount = model.connection.sessions.size,
    sttEnabled = model.connection.stt.enabled,
    isDemo = model.isDemo,
)
