package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.Preferences
import com.junbingao.remotecontrol.core.protocol.PreferencesResponse
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A41: the Settings screen's preferences are the account's.
 *
 * The gateway keeps them, `SettingsStore` caches them, and `PreferenceSync` keeps the two equal:
 * what arrives is applied, what the person changes is written up, a field the account has never
 * been told is offered this phone's value once, and nothing that arrives is ever echoed back.
 */
class PreferenceSyncTests {
    /** A store sets the language every string is looked up in; the next suite reads English again. */
    @AfterTest
    fun backToEnglish() = L10n.use(InterfaceLanguage.en)

    /** A store nobody else's run can reach, and nothing to inherit. */
    private fun store(): SettingsStore = SettingsStore(defaults = MemoryUserDefaults())

    private fun hello(preferences: Preferences?): AppFrame = AppFrame.Hello(HelloFrame(
        protocolVersion = RemoteProtocol.version, gatewayVersion = "test", user = UserIdentity(username = "me"),
        devices = emptyList(), sessions = emptyList(), stt = STTConfig.disabled, preferences = preferences, serverTime = 0,
    ))

    /** Every field set, as an account that has been through A41 holds them. */
    private fun everyField(): Preferences = Preferences(
        resumeAfterLimit = false, language = InterfaceLanguage.en, sttLanguage = "auto", polishEnabled = false,
        polishModel = "", polishStrength = PolishStrength.moderate, timelineDetail = TimelineDetail.simple,
    )

