package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.GatewayAPI
import com.junbingao.remotecontrol.core.state.InstalledApp
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.core.transport.GatewaySocket
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the one `ConnectionStore` the app runs on (A46: it measures itself against
 * `apps.windows`), with this run's secrets and cache, and every API it makes wrapped so the login
 * page can read what a sign-in ended with.
 */
object ConnectionFactory {
    fun make(options: LaunchOptions, persistence: Persistence, recorder: SignInRecorder, tasks: CoroutineScope): ConnectionStore {
        if (options.demoAccount) {
            // The offline demo behind the sign-in form: what
            // `ConnectionStore.offlineDemo(tasks, cache, installedApp, registrationOpen)` builds,
            // with this run's cache and the recorder around the gateway.
            val gateway = DemoGateway(registrationOpen = options.registrationOpen)
            return ConnectionStore(
                tasks = tasks, installedApp = InstalledApp.windows, cache = persistence.cache,
                makeAPI = { RecordingGatewayAPI(gateway, recorder) },
                makeChannel = { gateway },
            )
        }
        val secrets = persistence.secrets
        return ConnectionStore(
            tasks = tasks, installedApp = InstalledApp.windows, cache = persistence.cache,
            makeAPI = { endpoint -> RecordingGatewayAPI(GatewayHTTPClient(endpoint, secrets = secrets), recorder) },
            makeChannel = { api ->
                // The socket authenticates with the HTTP client's own token, so it is handed the
                // client the wrapper holds, never a new one.
                GatewaySocket(client = httpClient(api) ?: GatewayHTTPClient(api.endpoint, secrets = secrets))
            },
        )
    }

    /**
     * The HTTP client an API built here stands on. Every one is wrapped in a `RecordingGatewayAPI`,
     * so a plain cast of the store's API finds nothing: whatever reaches the gateway past
     * `GatewayAPI` — the app socket, the dictation socket — takes its client, and its token, from
     * here.
     */
    fun httpClient(behind: GatewayAPI?): GatewayHTTPClient? =
        ((behind as? RecordingGatewayAPI)?.base ?: behind) as? GatewayHTTPClient
}
