package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.win.app.ConnectionFactory
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.RecordingGatewayAPI
import com.junbingao.remotecontrol.win.app.SignInRecorder
import com.junbingao.remotecontrol.win.app.httpClient
import com.junbingao.remotecontrol.win.app.signIn
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.chat.composer.AppComposerHost
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dictation reaches the gateway through the HTTP client behind the store's API. Every API the app
 * builds is wrapped (`RecordingGatewayAPI`), and a dictation that cast the store's API straight to
 * `GatewayHTTPClient` would find nothing and end the moment it started with "Transcription failed.".
 */
class VoiceGatewayTests {
    @Test
    fun theClientBehindTheWrapperIsTheOneFound() {
        val client = GatewayHTTPClient(GatewayEndpoint("https://rc.example.com"), secrets = MemorySecretStore())
        val wrapped = RecordingGatewayAPI(base = client, recorder = SignInRecorder())
        assertSame(client, ConnectionFactory.httpClient(behind = wrapped))
        assertSame(client, ConnectionFactory.httpClient(behind = client))
        assertNull(ConnectionFactory.httpClient(behind = null))
        assertNull(ConnectionFactory.httpClient(behind = DemoGateway()))
    }

    /**
     * The composer's own wiring against a gateway: sign in as the app does, open the dictation socket
     * the composer opens, speak into it, and hear a partial and the final transcript back. It needs a
     * gateway, so it runs only when `RC_MOCK_GATEWAY` names one — the web's mock gateway
     * (`cd web && npm run mock`, `RC_MOCK_GATEWAY=http://127.0.0.1:8787`).
     */
    @Test
    fun aDictationOnAGatewayHearsItsTranscript() {
        val origin = System.getenv("RC_MOCK_GATEWAY")
        assumeTrue(origin != null, "RC_MOCK_GATEWAY names no gateway")
        ModelHarness(LaunchOptions(ephemeral = true)).use { harness ->
            harness.run {
                signIn(origin = origin!!, username = "admin", password = "dev")
                assertTrue(isSignedIn)
                assertNotNull(httpClient, "the dictation socket has a client to authenticate with")

                val heard = mutableListOf<SpeechEvent>()
                val stream = AppComposerHost.gatewaySpeech(this).socket { heard += it }
                stream.start()
                // Half a second of silence every 100 ms, for the mock's two-second partial.
                repeat(25) {
                    stream.append(ByteArray(3200))
                    delay(100)
                }
                stream.stop()
                var waited = 0
                while (heard.none { it is SpeechEvent.Final } && waited < 5_000) {
                    delay(50)
                    waited += 50
                }
                assertTrue(heard.any { it is SpeechEvent.Partial }, "a partial: $heard")
                assertTrue(heard.any { it is SpeechEvent.Final && it.text.isNotEmpty() }, "a final transcript: $heard")
                assertTrue(heard.none { it is SpeechEvent.Failed }, "no failure: $heard")
                signOut()
            }
        }
    }
}