    /** The account's values are the app's the moment hello carries them. */
    @Test
    fun helloIsApplied() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)

        sync.receive(hello(Preferences(resumeAfterLimit = true, language = InterfaceLanguage.zhHans, sttLanguage = "zh",
                                       polishEnabled = true, polishModel = "gpt-5.4-mini",
                                       polishStrength = PolishStrength.strong, timelineDetail = TimelineDetail.detailed)))
        assertEquals(InterfaceLanguage.zhHans, settings.language)
        assertEquals("zh", settings.voiceLanguage)
        assertTrue(settings.polishEnabled)
        assertEquals("gpt-5.4-mini", settings.polishModel)
        assertEquals(PolishStrength.strong, settings.polishStrength)
        assertEquals(TimelineDetail.detailed, settings.timelineDetail)

        sync.settle()
        assertTrue(gateway.writes.isEmpty(), "an account that has every field is asked for nothing")
    }

    /** A change made elsewhere moves the control and is not echoed back. */
    @Test
    fun updatedFrameIsAppliedOnce() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))

        sync.receive(AppFrame.PreferencesUpdated(everyField().copy(polishEnabled = true,
                                                                   timelineDetail = TimelineDetail.detailed)))
        assertTrue(settings.polishEnabled, "the switch another app turned on is on here")
        assertEquals(TimelineDetail.detailed, settings.timelineDetail, "and the detail changed with it")

        sync.settle()
        assertTrue(gateway.writes.isEmpty(), "with nothing written back for either of them")
    }

    /** A field the account has none of is offered this phone's value, once. */
    @Test
    fun ownValuesAreOfferedOnce() = runTest {
        val settings = store()
        settings.timelineDetail = TimelineDetail.detailed
        settings.polishModel = "gpt-4.1"
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)

        // The account arrived at A41 with the resume switch and nothing else.
        sync.receive(hello(Preferences(resumeAfterLimit = true)))
        sync.settle()
        val offered = assertNotNull(gateway.writes.firstOrNull())
        assertEquals(TimelineDetail.detailed, offered.timelineDetail, "this phone's detail becomes the account's")
        assertEquals("gpt-4.1", offered.polishModel, "and its model")
        assertEquals(InterfaceLanguage.en, offered.language, "and every other field the account had none of")
        assertEquals("zh", offered.sttLanguage, "Chinese, which a new install listens for (A44)")
        assertEquals(false, offered.polishEnabled)
        assertEquals(PolishStrength.moderate, offered.polishStrength)
        assertNull(offered.resumeAfterLimit, "never the switch, which the account already had")
        assertEquals(1, gateway.writes.size, "one write, not one per field")

        // A second connection of the same sign-in asks for nothing again.
        sync.receive(hello(gateway.held))
        sync.settle()
        assertEquals(1, gateway.writes.size, "and the offer is made once, not once a flap")
    }

    /** A change made on this phone is written up and the reply is the value. */
    @Test
    fun aLocalChangeIsWrittenUp() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))

        settings.polishEnabled = true
        sync.settle()
        assertEquals(1, gateway.writes.size, "the change goes up on its own")
        assertEquals(true, gateway.writes.firstOrNull()?.polishEnabled, "carrying that field")
        assertNull(gateway.writes.firstOrNull()?.language, "and no field nobody touched")
        assertEquals(true, gateway.held.polishEnabled, "so the account now holds it")
        assertTrue(settings.polishEnabled, "and the screen reads what the gateway answered")
    }

    /** Two changes a moment apart reach the gateway in the order they were made. */
    @Test
    fun writesKeepTheirOrder() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))

        // The second change is made while the first is still out. Racing them would let the older one
        // be the last to arrive, and the gateway takes the order they arrive in as the order of truth.
        gateway.hold()
        settings.timelineDetail = TimelineDetail.detailed
        gateway.waitForWrite()
        settings.polishEnabled = true
        gateway.release()
        sync.settle()

        assertEquals(2, gateway.writes.size, "one request each, never both at once")
        assertEquals(TimelineDetail.detailed, gateway.writes.firstOrNull()?.timelineDetail, "the first change first")
        assertEquals(true, gateway.writes.lastOrNull()?.polishEnabled, "and the second behind it")
        assertNull(gateway.writes.lastOrNull()?.timelineDetail, "carrying only what the first one did not settle")
        assertEquals(TimelineDetail.detailed, gateway.held.timelineDetail, "so the account holds both")
        assertEquals(true, gateway.held.polishEnabled)
    }

    /** A change the person undid before the round trip is never written. */
    @Test
    fun aChangeThatNetsToNothingIsNotWritten() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))

        settings.timelineDetail = TimelineDetail.detailed
        settings.timelineDetail = TimelineDetail.simple
        sync.settle()
        assertTrue(gateway.writes.isEmpty(), "the account already reads what the screen does")
        assertEquals(TimelineDetail.simple, settings.timelineDetail)
    }

    /** A gateway that carries no preferences leaves this phone's settings alone. */
    @Test
    fun anOlderGatewayChangesNothing() = runTest {
        val settings = store()
        settings.timelineDetail = TimelineDetail.detailed
        settings.polishEnabled = true
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)

        sync.receive(hello(null))
        sync.settle()
        assertEquals(TimelineDetail.detailed, settings.timelineDetail, "nothing it had is taken away")
        assertTrue(settings.polishEnabled)
        assertTrue(gateway.writes.isEmpty(), "and a gateway that offers none is asked for none")
    }

    /** An auto from before A44 is heard as Chinese and never written back. */
    @Test
    fun aLegacyAutoIsReadAsChinese() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))
        assertEquals("auto", settings.voiceLanguage, "the account's value is taken as it came")
        assertEquals("zh", settings.dictationLanguage, "and the recogniser hears Chinese")
        sync.settle()
        assertTrue(gateway.writes.isEmpty(), "with nothing written back to correct it")

        settings.voiceLanguage = "en"
        sync.settle()
        assertEquals("en", gateway.writes.lastOrNull()?.sttLanguage, "a language picked here goes up")
    }

    /** A phone still holding auto offers the account no dictation language. */
    @Test
    fun aLegacyAutoIsNotOffered() = runTest {
        val settings = store()
        settings.voiceLanguage = "auto"
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)

        sync.receive(hello(Preferences(resumeAfterLimit = true)))
        sync.settle()
        val offered = assertNotNull(gateway.writes.firstOrNull())
        assertNull(offered.sttLanguage, "the account stays unset, which reads as Chinese too")
        assertEquals(InterfaceLanguage.en, offered.language, "while the other fields are offered as before")
    }

    /** Signing out forgets the account's copy. */
    @Test
    fun signingOutForgetsTheAccount() = runTest {
        val settings = store()
        val gateway = RecordingGateway()
        val sync = PreferenceSync(settings = settings, tasks = backgroundScope)
        sync.attach(api = gateway)
        sync.receive(hello(everyField()))

        sync.attach(api = null)
        settings.polishEnabled = true
        sync.settle()
        assertTrue(gateway.writes.isEmpty(), "a signed-out app writes nobody's preferences")
    }

    /**
     * A gateway that keeps the account's preferences the way a real one does and remembers every
     * write, so a test can count them as well as read them. The next write can be held on arrival,
     * which is how the order of two of them is looked at rather than raced.
     */
    private class RecordingGateway : StubGateway(GatewayEndpoint("https://rc.example.com")) {
        val writes = mutableListOf<PreferencePatch>()
        var held = Preferences()
            private set
        private var holdsNextWrite = false
        private var holding: CompletableDeferred<Unit>? = null
        private var arrival = CompletableDeferred<Unit>()

        override suspend fun preferences(): PreferencesResponse = PreferencesResponse(preferences = held)

        override suspend fun patchPreferences(changes: PreferencePatch): PreferencesResponse {
            if (holdsNextWrite) {
                holdsNextWrite = false
                val gate = CompletableDeferred<Unit>()
                holding = gate
                arrival.complete(Unit)
                gate.await()
            }
            writes.add(changes)
            held = held.applying(changes)
            return PreferencesResponse(preferences = held)
        }

        /** Hold the next write until the test lets it answer. */
        fun hold() {
            holdsNextWrite = true
        }

        /** Returns once that write has arrived and is waiting. */
        suspend fun waitForWrite() {
            if (holding != null) return
            arrival.await()
        }

        fun release() {
            holding?.complete(Unit)
            holding = null
        }
    }
}
