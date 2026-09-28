import Foundation
import RCCore

/// Builds the one `ConnectionStore` the app runs on (A45: it measures itself
/// against `apps.macos`), with this run's secrets and cache, and every API it
/// makes wrapped so the login page can read what a sign-in ended with.
@MainActor
enum ConnectionFactory {
    static func make(options: LaunchOptions, persistence: Persistence,
                     recorder: SignInRecorder) -> ConnectionStore {
        if options.demoAccount {
            // The offline demo behind the sign-in form: what
            // `ConnectionStore.offlineDemo(installedApp:registrationOpen:)` builds,
            // with this run's cache and the recorder around the gateway.
            let gateway = DemoGateway(registrationOpen: options.registrationOpen)
            return ConnectionStore(installedApp: .macos, cache: persistence.cache,
                                   makeAPI: { _ in RecordingGatewayAPI(base: gateway, recorder: recorder) },
                                   makeChannel: { _ in gateway })
        }
        let secrets = persistence.secrets
        return ConnectionStore(
            installedApp: .macos, cache: persistence.cache,
            makeAPI: { endpoint in
                RecordingGatewayAPI(base: GatewayHTTPClient(endpoint: endpoint, secrets: secrets), recorder: recorder)
            },
            makeChannel: { api in
                // The socket authenticates with the HTTP client's own token, so
                // it is handed the client the wrapper holds, never a new one.
                let client = (api as? RecordingGatewayAPI)?.base as? GatewayHTTPClient
                    ?? GatewayHTTPClient(endpoint: api.endpoint, secrets: secrets)
                return GatewaySocket(client: client)
            })
    }
}
