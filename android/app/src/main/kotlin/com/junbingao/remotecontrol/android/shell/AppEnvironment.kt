package com.junbingao.remotecontrol.android.shell

import android.content.Context
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.persistence.AppDirectories
import com.junbingao.remotecontrol.android.persistence.SharedPreferencesDefaults
import com.junbingao.remotecontrol.android.screens.alerts.PushController
import com.junbingao.remotecontrol.android.screens.alerts.TurnNotifier
import com.junbingao.remotecontrol.android.security.KeystoreSecretStore
import com.junbingao.remotecontrol.core.persistence.DraftStore
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.InstalledApp
import com.junbingao.remotecontrol.core.state.SessionStore
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import kotlinx.coroutines.CoroutineScope

/**
 * The app model as Android builds it: the core's stores over the phone's own seams — the defaults
 * on `SharedPreferences`, the token in the Keystore, drafts and the transcript cache in the
 * no-backup directory — measured against `apps.android` (A46). The one place these are chosen.
 */
object AppEnvironment {
    fun model(context: Context, options: LaunchOptions, tasks: CoroutineScope): AppModel {
        val app = context.applicationContext
        val defaults = SharedPreferencesDefaults.standard(app)
        return AppModel(
            tasks = tasks,
            connection = connection(app, options, tasks),
            settings = SettingsStore(defaults),
            sessions = SessionStore(defaults),
            push = PushController(app),
            turns = TurnNotifier(app),
            drafts = DraftStore(AppDirectories.drafts(app)),
            options = options,
        )
    }

    /**
     * The gateway the app reaches. `--demo-account` puts the offline gateway behind the sign-in
     * form instead of around it, which is how the account screens are driven with no gateway to
     * reach.
     */
    private fun connection(context: Context, options: LaunchOptions, tasks: CoroutineScope): ConnectionStore {
        val cache = LocalCache(AppDirectories.cache(context))
        if (options.demoAccount) {
            return ConnectionStore.offlineDemo(tasks, cache, installedApp = InstalledApp.android, registrationOpen = options.registrationOpen)
        }
        val secrets = KeystoreSecretStore(context)
        return ConnectionStore(tasks, InstalledApp.android, cache, makeAPI = { endpoint -> GatewayHTTPClient(endpoint, secrets = secrets) })
    }
}
